package dev.ignition.jakbridge;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.lang.reflect.Method;

/**
 * The Minecraft rendering side of "Steve is really in Jak's world":
 *  - shadows of Steve and mobs on Jak's ground (Jak's ground is invisible blocks, which Minecraft
 *    never draws shadows on): soft blobs that follow Jak's real floor, like Jak's own shadow;
 *  - Minecraft's entity lights pointed at Jak's sun (DiffuseLightingMixin);
 *  - Minecraft's fog set to Jak's (FogMixin).
 * Lightmap colouring is in LightmapMixin; the math is in JakLight.
 */
public final class JakRender {
    private static final Identifier SHADOW_TEXTURE = Identifier.ofVanilla("textures/misc/shadow.png");
    private static final int MAX_SHADOWS = 12;
    private static final double SHADOW_RANGE = 32.0;
    /** Shadows fade out over this height above Jak's floor (m). */
    private static final double SHADOW_FADE = 6.0;
    private static final int GRID = 4;
    private static final int FULL_BRIGHT = 15728880;

    private static boolean shadowsBroken = false;
    private static Method setShaderLights;
    private static boolean lightsBroken = false;
    private static volatile boolean diffuseHookSeen = false;

    private JakRender() {
    }

    // ---- shadows ----

    /** WorldRenderEvents.AFTER_ENTITIES. */
    public static void renderShadows(WorldRenderContext ctx) {
        if (shadowsBroken) return;
        try {
            renderShadowsUnsafe(ctx);
        } catch (Throwable t) {
            shadowsBroken = true;
            JakBridgeClient.LOG.error("Shadows on Jak's ground failed; turned off", t);
        }
    }

