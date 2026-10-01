package dev.ignition.jakbridge;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;

/**
 * JAK mode: you play Jak and Daxter in Jak's window, and Minecraft is the "Steve layer":
 *  - Steve is placed at Jak's (hidden) body every frame and looks where Jak's first-person camera
 *    looks, so Minecraft renders exactly Jak's view.
 *  - Mouse buttons / wheel / 1-9 pressed in Jak's window drive Minecraft (break, place, pick,
 *    hotbar).
 *  - The sky is transparent, and Jak's level collision is drawn into the depth buffer only, so
 *    Minecraft blocks and mobs are hidden behind Jak's walls.
 *  - The finished frame (world + hand + HUD) is written to shared memory, and Jak draws it on top
 *    of its own frame.
 */
public final class JakHost {
    private static final long HOST_TIMEOUT_NANOS = 400_000_000L;
    private static final int PROXY_RADIUS_CHUNKS = 5;

    private static McFrameWriter writer;
    private static boolean writerFailed = false;
    private static boolean captureBroken = false;
    private static boolean proxyBroken = false;
    private static int prevButtons = 0;
    private static Matrix4f lastPositionMatrix;
    private static Matrix4f lastProjectionMatrix;
    private static Vec3d lastCameraPos;

    private JakHost() {
    }

    /** Fresh camera/body/input from Jak in JAK mode, else null. */
    public static BridgeSocket.HostState fresh(BridgeSocket bridge) {
        if (bridge == null || JakBridgeClient.mode() != JakBridgeClient.Mode.JAK) return null;
        BridgeSocket.HostState h = bridge.latestHost();
        if (h == null || System.nanoTime() - h.receivedNanos > HOST_TIMEOUT_NANOS) return null;
        return h;
    }

    public static boolean active() {
        return fresh(JakBridgeClient.bridge()) != null;
    }

    /**
     * Put Steve exactly at Jak's body, looking along Jak's camera. Called right before Minecraft
     * computes its camera (CameraMixin) and every tick.
     */
    public static void applyToPlayer(ClientPlayerEntity player, int yOffset) {
        BridgeSocket.HostState h = fresh(JakBridgeClient.bridge());
        if (h == null || player == null) return;
        double x = h.feetX, y = h.feetY + yOffset, z = h.feetZ;
        player.setPosition(x, y, z);
        // no interpolation between ticks: the camera must be exactly Jak's
        player.prevX = x;
        player.prevY = y;
        player.prevZ = z;
        player.lastRenderX = x;
        player.lastRenderY = y;
        player.lastRenderZ = z;
        player.setYaw(h.yaw);
        player.setPitch(h.pitch);
        player.prevYaw = h.yaw;
        player.prevPitch = h.pitch;
        player.setHeadYaw(h.yaw);
        player.setVelocity(Vec3d.ZERO);
        player.fallDistance = 0f;
        player.setNoGravity(true);
    }

    public static void releasePlayer(ClientPlayerEntity player) {
        if (player != null) player.setNoGravity(false);
    }

    private static void key(KeyBinding binding, boolean pressed, boolean wasPressed) {
        InputUtil.Key k = KeyBindingHelper.getBoundKeyOf(binding);
        KeyBinding.setKeyPressed(k, pressed);
        if (pressed && !wasPressed) KeyBinding.onKeyPressed(k);
    }

    /** Start of every client tick: feed the clicks / wheel / number keys from Jak's window. */
    public static void injectInput(MinecraftClient client, BridgeSocket bridge) {
        BridgeSocket.HostState h = fresh(bridge);
        if (h == null || client.player == null) {
            if (prevButtons != 0) {
                key(client.options.attackKey, false, true);
                key(client.options.useKey, false, true);
                key(client.options.pickItemKey, false, true);
                prevButtons = 0;
            }
            return;
        }
        int b = h.buttons;
        key(client.options.attackKey, (b & BridgeSocket.HOST_ATTACK) != 0, (prevButtons & BridgeSocket.HOST_ATTACK) != 0);
        key(client.options.useKey, (b & BridgeSocket.HOST_USE) != 0, (prevButtons & BridgeSocket.HOST_USE) != 0);
        key(client.options.pickItemKey, (b & BridgeSocket.HOST_PICK) != 0, (prevButtons & BridgeSocket.HOST_PICK) != 0);
        prevButtons = b;

        var inv = client.player.getInventory();
        int hotbar = bridge.takeHotbar();
        if (hotbar >= 1 && hotbar <= 9) inv.selectedSlot = hotbar - 1;
        int wheel = bridge.takeWheel();
        if (wheel != 0) inv.selectedSlot = Math.floorMod(inv.selectedSlot - wheel, 9);
    }

