package dev.ignition.jakbridge;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The 1 m cells of Jak's ground that the player has dug out (Jak meters). No Minecraft
 * dependencies.
 *
 * Jak's ground is only a shell of invisible blocks, so digging works like this: a Jak ground
 * block hit with a pickaxe or shovel turns into dirt or stone, and when a block is dug out its
 * neighbours below Jak's surface become dirt or stone too, so the hole gets walls and a floor.
 * Dug cells are remembered (saved with the world) so Jak's invisible ground never comes back
 * there, and Minecraft stops hiding its own blocks behind Jak's ground at those cells, so you can
 * see into the hole.
 *
 * Shared between the integrated server (digging) and the client (drawing).
 */
public final class JakDigs {
    private static final Set<Long> cells = ConcurrentHashMap.newKeySet();
    /** Dirt / stone this mod put in Jak's ground (so it can tell them from the player's own blocks). */
    private static final Set<Long> earth = ConcurrentHashMap.newKeySet();
    /**
     * Cells dug before 0.13, when Jak's floors were the voxel *below* the surface: the voxel above
     * such a hole is now the floor block, and is dug out too when it's placed (see CollisionPlacer).
     */
    private static final Set<Long> legacy = ConcurrentHashMap.newKeySet();
    /** Save file marker (old files start with the cell count, never negative). */
    private static final int FILE_MAGIC = -0x4A44;
    private static final int FILE_VERSION = 2;
    private static volatile int version = 0;
    private static volatile boolean dirty = false;

    private JakDigs() {
    }

    public static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | ((y + 2048) & 0xFFF);
    }

    public static int keyX(long k) {
        return (int) (k >> 38);
    }

    public static int keyZ(long k) {
        return (int) (k << 26 >> 38);
    }

    public static int keyY(long k) {
        return (int) (k & 0xFFF) - 2048;
    }

    public static boolean contains(int x, int y, int z) {
        return !cells.isEmpty() && cells.contains(key(x, y, z));
    }

    public static void add(int x, int y, int z) {
        earth.remove(key(x, y, z));
        if (cells.add(key(x, y, z))) {
            version++;
            dirty = true;
        }
    }

    public static void addEarth(int x, int y, int z) {
        if (earth.add(key(x, y, z))) dirty = true;
    }

    /** A hole dug before 0.13 (see legacy). */
    public static boolean isLegacy(int x, int y, int z) {
        return !legacy.isEmpty() && legacy.contains(key(x, y, z));
    }

    public static boolean isEarth(int x, int y, int z) {
        return !earth.isEmpty() && earth.contains(key(x, y, z));
    }

    public static boolean isEmpty() {
        return cells.isEmpty();
    }

    public static int size() {
        return cells.size();
    }

    /** Changes every time a cell is dug (for caches). */
    public static int version() {
        return version;
    }

    /** All dug cells within the box (inclusive), as {x, y, z}. */
    public static List<int[]> within(int x0, int y0, int z0, int x1, int y1, int z1) {
        List<int[]> out = new ArrayList<>();
        for (long k : cells) {
            int x = keyX(k), y = keyY(k), z = keyZ(k);
            if (x >= x0 && x <= x1 && y >= y0 && y <= y1 && z >= z0 && z <= z1) out.add(new int[] {x, y, z});
        }
        return out;
    }

    public static void clear() {
        cells.clear();
        earth.clear();
        legacy.clear();
        version++;
        dirty = false;
    }

    public static void load(Path file) {
        clear();
        if (file == null || !Files.exists(file)) return;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            int n = in.readInt();
            boolean old = n != FILE_MAGIC;
            if (!old) {
                in.readInt(); // version
                n = in.readInt();
            }
            for (int i = 0; i < n; i++) {
                long k = in.readLong();
                cells.add(k);
                if (old) legacy.add(k);
            }
            int m = in.readInt();
            for (int i = 0; i < m; i++) earth.add(in.readLong());
            if (!old) {
                int l = in.readInt();
                for (int i = 0; i < l; i++) legacy.add(in.readLong());
            }
            version++;
            dirty = old; // rewrite in the new format
        } catch (IOException e) {
            JakBridgeClient.LOG.warn("Could not read {}: {}", file, e.toString());
            dirty = false;
        }
    }

    /** Write the cells if they changed since the last save. */
    public static void saveIfDirty(Path file) {
        if (!dirty || file == null) return;
        dirty = false;
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
                out.writeInt(FILE_MAGIC);
                out.writeInt(FILE_VERSION);
                List<Long> copy = new ArrayList<>(cells);
                out.writeInt(copy.size());
                for (long k : copy) out.writeLong(k);
                List<Long> e = new ArrayList<>(earth);
                out.writeInt(e.size());
                for (long k : e) out.writeLong(k);
                List<Long> l = new ArrayList<>(legacy);
                out.writeInt(l.size());
                for (long k : l) out.writeLong(k);
            }
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            dirty = true;
            JakBridgeClient.LOG.warn("Could not save {}: {}", file, e.toString());
        }
    }
}
