package dev.ignition.jakbridge;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

/**
 * Puts Minecraft's blocks *inside* Jak's world instead of in front of it.
 *
 * Jak's picture is drawn as Minecraft's sky, so on its own every Minecraft block would cover it.
 * Right after that, Jak's level shape (the same collision triangles that became barrier blocks)
 * is drawn into the depth buffer only - invisible, but anything Minecraft draws behind a Jak wall,
 * hill or building then fails the depth test, and Jak's picture shows there instead.
 */
public final class JakDepthProxy {
    private static final int RADIUS_CHUNKS = 5;

    private static Matrix4f lastPositionMatrix;
    private static Matrix4f lastProjectionMatrix;
    private static Vec3d lastCameraPos;
    private static boolean broken = false;

    private JakDepthProxy() {
    }

    /** WorldRenderEvents.START: this frame's camera. */
    public static void rememberMatrices(WorldRenderContext ctx) {
        lastPositionMatrix = new Matrix4f(ctx.positionMatrix());
        lastProjectionMatrix = new Matrix4f(ctx.projectionMatrix());
        lastCameraPos = ctx.camera().getPos();
    }

    /** Called from the sky replacement, after Jak's picture and before Minecraft's terrain. */
    public static void draw() {
        if (broken) return;
        try {
            drawUnsafe();
        } catch (Throwable t) {
            broken = true;
            JakBridgeClient.LOG.error("Jak depth proxy failed; blocks will draw in front of Jak's walls", t);
        }
    }

    private static void drawUnsafe() {
        CollisionVoxels v = JakBridgeClient.collision().voxels();
        if (v == null || lastPositionMatrix == null || lastCameraPos == null) return;
        final double cx = lastCameraPos.x;
        final double cy = lastCameraPos.y - JakBridgeClient.collision().yOffset();
        final double cz = lastCameraPos.z;

        BufferBuilder bb = Tessellator.getInstance().begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION);
        int[] count = {0};
        v.forEachTriangleListNear(cx, cz, RADIUS_CHUNKS, tris -> {
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
}
