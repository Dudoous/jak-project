package dev.ignition.jakbridge;

import org.junit.jupiter.api.Test;

import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact-height Jak ground, surface types, Jak's light on Minecraft, old dig files. */
class GroundAndLightTest {

    private static CollisionVoxels load(String obj) throws Exception {
        Path dir = Files.createTempDirectory("collide");
        Files.writeString(dir.resolve("collide-test.obj"), obj);
        return CollisionVoxels.loadFolder(dir, null);
    }

    @Test
    void floorBlockTopIsJaksFloor() throws Exception {
        // 4x4 m grass floor at y = 10.3 (pat: material grass = 7 << 6)
        String pat = Integer.toHexString(7 << 6);
        CollisionVoxels v = load("v 0 10.3 0\nv 4 10.3 0\nv 4 10.3 4\nv 0 10.3 4\nusemtl pat_" + pat + "\nf 1 2 3\nf 1 3 4\n");
        assertTrue(v.contains(1, 10, 1), "the floor is in voxel 10");
        assertFalse(v.contains(1, 9, 1), "nothing under it");
        int top = v.floors(0, 0).top(1, 10, 1);
        assertEquals(5, top & 0xff, "top at 10 + 5/16 = 10.31, Jak's floor is 10.3");
        assertEquals(JakSurface.GRASS, top >> 8, "sounds like grass");
        assertEquals(0, v.floors(0, 0).top(1, 9, 1), "no floor in the voxel below");
        assertEquals(10.3, v.floorBelow(2.2, 1.7, 11, 3), 1e-4);
        assertTrue(Double.isNaN(v.floorBelow(2.2, 1.7, 10, 3)), "the floor is above 10");
    }

    @Test
    void slopeIsCutAtTheMiddleOfEachCell() throws Exception {
        // a 20 degree slope rising along x: y = 5 + 0.364 x
        double k = Math.tan(Math.toRadians(20));
        CollisionVoxels v = load(String.format("v 0 5 0\nv 8 %f 0\nv 8 %f 8\nv 0 5 8\nf 1 2 3\nf 1 3 4\n", 5 + 8 * k, 5 + 8 * k));
        CollisionVoxels.Floors f = v.floors(0, 0);
        for (int x = 0; x < 8; x++) {
            double want = 5 + (x + 0.5) * k;
            int vy = (int) Math.floor(want - 0.002);
            assertTrue(v.contains(x, vy, 3));
            int top = f.top(x, vy, 3) & 0xff;
            assertTrue(top > 0);
            assertEquals(want, vy + top / 16.0, 1.0 / 32 + 1e-6, "cell " + x);
        }
    }

    @Test
    void cameraOnlySurfacesAreNotSolid() throws Exception {
        CollisionVoxels v = load("v 0 0 0\nv 0 4 0\nv 0 4 4\nv 0 0 4\nusemtl pat_00000001\nf 1 2 3 4\n"
                + "usemtl pat_00000000\nv 0 2 0\nv 4 2 0\nv 4 2 4\nf 5 6 7\n");
        assertEquals(1, v.triangleCount(), "the camera-only wall was left out");
        assertEquals(2, v.skippedCount());
        assertTrue(v.contains(2, 1, 1), "the floor is still there");
    }

    @Test
    void surfaceKinds() {
        assertEquals(JakSurface.WOOD, JakSurface.soundKind(6 << 6));
        assertEquals(JakSurface.SNOW, JakSurface.soundKind(10 << 6));
        assertEquals(JakSurface.STONE, JakSurface.soundKind(0));
        assertEquals(0x4041, JakSurface.parse("pat_00004041"));
        assertEquals(9, JakSurface.KINDS, "JakGroundBlock.Surface has one per kind, in the same order");
    }

    private static float[] light(float sun, float ambient) {
        float[] d = new float[BridgeSocket.LIGHT_FLOATS];
        d[JakLight.DIR0] = -0.612f;
        d[JakLight.DIR0 + 1] = 0.5f;
        d[JakLight.DIR0 + 2] = 0.612f;
        for (int c = 0; c < 3; c++) {
            d[JakLight.COLOR0 + c] = sun;
            d[JakLight.AMBIENT + c] = ambient;
        }
        d[JakLight.FOG_START] = 20;
        d[JakLight.FOG_END] = 200;
        d[JakLight.FOG_AMOUNT] = 0.5f;
        d[JakLight.FOG_COLOR] = 0.5f;
        return d;
    }

    @Test
    void lightmapFollowsJaksLight() {
        int[] day = new int[256], night = new int[256];
        JakLight.fillLightmap(light(0.8f, 0.4f), 1f, 0f, 0f, day);
        JakLight.fillLightmap(light(0.05f, 0.1f), 1f, 0f, 0f, night);
        int fullSkyDay = day[15 * 16] & 0xff, fullSkyNight = night[15 * 16] & 0xff;
        assertTrue(fullSkyDay > 230, "sunny: Steve fully lit, was " + fullSkyDay);
        assertTrue(fullSkyNight < 60, "night: Steve dark, was " + fullSkyNight);
        assertTrue((night[15 * 16 + 15] & 0xff) > 200, "a torch still lights him at night");
        assertTrue((day[0] & 0xff) < 20, "no sky, no torch: dark under your own roof");
        assertEquals(0xFF000000, day[100] & 0xFF000000, "opaque");
    }

    @Test
    void entityLightsPointAtJaksSun() {
        float[] out = new float[6];
        assertTrue(JakLight.shaderLights(light(0.8f, 0.3f), out));
        double len = Math.sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2]);
        assertEquals(1.0, len, 1e-4, "the sun is the stronger light: full strength");
        assertTrue(out[1] > 0, "from above");
        assertEquals(0f, out[3] * out[3] + out[4] * out[4] + out[5] * out[5], 1e-6f, "no fill light here");
        assertFalse(JakLight.shaderLights(light(0f, 0.3f), out), "only ambient: Minecraft's own shading");
    }

    @Test
    void fogReachesJaksFogAmount() {
        float[] f = new float[5];
        assertTrue(JakLight.fog(light(1, 1), 10000f, f));
        assertEquals(20f, f[0], 1e-4f);
        assertEquals(20f + 180f / 0.5f, f[1], 1e-3f, "full fog where Jak's fog line would reach 100%");
        assertTrue(JakLight.fog(light(1, 1), 100f, f));
        assertEquals(100f, f[1], 1e-4f, "never beyond Minecraft's own fog");
    }

    @Test
    void oldDigFilesBecomeLegacyHoles() throws Exception {
        Path file = Files.createTempFile("dug", ".dat");
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(file))) {
            out.writeInt(1);
            out.writeLong(JakDigs.key(3, 9, 4));
            out.writeInt(0);
        }
        JakDigs.load(file);
        assertTrue(JakDigs.contains(3, 9, 4));
        assertTrue(JakDigs.isLegacy(3, 9, 4));
        JakDigs.add(3, 10, 4);
        JakDigs.saveIfDirty(file);
        JakDigs.load(file);
        assertTrue(JakDigs.isLegacy(3, 9, 4), "still known as old after saving in the new format");
        assertTrue(JakDigs.contains(3, 10, 4));
        assertFalse(JakDigs.isLegacy(3, 10, 4));
        JakDigs.clear();
    }
}
