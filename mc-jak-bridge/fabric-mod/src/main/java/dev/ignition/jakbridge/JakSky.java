package dev.ignition.jakbridge;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import com.mojang.blaze3d.systems.VertexSorter;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

/**
 * Draws the frame Jak rendered (from Steve's eyes) as Minecraft's sky, so every
 * Minecraft thing (blocks, hand, mobs, HUD) is drawn on top of Jak's world.
 *
 * Each frame, {@link #latch()} (called when Minecraft sets up its camera) takes Jak's newest
 * picture together with the camera pose it was rendered from; Minecraft can then draw its own
 * world from that same pose (see CameraMixin), so the two pictures line up exactly.
 * {@link #drawInsteadOfSky()} (WorldRendererMixin) draws the latched picture.
 */
public final class JakSky {
    /** Where a Jak picture was taken from (Jak meters, Minecraft degrees). */
    public static final class Pose {
        public final int seq;
        public final double x, y, z;
        public final float yaw, pitch;

        Pose(int seq, double x, double y, double z, float yaw, float pitch) {
            this.seq = seq;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

    private static Pose framePose;
    private static boolean latchedThisFrame = false;
    private static FrameReader reader;
    private static int texture = -1;
    private static int texWidth = 0;
    private static int texHeight = 0;
    private static int frameWidth = 0;
    private static int frameHeight = 0;
    private static boolean haveFrame = false;
    private static boolean broken = false;

    private JakSky() {
    }

    public static void init() {
        reader = new FrameReader(FrameReader.defaultPath());
        JakBridgeClient.LOG.info("Jak frames expected at {}", reader.path());
    }

    /** True while Jak keeps sending frames. */
    public static boolean active() {
        return !broken && reader != null && JakBridgeClient.streaming()
                && reader.isFresh() && haveFrame;
    }

    /** The pose of the picture in the texture, or null (none, stale, or not from Minecraft's camera). */
    public static Pose framePose() {
        return active() ? framePose : null;
    }

    /** Take Jak's newest picture (if a new one arrived) for this rendered frame. */
    public static void latch() {
        latchedThisFrame = true;
        if (broken || reader == null || !JakBridgeClient.streaming()) {
            return;
        }
        try {
            update();
        } catch (Throwable t) {
            broken = true;
            JakBridgeClient.LOG.error("Reading Jak's picture failed, falling back to the normal sky", t);
        }
    }

    /** Pull a new frame from Jak, if any, into the texture. */
    private static void update() {
        FrameReader.Frame f = reader.poll();
        if (f == null) return;
        framePose = f.poseValid ? new Pose(f.poseSeq, f.x, f.y, f.z, f.yaw, f.pitch) : null;
        if (texture < 0) {
            texture = GlStateManager._genTexture();
            RenderSystem.bindTexture(texture);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        }
        RenderSystem.bindTexture(texture);
        // Minecraft leaves custom unpack state behind after its own uploads
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
        if (f.width != texWidth || f.height != texHeight) {
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, f.width, f.height, 0, GL11.GL_RGBA,
                    GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
            texWidth = f.width;
            texHeight = f.height;
        }
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, f.width, f.height, GL11.GL_RGBA,
                GL11.GL_UNSIGNED_BYTE, f.pixels);
        frameWidth = f.width;
        frameHeight = f.height;
        haveFrame = true;
        // a torn frame (Jak overwrote it mid-upload) just gets replaced next frame
        reader.stillValid(f);
    }

    /**
     * Replace the sky. Returns true if Jak's world was drawn (the vanilla sky must then be skipped).
     */
    public static boolean drawInsteadOfSky() {
        if (broken || reader == null || !JakBridgeClient.streaming()) {
            return false;
        }
        try {
            // normally already done when the camera was set up; this is the fallback
            if (!latchedThisFrame) update();
            latchedThisFrame = false;
            if (!reader.isFresh() || !haveFrame) {
                return false;
            }
            drawFullscreen(false);
            return true;
        } catch (Throwable t) {
            broken = true;
            JakBridgeClient.LOG.error("Drawing Jak's world failed, falling back to the normal sky", t);
            return false;
        }
    }

    /** Cutscenes: Jak's picture over everything (called after the HUD is drawn). */
    public static void drawCutscene() {
        if (!active() || !JakBridgeClient.cutscene()) return;
        try {
            drawFullscreen(true);
        } catch (Throwable t) {
            broken = true;
            JakBridgeClient.LOG.error("Drawing Jak's cutscene failed", t);
        }
    }

    private static void drawFullscreen(boolean fit) {
        Framebuffer fb = MinecraftClient.getInstance().getFramebuffer();
        float mcAspect = (float) fb.textureWidth / Math.max(1, fb.textureHeight);
        float jakAspect = (float) frameWidth / Math.max(1, frameHeight);
        // Both cameras share the vertical FOV, so match heights and centre horizontally.
        float hx = jakAspect / mcAspect;
        float hy = 1f;
        if (fit && hx > 1f) {
            // cutscene: the whole picture must fit on screen
            hy = 1f / hx;
            hx = 1f;
        }

        Matrix4f oldProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorter oldSorting = RenderSystem.getVertexSorting();
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.identity();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(new Matrix4f(), VertexSorter.BY_Z);

        // black bars instead of Minecraft's sky colour where Jak's picture doesn't reach
        RenderSystem.clearColor(0f, 0f, 0f, 1f);
        RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC);

        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableBlend();
        RenderSystem.disableCull();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.setShader(GameRenderer::getPositionTexProgram);
        RenderSystem.setShaderTexture(0, texture);

        BufferBuilder bb = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE);
        bb.vertex(-hx, -hy, 0f).texture(0f, 0f);
        bb.vertex(hx, -hy, 0f).texture(1f, 0f);
        bb.vertex(hx, hy, 0f).texture(1f, 1f);
        bb.vertex(-hx, hy, 0f).texture(0f, 1f);
        BufferRenderer.drawWithGlobalProgram(bb.end());

        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        modelView.popMatrix();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(oldProjection, oldSorting);
    }
}
