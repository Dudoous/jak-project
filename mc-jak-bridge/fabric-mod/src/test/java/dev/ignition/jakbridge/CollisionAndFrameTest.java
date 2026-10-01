package dev.ignition.jakbridge;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollisionAndFrameTest {

    @Test
    void floorVoxelsTopOutAtJaksFloor() throws Exception {
        Path dir = Files.createTempDirectory("collide");
        // 10x10 m floor at y = 10, one triangle of each winding
        Files.writeString(dir.resolve("collide-floor.obj"),
                "v -5 10 -5\nv 5 10 -5\nv 5 10 5\nv -5 10 5\nf 1/1 2/2 3/3\nf 1/1 4/4 3/3\n");
        // wall at x = 20.2, quad (fan-triangulated)
        Files.writeString(dir.resolve("collide-wall.obj"),
                "v 20.2 0 0\nv 20.2 4 0\nv 20.2 4 3\nv 20.2 0 3\nf 1 2 3 4\n");
        CollisionVoxels v = CollisionVoxels.loadFolder(dir, null);
        assertEquals(2, v.fileCount());
        assertEquals(4, v.triangleCount());

        Set<String> cells = new HashSet<>();
        Set<Integer> ys = new HashSet<>();
        for (int cx = -1; cx <= 1; cx++) {
            for (int cz = -1; cz <= 1; cz++) {
                CollisionVoxels.Column c = v.column(cx, cz);
                if (c == null) continue;
                final int fcx = cx, fcz = cz;
                c.forEach((lx, y, lz) -> {
                    int x = fcx * 16 + lx, z = fcz * 16 + lz;
                    if (x >= -5 && x < 5 && z >= -5 && z < 5) {
                        cells.add(x + "," + z);
                        ys.add(y);
                    }
                });
            }
        }
        assertEquals(100, cells.size(), "floor covers every 1x1 m cell");
        assertEquals(Set.of(9), ys, "block at y=9 has its top at Jak's floor (10)");
        assertTrue(v.column(1, 0).count() >= 12, "wall is solid");

        int[] near = {0};
        v.forEachTriangleListNear(0, 0, 0, t -> near[0] += t.count());
        assertEquals(2, near[0], "both floor triangles are kept for the depth proxy at the origin");
        int[] far = {0};
        v.forEachTriangleListNear(500, 500, 1, t -> far[0] += t.count());
        assertEquals(0, far[0], "nothing far away");
    }

    @Test
    void frameReaderReadsCompleteFramesOnce() throws Exception {
        Path f = Files.createTempFile("frame", ".bin");
        int stride = 64 + 2 * 2 * 4;
        ByteBuffer b = ByteBuffer.allocate(64 + 3 * stride).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0, FrameReader.MAGIC).putInt(4, 2).putInt(12, 3).putInt(16, stride).putInt(28, 5);
        // newest frame: number 5 in slot 2
        int s2 = 64 + 2 * stride;
        b.putInt(8, (5 << 2) | 2);
        b.putInt(s2, 2).putInt(s2 + 4, 2).putInt(s2 + 8, 2).putInt(s2 + 12, FrameReader.FORMAT_BGRA8).putInt(s2 + 16, 5);
        // camera pose the frame was rendered from
        b.putInt(s2 + 20, 1).putInt(s2 + 24, 77).putFloat(s2 + 28, 1.5f).putFloat(s2 + 32, -20f).putFloat(s2 + 36, 300.25f)
                .putFloat(s2 + 40, -90f).putFloat(s2 + 44, 30f).putFloat(s2 + 48, 70f);
        for (int i = 0; i < 16; i++) b.put(s2 + 64 + i, (byte) i);
        Files.write(f, b.array());

        try (FrameReader r = new FrameReader(f)) {
            FrameReader.Frame frame = r.poll();
            assertNotNull(frame);
            assertEquals(2, frame.width);
            assertEquals(2, frame.height);
            assertEquals(5, frame.frame);
            assertEquals(FrameReader.FORMAT_BGRA8, frame.format);
            assertEquals(16, frame.pixels.remaining());
            assertEquals(5, frame.pixels.get(5));
            assertTrue(frame.poseValid);
            assertEquals(77, frame.poseSeq);
            assertEquals(-20f, frame.y);
            assertEquals(300.25f, frame.z);
            assertEquals(-90f, frame.yaw);
            assertEquals(30f, frame.pitch);
            assertTrue(r.stillValid(frame));
            assertTrue(r.isFresh());
            assertEquals(5, r.framesWrittenByJak());
            assertNull(r.poll(), "same frame is not returned twice");
        }

        // a slot being written (odd seq) is skipped
        b.putInt(s2, 3);
        Files.write(f, b.array());
        try (FrameReader r = new FrameReader(f)) {
            assertNull(r.poll());
        }
    }
}
