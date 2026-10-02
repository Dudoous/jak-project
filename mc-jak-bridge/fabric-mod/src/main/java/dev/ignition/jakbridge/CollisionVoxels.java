package dev.ignition.jakbridge;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Jak's level collision (the collide-*.obj files OpenGOAL's extractor writes with rip_collision)
 * turned into 1 m voxels, so the Minecraft player can walk on Jak's ground. No Minecraft
 * dependencies.
 *
 * Floors are exact: the voxel a floor passes through becomes a partial block whose top is Jak's
 * floor (to 1/16 m, see Floors), so Steve stands on Jak's real ground and walks up slopes in small
 * steps instead of 1 m stairs. Walls fill the voxels they pass through. Flat surfaces are all
 * treated alike (the export's winding isn't trusted), so a ceiling is solid from the voxel boundary
 * below it up to the ceiling. Surfaces Jak himself walks through (camera-only) are skipped.
 *
 * Coordinates are Jak meters (1 block = 1 m, same axes as Minecraft). The Y offset that moves
 * Jak's world into Minecraft's build height is applied by the caller.
 */
public final class CollisionVoxels {
    /** Steeper than this (normal y) is a wall. */
    private static final double FLOOR_NORMAL_Y = 0.7;
    /** A floor exactly on a voxel boundary belongs to the voxel below (its top is the floor). */
    private static final double FLOOR_EPS = 0.002;
    /** Sample spacing along triangles, in voxels. Below 0.5 so no crossed voxel is skipped. */
    private static final double SAMPLE_STEP = 0.35;

    /** A 16x16 column of 16x16x16 sections, each a 4096-bit set. */
    public static final class Column {
        final TreeMap<Integer, long[]> sections = new TreeMap<>();
        int count = 0;

        void set(int lx, int y, int lz) {
            int sy = Math.floorDiv(y, 16);
            long[] bits = sections.computeIfAbsent(sy, k -> new long[64]);
            int idx = lx + (lz << 4) + ((y - (sy << 4)) << 8);
            long mask = 1L << (idx & 63);
            if ((bits[idx >> 6] & mask) == 0) {
                bits[idx >> 6] |= mask;
                count++;
            }
        }

        public int count() {
            return count;
        }

        public boolean has(int lx, int y, int lz) {
            long[] bits = sections.get(Math.floorDiv(y, 16));
            if (bits == null) return false;
            int idx = lx + (lz << 4) + ((y - (Math.floorDiv(y, 16) << 4)) << 8);
            return (bits[idx >> 6] & (1L << (idx & 63))) != 0;
        }

        /** Calls out with (localX, y, localZ) for every voxel in this column, bottom to top. */
        public void forEach(VoxelConsumer out) {
            for (Map.Entry<Integer, long[]> e : sections.entrySet()) {
                int baseY = e.getKey() << 4;
                long[] bits = e.getValue();
                for (int w = 0; w < 64; w++) {
                    long word = bits[w];
                    while (word != 0) {
                        int b = Long.numberOfTrailingZeros(word);
                        word &= word - 1;
                        int idx = (w << 6) | b;
                        out.accept(idx & 15, baseY + (idx >> 8), (idx >> 4) & 15);
                    }
                }
            }
        }
    }

    public interface VoxelConsumer {
        void accept(int localX, int y, int localZ);
    }

    /** Growable list of triangles, 9 floats each (x y z of 3 corners, Jak meters), and their pats. */
    public static final class Triangles {
        float[] data = new float[9 * 16];
        int[] pats = new int[16];
        int floats = 0;

        void add(double ax, double ay, double az, double bx, double by, double bz, double cx, double cy, double cz) {
            add(ax, ay, az, bx, by, bz, cx, cy, cz, 0);
        }

        void add(double ax, double ay, double az, double bx, double by, double bz, double cx, double cy, double cz,
                 int pat) {
            if (floats + 9 > data.length) data = java.util.Arrays.copyOf(data, data.length * 2);
            int n = floats / 9;
            if (n >= pats.length) pats = java.util.Arrays.copyOf(pats, pats.length * 2);
            pats[n] = pat;
            data[floats++] = (float) ax; data[floats++] = (float) ay; data[floats++] = (float) az;
            data[floats++] = (float) bx; data[floats++] = (float) by; data[floats++] = (float) bz;
            data[floats++] = (float) cx; data[floats++] = (float) cy; data[floats++] = (float) cz;
        }

