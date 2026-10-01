package dev.ignition.jakbridge;

import java.util.ArrayList;
import java.util.List;

/**
 * Which 1 m cells a solid Jak object (ACTORS packet: crate box or closed door slab) fills.
 * No Minecraft dependencies, Jak meters.
 */
public final class JakActors {
    private JakActors() {
    }

    /** Cells along one axis that the span [lo, hi] covers more than half of (at least one). */
    private static int[] axisCells(double lo, double hi) {
        int k0 = (int) Math.floor(lo);
        int k1 = (int) Math.floor(hi);
        List<Integer> ks = new ArrayList<>();
        int best = k0;
        double bestOverlap = -1;
        for (int k = k0; k <= k1; k++) {
            double overlap = Math.min(k + 1, hi) - Math.max(k, lo);
            if (overlap > 0.5) ks.add(k);
            if (overlap > bestOverlap) {
                bestOverlap = overlap;
                best = k;
            }
        }
        if (ks.isEmpty()) ks.add(best);
        int[] out = new int[ks.size()];
        for (int i = 0; i < out.length; i++) out[i] = ks.get(i);
        return out;
    }

    /**
     * Cells (x, y, z triples) for one object. data[off..off+5] = x, y, z (centre), half size,
     * normal x, normal z.
     */
    public static List<int[]> cells(int kind, float[] data, int off) {
        double cx = data[off], cy = data[off + 1], cz = data[off + 2], half = data[off + 3];
        List<int[]> out = new ArrayList<>();
        if (!(half > 0.05) || half > 16) return out; // nonsense size
        if (kind == BridgeSocket.ACTOR_BOX) {
            for (int x : axisCells(cx - half, cx + half))
                for (int y : axisCells(cy - half, cy + half))
                    for (int z : axisCells(cz - half, cz + half))
                        out.add(new int[] {x, y, z});
        } else if (kind == BridgeSocket.ACTOR_SLAB) {
            double nx = data[off + 4], nz = data[off + 5];
            double len = Math.sqrt(nx * nx + nz * nz);
            if (len < 1e-3) return out;
            nx /= len;
            nz /= len;
            int r = (int) Math.ceil(half) + 1;
            int bx = (int) Math.floor(cx), by = (int) Math.floor(cy), bz = (int) Math.floor(cz);
            for (int x = bx - r; x <= bx + r; x++) {
                for (int z = bz - r; z <= bz + r; z++) {
                    double dx = x + 0.5 - cx, dz = z + 0.5 - cz;
                    double along = dx * nx + dz * nz;
                    double lateral = Math.abs(dx * -nz + dz * nx);
                    if (Math.abs(along) > 0.6 || lateral > half) continue;
                    for (int y = by - r; y <= by + r; y++) {
                        if (Math.abs(y + 0.5 - cy) <= half) out.add(new int[] {x, y, z});
                    }
                }
            }
        }
        return out;
    }
}
