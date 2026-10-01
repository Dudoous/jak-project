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
 * Coordinates are Jak meters (1 block = 1 m, same axes as Minecraft). The Y offset that moves
 * Jak's world into Minecraft's build height is applied by the caller.
 */
public final class CollisionVoxels {
    /** Floors are put one voxel below the surface so a block's top lines up with Jak's floor. */
    private static final double FLOOR_NORMAL_Y = 0.7;
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

        boolean has(int lx, int y, int lz) {
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

    /** Growable list of triangles, 9 floats each (x y z of 3 corners, Jak meters). */
    public static final class Triangles {
        float[] data = new float[9 * 16];
        int floats = 0;

        void add(double ax, double ay, double az, double bx, double by, double bz, double cx, double cy, double cz) {
            if (floats + 9 > data.length) data = java.util.Arrays.copyOf(data, data.length * 2);
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

    /** Voxelize one triangle (Jak meters). */
    public void addTriangle(double ax, double ay, double az, double bx, double by, double bz,
                            double cx, double cy, double cz) {
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
        // keep the triangle itself too, for the depth proxy (Minecraft hides its blocks behind Jak's walls)
        int cx0 = (int) Math.floor(Math.min(ax, Math.min(bx, cx))) >> 4;
        int cx1 = (int) Math.floor(Math.max(ax, Math.max(bx, cx))) >> 4;
        int cz0 = (int) Math.floor(Math.min(az, Math.min(bz, cz))) >> 4;
        int cz1 = (int) Math.floor(Math.max(az, Math.max(bz, cz))) >> 4;
        if ((long) (cx1 - cx0 + 1) * (cz1 - cz0 + 1) > BIG_TRIANGLE_COLUMNS) {
            bigTriangles.add(ax, ay, az, bx, by, bz, cx, cy, cz);
        } else {
            for (int tx = cx0; tx <= cx1; tx++) {
                for (int tz = cz0; tz <= cz1; tz++) {
                    columnTriangles.computeIfAbsent(columnKey(tx, tz), k -> new Triangles())
                            .add(ax, ay, az, bx, by, bz, cx, cy, cz);
                }
            }
        }

        // floors (either winding): sample half a voxel lower so the voxel below the surface is used
        double yShift = Math.abs(ny / nlen) > FLOOR_NORMAL_Y ? -0.5 : 0.0;

        double len = Math.max(Math.sqrt(ux * ux + uy * uy + uz * uz),
                Math.max(Math.sqrt(vx * vx + vy * vy + vz * vz),
                        Math.sqrt((cx - bx) * (cx - bx) + (cy - by) * (cy - by) + (cz - bz) * (cz - bz))));
        int n = Math.max(1, (int) Math.ceil(len / SAMPLE_STEP));
        for (int i = 0; i <= n; i++) {
            double s = (double) i / n;
            for (int j = 0; j <= n - i; j++) {
                double t = (double) j / n;
                mark(ax + ux * s + vx * t, ay + uy * s + vy * t + yShift, az + uz * s + vz * t);
            }
        }
    }

    /** Parse one OBJ ("v x y z" / "f a/a b/b c/c", 1-based, per-file indices). */
    public void addObj(Path file) throws IOException {
        List<double[]> verts = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("v ")) {
                    String[] p = line.trim().split("\\s+");
                    if (p.length >= 4) {
                        verts.add(new double[] {Double.parseDouble(p[1]), Double.parseDouble(p[2]),
                                Double.parseDouble(p[3])});
                    }
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
                        addTriangle(a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2]);
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
