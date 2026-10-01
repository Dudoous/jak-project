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
    }

    @Test
    void yOffsetFitsJakAboveMinecraftsBottom() {
        assertEquals(0, JakCraftConfig.chooseYOffset(-10));
        assertEquals(141, JakCraftConfig.chooseYOffset(-200.4));
    }

    @Test
    void frameReaderReadsCompleteFramesOnce() throws Exception {
        Path f = Files.createTempFile("frame", ".bin");
        ByteBuffer b = ByteBuffer.allocate(64 + 2 * 2 * 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0, FrameReader.MAGIC).putInt(4, 1).putInt(8, 2).putInt(12, 2).putInt(16, 2).putInt(20, 1);
        for (int i = 0; i < 16; i++) b.put(64 + i, (byte) i);
        Files.write(f, b.array());

        try (FrameReader r = new FrameReader(f)) {
            FrameReader.Frame frame = r.poll();
            assertNotNull(frame);
            assertEquals(2, frame.width);
            assertEquals(2, frame.height);
            assertEquals(16, frame.pixels.remaining());
            assertEquals(5, frame.pixels.get(5));
            assertTrue(r.stillValid(frame));
            assertTrue(r.isFresh());
            assertNull(r.poll(), "same frame is not returned twice");
        }

        // a frame being written (odd seq) is skipped
        b.putInt(8, 3);
        Files.write(f, b.array());
        try (FrameReader r = new FrameReader(f)) {
            assertNull(r.poll());
        }
    }
}
