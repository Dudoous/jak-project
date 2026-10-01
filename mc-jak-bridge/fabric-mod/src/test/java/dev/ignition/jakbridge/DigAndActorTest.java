package dev.ignition.jakbridge;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DigAndActorTest {

    private static Set<String> asSet(List<int[]> cells) {
        Set<String> s = new HashSet<>();
        for (int[] c : cells) s.add(c[0] + "," + c[1] + "," + c[2]);
        return s;
    }

    @Test
    void crateBecomesAOneOrTwoBlockBox() {
        // a 1.5 m crate standing on y = 10, centred on a block
        float[] d = {5.5f, 10.75f, -2.5f, 0.75f, 0f, 0f};
        Set<String> cells = asSet(JakActors.cells(BridgeSocket.ACTOR_BOX, d, 0));
        assertEquals(Set.of("5,10,-3"), cells, "covers its block, not half-touched neighbours");
        // centred on a block corner: 2 x 2
        float[] e = {6.0f, 10.75f, 0.0f, 0.75f, 0f, 0f};
        assertEquals(4, JakActors.cells(BridgeSocket.ACTOR_BOX, e, 0).size());
    }

    @Test
    void closedDoorIsAThinWall() {
        // door facing +Z at the origin, 2 m half size
        float[] d = {0.5f, 2.0f, 0.5f, 2.0f, 0f, 1f};
        List<int[]> cells = JakActors.cells(BridgeSocket.ACTOR_SLAB, d, 0);
        assertFalse(cells.isEmpty());
        for (int[] c : cells) assertEquals(0, c[2], "one block thick along the normal");
    }

    @Test
    void digKeysRoundTrip() {
        int[][] pts = {{0, 0, 0}, {-1, -5, 7}, {12345, 2000, -9876}, {-30000, -2000, 30000}};
        for (int[] p : pts) {
            long k = JakDigs.key(p[0], p[1], p[2]);
            assertEquals(p[0], JakDigs.keyX(k));
            assertEquals(p[1], JakDigs.keyY(k));
            assertEquals(p[2], JakDigs.keyZ(k));
        }
    }

    @Test
    void dugCellIsCutOutOfJaksFloor() {
        // a 10 x 10 m floor at y = 10 as two triangles
        CollisionVoxels.Triangles t = new CollisionVoxels.Triangles();
        t.add(0, 10, 0, 10, 10, 0, 10, 10, 10);
        t.add(0, 10, 0, 10, 10, 10, 0, 10, 10);
        // the floor voxel under the surface is y = 9 (see CollisionVoxels), dug at x = 4, z = 6
        CollisionVoxels.Triangles cut = DigCut.cutWith(t, DigCut.boxes(List.of(new int[] {4, 9, 6})));
        double area = 0, holeArea = 0;
        for (double[] c : DigCut.centroids(cut)) {
            assertFalse(c[0] >= 4 && c[0] < 5 && c[2] >= 6 && c[2] < 7, "no floor left in the hole");
        }
        float[] d = cut.data();
        for (int i = 0; i < cut.count() * 9; i += 9) {
            double ux = d[i + 3] - d[i], uz = d[i + 5] - d[i + 2], vx = d[i + 6] - d[i], vz = d[i + 8] - d[i + 2];
            area += Math.abs(ux * vz - uz * vx) / 2;
        }
        holeArea = 100 - area;
        assertTrue(holeArea > 0.8 && holeArea < 1.3, "about 1 m2 removed, was " + holeArea);
        assertTrue(cut.count() < 2000, "only split near the hole: " + cut.count());
    }
}
