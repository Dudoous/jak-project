package dev.ignition.jakbridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Jak's level shape with the cells the player dug out (JakDigs) cut away, for the depth proxy.
 * Triangles near a dug cell are split into small pieces and the pieces inside it dropped.
 * Results are cached per triangle list until something new is dug. No Minecraft dependencies.
 */
public final class DigCut {
    /** Pieces smaller than this (m) are kept or dropped whole. */
    private static final double MIN_EDGE = 0.2;
    /** A dug cell clears the cell and 1 m above it (Jak's floor surface is in there). */
    private static final double CUT_UP = 2.0;

    private static final class Entry {
        int version;
        float[] bounds; // min x y z, max x y z
        CollisionVoxels.Triangles result;
    }

    private static final Map<CollisionVoxels.Triangles, Entry> cache = new WeakHashMap<>();

    private DigCut() {
    }

    public static CollisionVoxels.Triangles cut(CollisionVoxels.Triangles t) {
        if (JakDigs.isEmpty()) return t;
        int ver = JakDigs.version();
        Entry e = cache.get(t);
        if (e != null && e.version == ver) return e.result;
        if (e == null) {
            e = new Entry();
            e.bounds = bounds(t);
            cache.put(t, e);
        }
        float[] b = e.bounds;
        List<int[]> dug = JakDigs.within((int) Math.floor(b[0]) - 1, (int) Math.floor(b[1] - CUT_UP) - 1,
                (int) Math.floor(b[2]) - 1, (int) Math.floor(b[3]) + 1, (int) Math.floor(b[4]) + 1,
                (int) Math.floor(b[5]) + 1);
        e.version = ver;
        e.result = dug.isEmpty() ? t : cutWith(t, boxes(dug));
        return e.result;
    }

    static double[][] boxes(List<int[]> dug) {
        double[][] out = new double[dug.size()][];
        for (int i = 0; i < out.length; i++) {
            int[] c = dug.get(i);
            out[i] = new double[] {c[0], c[1], c[2], c[0] + 1, c[1] + CUT_UP, c[2] + 1};
        }
        return out;
    }

    static float[] bounds(CollisionVoxels.Triangles t) {
        float[] d = t.data();
        float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int i = 0; i < t.count() * 9; i += 3) {
            for (int k = 0; k < 3; k++) {
                b[k] = Math.min(b[k], d[i + k]);
                b[k + 3] = Math.max(b[k + 3], d[i + k]);
            }
        }
        return b;
    }

    static CollisionVoxels.Triangles cutWith(CollisionVoxels.Triangles t, double[][] boxes) {
        CollisionVoxels.Triangles out = new CollisionVoxels.Triangles();
        float[] d = t.data();
        double[] stack = new double[9 * 64];
        for (int i = 0; i < t.count() * 9; i += 9) {
            int sp = 0;
            for (int k = 0; k < 9; k++) stack[k] = d[i + k];
            sp = 1;
            while (sp > 0) {
                sp--;
                int o = sp * 9;
                double ax = stack[o], ay = stack[o + 1], az = stack[o + 2];
                double bx = stack[o + 3], by = stack[o + 4], bz = stack[o + 5];
                double cx = stack[o + 6], cy = stack[o + 7], cz = stack[o + 8];
                double x0 = Math.min(ax, Math.min(bx, cx)), x1 = Math.max(ax, Math.max(bx, cx));
                double y0 = Math.min(ay, Math.min(by, cy)), y1 = Math.max(ay, Math.max(by, cy));
                double z0 = Math.min(az, Math.min(bz, cz)), z1 = Math.max(az, Math.max(bz, cz));
                boolean touches = false;
                for (double[] bx6 : boxes) {
                    if (x1 > bx6[0] && x0 < bx6[3] && y1 > bx6[1] && y0 < bx6[4] && z1 > bx6[2] && z0 < bx6[5]) {
                        touches = true;
                        break;
                    }
                }
                if (!touches) {
                    out.add(ax, ay, az, bx, by, bz, cx, cy, cz);
                    continue;
                }
                double e = Math.max(Math.max(dist(ax, ay, az, bx, by, bz), dist(bx, by, bz, cx, cy, cz)),
                        dist(cx, cy, cz, ax, ay, az));
                if (e < MIN_EDGE || sp + 4 > 64) {
                    double mx = (ax + bx + cx) / 3, my = (ay + by + cy) / 3, mz = (az + bz + cz) / 3;
                    boolean inside = false;
                    for (double[] bx6 : boxes) {
                        if (mx >= bx6[0] && mx < bx6[3] && my >= bx6[1] && my < bx6[4] && mz >= bx6[2] && mz < bx6[5]) {
                            inside = true;
                            break;
                        }
                    }
                    if (!inside) out.add(ax, ay, az, bx, by, bz, cx, cy, cz);
                    continue;
                }
                // split into 4 at the edge midpoints
                double abx = (ax + bx) / 2, aby = (ay + by) / 2, abz = (az + bz) / 2;
                double bcx = (bx + cx) / 2, bcy = (by + cy) / 2, bcz = (bz + cz) / 2;
                double cax = (cx + ax) / 2, cay = (cy + ay) / 2, caz = (cz + az) / 2;
                sp = push(stack, sp, ax, ay, az, abx, aby, abz, cax, cay, caz);
                sp = push(stack, sp, abx, aby, abz, bx, by, bz, bcx, bcy, bcz);
                sp = push(stack, sp, cax, cay, caz, bcx, bcy, bcz, cx, cy, cz);
                sp = push(stack, sp, abx, aby, abz, bcx, bcy, bcz, cax, cay, caz);
            }
        }
        return out;
    }

    private static int push(double[] s, int sp, double ax, double ay, double az, double bx, double by, double bz,
                            double cx, double cy, double cz) {
        int o = sp * 9;
        s[o] = ax; s[o + 1] = ay; s[o + 2] = az;
        s[o + 3] = bx; s[o + 4] = by; s[o + 5] = bz;
        s[o + 6] = cx; s[o + 7] = cy; s[o + 8] = cz;
        return sp + 1;
    }

    private static double dist(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = ax - bx, dy = ay - by, dz = az - bz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** For tests. */
    static List<double[]> centroids(CollisionVoxels.Triangles t) {
        List<double[]> out = new ArrayList<>();
        float[] d = t.data();
        for (int i = 0; i < t.count() * 9; i += 9) {
            out.add(new double[] {(d[i] + d[i + 3] + d[i + 6]) / 3, (d[i + 1] + d[i + 4] + d[i + 7]) / 3,
                    (d[i + 2] + d[i + 5] + d[i + 8]) / 3});
        }
        return out;
    }
}