    private static void renderShadowsUnsafe(WorldRenderContext ctx) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!JakBridgeClient.inJakWorld() || JakBridgeClient.cutscene() || client.world == null) return;
        if (!JakBridgeClient.config().shadows() || !client.options.getEntityShadows().getValue()) return;
        CollisionVoxels v = JakBridgeClient.collision().voxels();
        VertexConsumerProvider consumers = ctx.consumers();
        if (v == null || consumers == null) return;
        VertexConsumer vc = consumers.getBuffer(RenderLayer.getEntityShadow(SHADOW_TEXTURE));
        MatrixStack ms = ctx.matrixStack() != null ? ctx.matrixStack() : new MatrixStack();
        Matrix4f m = ms.peek().getPositionMatrix();
        Vec3d cam = ctx.camera().getPos();
        float tickDelta = ctx.tickCounter().getTickDelta(true);
        int yOffset = JakBridgeClient.collision().yOffset();
        // a stronger sun makes darker shadows, none at all in pitch darkness
        float darkness = 0.55f;
        BridgeSocket.Light light = JakLight.active();
        if (light != null) {
            float[] base = new float[3];
            JakLight.baseColor(light.data, JakBridgeClient.config().jakLightStrength(), base);
            darkness = 0.25f + 0.4f * Math.min(1f, (base[0] + base[1] + base[2]) / 2.4f);
        }
        int n = 0;
        for (Entity e : client.world.getEntities()) {
            if (!(e instanceof LivingEntity) || e.isInvisible() || e.isSpectator()) continue;
            if (e.squaredDistanceTo(cam) > SHADOW_RANGE * SHADOW_RANGE) continue;
            if (n++ >= MAX_SHADOWS) break;
            Vec3d p = e.getLerpedPos(tickDelta);
            // standing on your own blocks: Minecraft draws that shadow itself
            BlockState under = client.world.getBlockState(BlockPos.ofFloored(p.x, p.y - 0.2, p.z));
            if (!under.isAir() && !CollisionPlacer.isJakBlock(under)) continue;
            drawShadow(vc, m, v, p.x, p.y - yOffset, p.z, Math.max(0.35, e.getWidth() * 0.65), darkness, cam, yOffset);
        }
    }

    private static void drawShadow(VertexConsumer vc, Matrix4f m, CollisionVoxels v, double x, double feetY,
                                   double z, double r, float darkness, Vec3d cam, int yOffset) {
        CollisionVoxels.FloorPatch patch = v.floorPatch(x - r, z - r, x + r, z + r);
        if (patch.isEmpty()) return;
        double ground = patch.floorBelow(x, z, feetY + 0.3, SHADOW_FADE);
        if (Double.isNaN(ground)) return;
        double above = Math.max(0, feetY - ground);
        float alpha = (float) (darkness * (1.0 - above / SHADOW_FADE));
        if (alpha <= 0.02f) return;
        int color = ((int) (alpha * 255f) << 24) | 0xFFFFFF;
        double[] h = new double[(GRID + 1) * (GRID + 1)];
        for (int i = 0; i <= GRID; i++) {
            for (int j = 0; j <= GRID; j++) {
                double px = x - r + 2 * r * i / GRID, pz = z - r + 2 * r * j / GRID;
                double g = patch.floorBelow(px, pz, feetY + 0.3, SHADOW_FADE);
                h[i * (GRID + 1) + j] = Double.isNaN(g) || Math.abs(g - ground) > 0.75 ? Double.NaN : g;
            }
        }
        Vector3f t = new Vector3f();
        for (int i = 0; i < GRID; i++) {
            for (int j = 0; j < GRID; j++) {
                int a = i * (GRID + 1) + j, b = (i + 1) * (GRID + 1) + j;
                if (Double.isNaN(h[a]) || Double.isNaN(h[a + 1]) || Double.isNaN(h[b]) || Double.isNaN(h[b + 1])) continue;
                corner(vc, m, t, x, z, r, i, j, h[a], color, cam, yOffset);
                corner(vc, m, t, x, z, r, i, j + 1, h[a + 1], color, cam, yOffset);
                corner(vc, m, t, x, z, r, i + 1, j + 1, h[b + 1], color, cam, yOffset);
                corner(vc, m, t, x, z, r, i + 1, j, h[b], color, cam, yOffset);
            }
        }
    }

    private static void corner(VertexConsumer vc, Matrix4f m, Vector3f t, double x, double z, double r, int i, int j,
                               double groundY, int color, Vec3d cam, int yOffset) {
        float u = (float) i / GRID, w = (float) j / GRID;
        double px = x - r + 2 * r * u, pz = z - r + 2 * r * w;
        m.transformPosition((float) (px - cam.x), (float) (groundY + yOffset + 0.02 - cam.y), (float) (pz - cam.z), t);
        vc.vertex(t.x(), t.y(), t.z(), color, u, w, OverlayTexture.DEFAULT_UV, FULL_BRIGHT, 0f, 1f, 0f);
    }

    // ---- entity lights ----

    /** DiffuseLightingMixin: Minecraft just set its level lights; point them at Jak's sun. */
    public static void applyShaderLights() {
        diffuseHookSeen = true;
        if (lightsBroken) return;
        BridgeSocket.Light light = JakLight.active();
        if (light == null) return;
        float[] dirs = new float[6];
        if (!JakLight.shaderLights(light.data, dirs)) return;
        try {
            if (setShaderLights == null) {
                // RenderSystem keeps its own names in every Minecraft version, but this one isn't
                // part of a stable API: look it up instead of linking to it
                setShaderLights = RenderSystem.class.getMethod("setShaderLights", Vector3f.class, Vector3f.class);
            }
            setShaderLights.invoke(null, new Vector3f(dirs[0], dirs[1], dirs[2]), new Vector3f(dirs[3], dirs[4], dirs[5]));
        } catch (ReflectiveOperationException | RuntimeException e) {
            lightsBroken = true;
            JakBridgeClient.LOG.warn("Can't point Minecraft's entity lights at Jak's sun: {}", e.toString());
        }
    }

    public static boolean diffuseHookSeen() {
        return diffuseHookSeen;
    }

    // ---- fog ----

    /** FogMixin, after Minecraft set up its fog. terrain = the fog for the world (not the sky). */
    public static void applyFog(Camera camera, boolean terrain, float viewDistance) {
        if (!terrain) return;
        BridgeSocket.Light light = JakLight.active();
        if (light == null || JakBridgeClient.cutscene()) return;
        // under water / in lava / in powder snow Minecraft's own fog is right
        if (camera != null && !"NONE".equals(camera.getSubmersionType().name())) return;
        float[] f = new float[5];
        if (!JakLight.fog(light.data, viewDistance, f)) return;
        RenderSystem.setShaderFogStart(f[0]);
        RenderSystem.setShaderFogEnd(f[1]);
        RenderSystem.setShaderFogColor(f[2], f[3], f[4]);
    }
}