        public int count() {
            return floats / 9;
        }

        public float[] data() {
            return data;
        }

        public int pat(int i) {
            return pats[i];
        }
    }

    public interface TrianglesConsumer {
        void accept(Triangles tris);
    }

    /** Triangles bigger than this many chunk columns go in one shared list. */
    private static final int BIG_TRIANGLE_COLUMNS = 64;

    private final Map<Long, Column> columns = new HashMap<>();
    private final Map<Long, Triangles> columnTriangles = new HashMap<>();
    private final Triangles bigTriangles = new Triangles();
    private long triangles = 0;
    private long skipped = 0;
    private int files = 0;
    private double minY = Double.POSITIVE_INFINITY;
    private double maxY = Double.NEGATIVE_INFINITY;

    public static long columnKey(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xffffffffL);
    }

    /**
     * Every triangle list touching the chunk columns within radiusChunks of (x, z), plus the big
     * triangles. A triangle that spans several columns is reported once per column (harmless for
     * depth-only drawing).
     */
    public void forEachTriangleListNear(double x, double z, int radiusChunks, TrianglesConsumer out) {
        int pcx = (int) Math.floor(x) >> 4;
        int pcz = (int) Math.floor(z) >> 4;
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                Triangles t = columnTriangles.get(columnKey(pcx + dx, pcz + dz));
                if (t != null) out.accept(t);
            }
        }
        if (bigTriangles.count() > 0) out.accept(bigTriangles);
    }

    /** True if Jak's level collision has a voxel at (x, y, z) (Jak meters). */
    public boolean contains(int x, int y, int z) {
        Column c = columns.get(columnKey(x >> 4, z >> 4));
        return c != null && c.has(x & 15, y, z & 15);
    }

    public Column column(int cx, int cz) {
        return columns.get(columnKey(cx, cz));
    }

    public int columnCount() {
        return columns.size();
    }

    public long triangleCount() {
        return triangles;
    }

    /** Camera-only triangles that were left out (Jak walks through them). */
    public long skippedCount() {
        return skipped;
    }

    public int fileCount() {
        return files;
    }

    public double minY() {
        return minY;
    }

    public double maxY() {
        return maxY;
    }

    public long voxelCount() {
        long n = 0;
        for (Column c : columns.values()) n += c.count;
        return n;
    }

    private void mark(double x, double y, double z) {
        int vx = (int) Math.floor(x);
        int vy = (int) Math.floor(y);
        int vz = (int) Math.floor(z);
        int cx = vx >> 4;
        int cz = vz >> 4;
        columns.computeIfAbsent(columnKey(cx, cz), k -> new Column()).set(vx & 15, vy, vz & 15);
    }

    /** (b - a) x (c - a) y component and length, for floor / wall / ceiling decisions. */
    private static double normalY(float[] d, int o, double[] lenOut) {
        double ux = d[o + 3] - d[o], uy = d[o + 4] - d[o + 1], uz = d[o + 5] - d[o + 2];
        double vx = d[o + 6] - d[o], vy = d[o + 7] - d[o + 1], vz = d[o + 8] - d[o + 2];
        double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        lenOut[0] = Math.sqrt(nx * nx + ny * ny + nz * nz);
        return ny;
    }

    /** True if triangle i of t is flat (a floor, or a ceiling: either way it's a top for Steve). */
    static boolean isFloor(Triangles t, int i, double[] tmp) {
        double ny = normalY(t.data, i * 9, tmp);
        return tmp[0] > 1e-9 && Math.abs(ny) / tmp[0] > FLOOR_NORMAL_Y;
    }

    /** Add one triangle (Jak meters) with its pat, and mark the voxels it passes through. */
    public void addTriangle(double ax, double ay, double az, double bx, double by, double bz,
                            double cx, double cy, double cz, int pat) {
        if (JakSurface.noEntity(pat)) {
            skipped++; // only stops Jak's camera: Jak walks through it, so does Steve
            return;
        }
        triangles++;
        minY = Math.min(minY, Math.min(ay, Math.min(by, cy)));
        maxY = Math.max(maxY, Math.max(ay, Math.max(by, cy)));

        double ux = bx - ax, uy = by - ay, uz = bz - az;
        double vx = cx - ax, vy = cy - ay, vz = cz - az;
        double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        double nlen = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (nlen < 1e-9) {
            return; // degenerate
        }
        int cx0 = (int) Math.floor(Math.min(ax, Math.min(bx, cx))) >> 4;
        int cx1 = (int) Math.floor(Math.max(ax, Math.max(bx, cx))) >> 4;
        int cz0 = (int) Math.floor(Math.min(az, Math.min(bz, cz))) >> 4;
        int cz1 = (int) Math.floor(Math.max(az, Math.max(bz, cz))) >> 4;
        if ((long) (cx1 - cx0 + 1) * (cz1 - cz0 + 1) > BIG_TRIANGLE_COLUMNS) {
            bigTriangles.add(ax, ay, az, bx, by, bz, cx, cy, cz, pat);
        } else {
            for (int tx = cx0; tx <= cx1; tx++) {
                for (int tz = cz0; tz <= cz1; tz++) {
                    columnTriangles.computeIfAbsent(columnKey(tx, tz), k -> new Triangles())
                            .add(ax, ay, az, bx, by, bz, cx, cy, cz, pat);
                }
            }
        }
        Triangles one = new Triangles();
        one.add(ax, ay, az, bx, by, bz, cx, cy, cz, pat);
        voxelize(one);
    }

    /** Same, plain ground. */
    public void addTriangle(double ax, double ay, double az, double bx, double by, double bz,
                            double cx, double cy, double cz) {
        addTriangle(ax, ay, az, bx, by, bz, cx, cy, cz, 0);
    }

    private void voxelize(Triangles t) {
        float[] d = t.data;
        double[] tmp = new double[1];
        for (int i = 0; i < t.count(); i++) {
            int o = i * 9;
            double ny = normalY(d, o, tmp);
            double nlen = tmp[0];
            if (nlen < 1e-9) continue;
            // a floor fills the voxel it passes through (that block's top is cut to the floor, see
            // Floors); walls fill what they cross
            double yShift = Math.abs(ny) / nlen > FLOOR_NORMAL_Y ? -FLOOR_EPS : 0.0;
            double ax = d[o], ay = d[o + 1], az = d[o + 2];
            double ux = d[o + 3] - ax, uy = d[o + 4] - ay, uz = d[o + 5] - az;
            double vx = d[o + 6] - ax, vy = d[o + 7] - ay, vz = d[o + 8] - az;
            double len = Math.max(Math.sqrt(ux * ux + uy * uy + uz * uz),
                    Math.max(Math.sqrt(vx * vx + vy * vy + vz * vz),
                            Math.sqrt((vx - ux) * (vx - ux) + (vy - uy) * (vy - uy) + (vz - uz) * (vz - uz))));
            int n = Math.max(1, (int) Math.ceil(len / SAMPLE_STEP));
            for (int a = 0; a <= n; a++) {
                double s = (double) a / n;
                for (int b = 0; b <= n - a; b++) {
                    double tt = (double) b / n;
                    mark(ax + ux * s + vx * tt, ay + uy * s + vy * tt + yShift, az + uz * s + vz * tt);
                }
            }
        }
    }

    // ---- exact floor heights ----

    /**
     * Where Jak's floors are inside one chunk column's voxels: for each 1x1 m cell, the floor
     * triangles over it. top() says how high Jak's floor is inside a given voxel.
     */
    public static final class Floors {
        // per cell (lx + lz * 16): 4 floats per floor piece: height at the cell centre (NaN if
        // the centre isn't on it), lowest and highest point over the cell, pat (float bits)
        final float[][] pieces = new float[256][];
        final int[] counts = new int[256];

        void add(int cell, float centre, float lo, float hi, int pat) {
            float[] p = pieces[cell];
            int n = counts[cell];
            if (p == null) {
                p = new float[16];
                pieces[cell] = p;
            } else if (n * 4 + 4 > p.length) {
                p = java.util.Arrays.copyOf(p, p.length * 2);
                pieces[cell] = p;
            }
            p[n * 4] = centre;
            p[n * 4 + 1] = lo;
            p[n * 4 + 2] = hi;
            p[n * 4 + 3] = Float.intBitsToFloat(pat);
            counts[cell] = n + 1;
        }

        /**
         * Jak's floor inside voxel (lx, vy, lz): (height in 16ths of the voxel, 1..16) | (sound
         * kind << 8), or 0 if no floor passes through it. The height is the floor at the cell's
         * centre (on a slope, the middle), or the highest point of a floor that only covers part of
         * the cell.
         */
        public int top(int lx, int vy, int lz) {
            int cell = lx + (lz << 4);
            float[] p = pieces[cell];
            if (p == null) return 0;
            float lo = vy, hi = vy + 1;
            float best = Float.NEGATIVE_INFINITY;
            int bestPat = 0;
            for (int k = 0; k < counts[cell]; k++) {
                float centre = p[k * 4], pLo = p[k * 4 + 1], pHi = p[k * 4 + 2];
                if (pHi <= lo || pLo > hi + 1e-3f) continue; // not in this voxel
                float h = (!Float.isNaN(centre) && centre > lo && centre <= hi + 1e-3f) ? centre : Math.min(pHi, hi);
                if (h > best) {
                    best = h;
                    bestPat = Float.floatToRawIntBits(p[k * 4 + 3]);
                }
            }
            if (best == Float.NEGATIVE_INFINITY) return 0;
            int sixteenths = Math.max(1, Math.min(16, Math.round((best - vy) * 16f)));
            return sixteenths | (JakSurface.soundKind(bestPat) << 8);
        }
    }

    /** The floors over chunk column (cx, cz). */
    public Floors floors(int cx, int cz) {
        Floors f = new Floors();
        Triangles t = columnTriangles.get(columnKey(cx, cz));
        if (t != null) addFloors(f, t, cx, cz);
        if (bigTriangles.count() > 0) addFloors(f, bigTriangles, cx, cz);
        return f;
    }

    private void addFloors(Floors f, Triangles t, int cx, int cz) {
        float[] d = t.data;
        double[] tmp = new double[1];
        double[] poly = new double[2 * 12], next = new double[2 * 12];
        int bx = cx << 4, bz = cz << 4;
        for (int i = 0; i < t.count(); i++) {
            if (!isFloor(t, i, tmp)) continue;
            int o = i * 9;
            double ax = d[o], ay = d[o + 1], az = d[o + 2];
            double bxx = d[o + 3], by = d[o + 4], bzz = d[o + 5];
            double ccx = d[o + 6], ccy = d[o + 7], ccz = d[o + 8];
            // plane: y = ay + gx * (x - ax) + gz * (z - az)
            double ux = bxx - ax, uy = by - ay, uz = bzz - az;
            double vx = ccx - ax, vy = ccy - ay, vz = ccz - az;
            double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            double gx = -nx / ny, gz = -nz / ny;
            int x0 = Math.max(bx, (int) Math.floor(Math.min(ax, Math.min(bxx, ccx))));
            int x1 = Math.min(bx + 15, (int) Math.floor(Math.max(ax, Math.max(bxx, ccx))));
            int z0 = Math.max(bz, (int) Math.floor(Math.min(az, Math.min(bzz, ccz))));
            int z1 = Math.min(bz + 15, (int) Math.floor(Math.max(az, Math.max(bzz, ccz))));
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    // the part of the triangle over this cell (clipped in x/z)
                    poly[0] = ax; poly[1] = az; poly[2] = bxx; poly[3] = bzz; poly[4] = ccx; poly[5] = ccz;
                    int n = 3;
                    n = clip(poly, n, next, 0, x, 1);
                    n = clip(next, n, poly, 0, x + 1, -1);
                    n = clip(poly, n, next, 1, z, 1);
                    n = clip(next, n, poly, 1, z + 1, -1);
                    if (n < 3) continue;
                    double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
                    for (int k = 0; k < n; k++) {
                        double y = ay + gx * (poly[2 * k] - ax) + gz * (poly[2 * k + 1] - az);
                        lo = Math.min(lo, y);
                        hi = Math.max(hi, y);
                    }
                    double px = x + 0.5, pz = z + 0.5;
                    float centre = Float.NaN;
                    if (insideXZ(px, pz, ax, az, bxx, bzz, ccx, ccz)) {
                        centre = (float) (ay + gx * (px - ax) + gz * (pz - az));
                    }
                    f.add((x - bx) + ((z - bz) << 4), centre, (float) lo, (float) hi, t.pats[i]);
                }
            }
        }
    }

    /** Keep the part of polygon in (pairs of x, z) on the side axisValue * dir <= coord * dir. */
    private static int clip(double[] in, int n, double[] out, int axis, double value, int dir) {
        int m = 0;
        for (int k = 0; k < n; k++) {
            int j = (k + 1) % n;
            double a = in[2 * k + axis], b = in[2 * j + axis];
            boolean ain = (a - value) * dir >= 0, bin = (b - value) * dir >= 0;
            if (ain) {
                out[2 * m] = in[2 * k];
                out[2 * m + 1] = in[2 * k + 1];
                m++;
            }
            if (ain != bin && m < 11) {
                double t = (value - a) / (b - a);
                out[2 * m] = in[2 * k] + t * (in[2 * j] - in[2 * k]);
                out[2 * m + 1] = in[2 * k + 1] + t * (in[2 * j + 1] - in[2 * k + 1]);
                m++;
            }
        }
        return m;
    }

    private static boolean insideXZ(double px, double pz, double ax, double az, double bx, double bz,
                                    double cx, double cz) {
        double d1 = (px - bx) * (az - bz) - (ax - bx) * (pz - bz);
        double d2 = (px - cx) * (bz - cz) - (bx - cx) * (pz - cz);
        double d3 = (px - ax) * (cz - az) - (cx - ax) * (pz - az);
        boolean neg = d1 < 0 || d2 < 0 || d3 < 0;
        boolean pos = d1 > 0 || d2 > 0 || d3 > 0;
        return !(neg && pos);
    }

    /**
     * Height of Jak's floor at (x, z) right under maxY (Jak meters), or NaN if there's none within
     * maxDrop below it. For drawing shadows on Jak's ground.
     */
    public double floorBelow(double x, double z, double maxY, double maxDrop) {
        double best = Double.NaN;
        Triangles t = columnTriangles.get(columnKey((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4));
        if (t != null) best = floorBelow(t, x, z, maxY, maxDrop, best);
        if (bigTriangles.count() > 0) best = floorBelow(bigTriangles, x, z, maxY, maxDrop, best);
        return best;
    }

    private double floorBelow(Triangles t, double x, double z, double maxY, double maxDrop, double best) {
        float[] d = t.data;
        double[] tmp = new double[1];
        for (int i = 0; i < t.count(); i++) {
            int o = i * 9;
            double ax = d[o], az = d[o + 2], bx = d[o + 3], bz = d[o + 5], cx = d[o + 6], cz = d[o + 8];
            if (x < Math.min(ax, Math.min(bx, cx)) || x > Math.max(ax, Math.max(bx, cx))
                    || z < Math.min(az, Math.min(bz, cz)) || z > Math.max(az, Math.max(bz, cz))) continue;
            if (!insideXZ(x, z, ax, az, bx, bz, cx, cz) || !isFloor(t, i, tmp)) continue;
            double ay = d[o + 1];
            double ux = bx - ax, uy = d[o + 4] - ay, uz = bz - az;
            double vx = cx - ax, vy = d[o + 7] - ay, vz = cz - az;
            double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            double y = ay + (-nx / ny) * (x - ax) + (-nz / ny) * (z - az);
            if (y <= maxY && y >= maxY - maxDrop && (Double.isNaN(best) || y > best)) best = y;
        }
        return best;
    }

    /**
     * Jak's floors over a small x/z rectangle, for many floorBelow questions in a row (a shadow):
     * the floor triangles there are picked out once.
     */
    public static final class FloorPatch {
        // per triangle: ax az bx bz cx cz, plane y = y0 + gx * x + gz * z
        private double[] t = new double[9 * 32];
        private int n = 0;

        void add(double ax, double az, double bx, double bz, double cx, double cz, double y0, double gx, double gz) {
            if ((n + 1) * 9 > t.length) t = java.util.Arrays.copyOf(t, t.length * 2);
            int o = n * 9;
            t[o] = ax; t[o + 1] = az; t[o + 2] = bx; t[o + 3] = bz; t[o + 4] = cx; t[o + 5] = cz;
            t[o + 6] = y0; t[o + 7] = gx; t[o + 8] = gz;
            n++;
        }

        public boolean isEmpty() {
            return n == 0;
        }

        /** Same as CollisionVoxels.floorBelow, for points inside the patch. */
        public double floorBelow(double x, double z, double maxY, double maxDrop) {
            double best = Double.NaN;
            for (int i = 0; i < n; i++) {
                int o = i * 9;
                if (!insideXZ(x, z, t[o], t[o + 1], t[o + 2], t[o + 3], t[o + 4], t[o + 5])) continue;
                double y = t[o + 6] + t[o + 7] * x + t[o + 8] * z;
                if (y <= maxY && y >= maxY - maxDrop && (Double.isNaN(best) || y > best)) best = y;
            }
            return best;
        }
    }

    /** The floors over x0..x1, z0..z1 (Jak meters). */
    public FloorPatch floorPatch(double x0, double z0, double x1, double z1) {
        FloorPatch p = new FloorPatch();
        int cx0 = (int) Math.floor(x0) >> 4, cx1 = (int) Math.floor(x1) >> 4;
        int cz0 = (int) Math.floor(z0) >> 4, cz1 = (int) Math.floor(z1) >> 4;
        java.util.Set<Triangles> lists = new java.util.HashSet<>();
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                Triangles t = columnTriangles.get(columnKey(cx, cz));
                if (t != null) lists.add(t);
            }
        }
        if (bigTriangles.count() > 0) lists.add(bigTriangles);
        double[] tmp = new double[1];
        // a triangle spanning two columns is in both lists; adding it twice changes nothing
        for (Triangles t : lists) {
            float[] d = t.data;
            for (int i = 0; i < t.count(); i++) {
                int o = i * 9;
                double ax = d[o], az = d[o + 2], bx = d[o + 3], bz = d[o + 5], cx = d[o + 6], cz = d[o + 8];
                if (Math.max(ax, Math.max(bx, cx)) < x0 || Math.min(ax, Math.min(bx, cx)) > x1
                        || Math.max(az, Math.max(bz, cz)) < z0 || Math.min(az, Math.min(bz, cz)) > z1) continue;
                if (!isFloor(t, i, tmp)) continue;
                double ay = d[o + 1];
                double ux = bx - ax, uy = d[o + 4] - ay, uz = bz - az;
                double vx = cx - ax, vy = d[o + 7] - ay, vz = cz - az;
                double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
                double gx = -nx / ny, gz = -nz / ny;
                p.add(ax, az, bx, bz, cx, cz, ay - gx * ax - gz * az, gx, gz);
            }
        }
        return p;
    }

    /** Parse one OBJ ("v x y z", "usemtl pat_xxxxxxxx", "f a/a b/b c/c", 1-based, per-file indices). */
    public void addObj(Path file) throws IOException {
        List<double[]> verts = new ArrayList<>();
        int pat = 0;
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("v ")) {
                    String[] p = line.trim().split("\\s+");
                    if (p.length >= 4) {
                        verts.add(new double[] {Double.parseDouble(p[1]), Double.parseDouble(p[2]),
                                Double.parseDouble(p[3])});
                    }
                } else if (line.startsWith("usemtl ")) {
                    pat = JakSurface.parse(line.substring(7));
                } else if (line.startsWith("f ")) {
                    String[] p = line.trim().split("\\s+");
                    if (p.length < 4) continue;
                    int[] idx = new int[p.length - 1];
                    boolean ok = true;
                    for (int k = 1; k < p.length; k++) {
                        String tok = p[k];
                        int slash = tok.indexOf('/');
                        int v = Integer.parseInt(slash < 0 ? tok : tok.substring(0, slash));
                        v = v < 0 ? verts.size() + v : v - 1; // OBJ allows negative indices
                        if (v < 0 || v >= verts.size()) {
                            ok = false;
                            break;
                        }
                        idx[k - 1] = v;
                    }
                    if (!ok) continue;
                    // fan-triangulate polygons
                    double[] a = verts.get(idx[0]);
                    for (int k = 1; k + 1 < idx.length; k++) {
                        double[] b = verts.get(idx[k]);
                        double[] c = verts.get(idx[k + 1]);
                        addTriangle(a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2], pat);
                    }
                }
            }
        }
        files++;
    }

    /** Load every collide-*.obj in a folder. Reports progress per file. */
    public static CollisionVoxels loadFolder(Path dir, Consumer<String> progress) throws IOException {
        CollisionVoxels vox = new CollisionVoxels();
        List<Path> objs = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.obj")) {
            for (Path p : ds) objs.add(p);
        }
        objs.sort(null);
        for (Path p : objs) {
            vox.addObj(p);
            if (progress != null) progress.accept(p.getFileName().toString());
        }
        return vox;
    }
}