    /** WorldRenderEvents.START: remember this frame's camera matrices for the depth proxy. */
    public static void rememberMatrices(WorldRenderContext ctx) {
        lastPositionMatrix = new Matrix4f(ctx.positionMatrix());
        lastProjectionMatrix = new Matrix4f(ctx.projectionMatrix());
        lastCameraPos = ctx.camera().getPos();
    }

    /**
     * In place of the sky (after Minecraft cleared the screen, before terrain): make the background
     * transparent, then draw Jak's collision into the depth buffer only.
     */
    public static boolean replaceSky() {
        if (!active()) return false;
        RenderSystem.clearColor(0f, 0f, 0f, 0f);
        RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC);
        if (!proxyBroken) {
            try {
                drawDepthProxy();
            } catch (Throwable t) {
                proxyBroken = true;
                JakBridgeClient.LOG.error("Jak depth proxy failed; blocks will draw in front of Jak's walls", t);
            }
        }
        return true;
    }

    private static void drawDepthProxy() {
        CollisionVoxels v = JakBridgeClient.collision().voxels();
        if (v == null || lastPositionMatrix == null || lastCameraPos == null) return;
        final double cx = lastCameraPos.x, cy = lastCameraPos.y - JakBridgeClient.collision().yOffset(), cz = lastCameraPos.z;

        BufferBuilder bb = Tessellator.getInstance().begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION);
        int[] count = {0};
        v.forEachTriangleListNear(cx, cz, PROXY_RADIUS_CHUNKS, tris -> {
            float[] d = tris.data();
            int n = tris.count() * 9;
            for (int i = 0; i < n; i += 3) {
                bb.vertex((float) (d[i] - cx), (float) (d[i + 1] - cy), (float) (d[i + 2] - cz));
            }
            count[0] += tris.count();
        });
        if (count[0] == 0) return; // BufferBuilder.end() throws on an empty buffer

        Matrix4f oldProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorter oldSorting = RenderSystem.getVertexSorting();
        Matrix4fStack mv = RenderSystem.getModelViewStack();
        mv.pushMatrix();
        mv.identity();
        mv.mul(lastPositionMatrix);
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(lastProjectionMatrix, VertexSorter.BY_DISTANCE);

        RenderSystem.colorMask(false, false, false, false);
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.disableBlend();
        // push Jak's surfaces slightly back so blocks placed right on them win the depth test
        RenderSystem.enablePolygonOffset();
        RenderSystem.polygonOffset(1.0f, 4.0f);
        RenderSystem.setShader(GameRenderer::getPositionProgram);
        BufferRenderer.drawWithGlobalProgram(bb.end());
        RenderSystem.polygonOffset(0f, 0f);
        RenderSystem.disablePolygonOffset();
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.enableCull();

        mv.popMatrix();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(oldProjection, oldSorting);
    }

    /** After the HUD is drawn: copy the whole Minecraft frame to shared memory for Jak. */
    public static void exportFrame() {
        if (captureBroken || !active()) return;
        try {
            if (writer == null) {
                if (writerFailed) return;
                try {
                    writer = new McFrameWriter(McFrameWriter.defaultPath());
                    JakBridgeClient.LOG.info("Sharing Minecraft frames with Jak through {}", McFrameWriter.defaultPath());
                } catch (Exception e) {
                    writerFailed = true;
                    JakBridgeClient.LOG.error("Could not create the Minecraft frame file", e);
                    return;
                }
            }
            Framebuffer fb = MinecraftClient.getInstance().getFramebuffer();
            ByteBuffer out = writer.begin(fb.textureWidth, fb.textureHeight);
            if (out == null) return;
            int oldRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fb.fbo);
            GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, 4);
            GlStateManager._pixelStore(GL11.GL_PACK_ROW_LENGTH, 0);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_PIXELS, 0);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_ROWS, 0);
            GL11.glReadPixels(0, 0, fb.textureWidth, fb.textureHeight, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, out);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, oldRead);
            writer.end();
        } catch (Throwable t) {
            captureBroken = true;
            JakBridgeClient.LOG.error("Sharing the Minecraft frame with Jak failed", t);
        }
    }
}
