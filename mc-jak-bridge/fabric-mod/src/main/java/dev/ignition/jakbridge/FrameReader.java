package dev.ignition.jakbridge;

import java.io.IOException;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Reads the frames Jak shares through a memory-mapped file (written by
 * game/mods/mc_frame_share.cpp in OpenGOAL). No Minecraft dependencies.
 *
 * Layout (version 2, see FRAME_SHARING.md): a 64-byte global header (magic 'JKFR', version, the
 * newest complete slot, slot count, slot stride...) then 3 slots, each a 64-byte slot header
 * (seq, size, format, frame number, the camera pose the frame was rendered from) and BGRA8 pixels,
 * bottom row first. Jak writes the slots in turn, so the newest one stays untouched for two frames.
 */
public final class FrameReader implements AutoCloseable {
    public static final int MAGIC = 0x52464B4A; // "JKFR" little-endian
    public static final int VERSION = 2;
    public static final int HEADER_SIZE = 64;
    public static final int FORMAT_RGBA8 = 1;
    public static final int FORMAT_BGRA8 = 2;
    private static final long REOPEN_INTERVAL_NANOS = 2_000_000_000L;
    private static final long FRESH_NANOS = 500_000_000L;

    private final Path path;
    private FileChannel channel;
    private MappedByteBuffer map;
    private long lastOpenAttempt = 0;
    private int lastLatest = -1;
    private long lastNewFrameNanos = 0;
    private long framesReceived = 0;

    public FrameReader(Path path) {
        this.path = path;
    }

    public static Path defaultPath() {
        String env = System.getenv("JAKCRAFT_FRAME_FILE");
        if (env != null && !env.isBlank()) {
            return Paths.get(env);
        }
        return Paths.get(System.getProperty("java.io.tmpdir"), "jakcraft-frames.bin");
    }

    public Path path() {
        return path;
    }

    /** A frame that can be uploaded right now. */
    public static final class Frame {
        public int seq;
        public int frame;
        public int width;
        public int height;
        public int format;
        /** Pixels, bottom row first, exactly width*height*4 bytes. Read-only view. */
        public ByteBuffer pixels;
        /** The Minecraft camera this frame was rendered from (Jak meters, Minecraft degrees). */
        public boolean poseValid;
        public int poseSeq;
        public float x, y, z, yaw, pitch, vfov;
        int slotBase;
    }

    private boolean ensureOpen() {
        if (map != null) {
            return true;
        }
        long now = System.nanoTime();
        if (now - lastOpenAttempt < REOPEN_INTERVAL_NANOS) {
            return false;
        }
        lastOpenAttempt = now;
        if (!Files.exists(path)) {
            return false;
        }
        try {
            channel = FileChannel.open(path, StandardOpenOption.READ);
            long size = channel.size();
            if (size < HEADER_SIZE || size > Integer.MAX_VALUE) {
                channel.close();
                channel = null;
                return false;
            }
            map = channel.map(FileChannel.MapMode.READ_ONLY, 0, size);
            map.order(ByteOrder.LITTLE_ENDIAN);
            return true;
        } catch (IOException e) {
            close();
            return false;
        }
    }

    /** The file is bigger than when we mapped it (Jak started after us): map it again soon. */
    private Frame remap() {
        close();
        lastOpenAttempt = 0;
        return null;
    }

    /**
     * Returns the newest complete frame if Jak wrote one since the last call, else null.
     * Call {@link #stillValid(Frame)} after using the pixels to detect a frame overwritten while
     * it was being used (only possible if using it took longer than two of Jak's frames).
     */
    public Frame poll() {
        if (!ensureOpen()) {
            return null;
        }
        if (map.getInt(0) != MAGIC || map.getInt(4) != VERSION) {
            return null;
        }
        VarHandle.acquireFence();
        int latest = map.getInt(8);
        if (latest == lastLatest) {
            return null;
        }
        int slotCount = map.getInt(12);
        long stride = map.getInt(16) & 0xffffffffL;
        int slot = latest & 3;
        if (slot >= slotCount || stride < HEADER_SIZE) {
            return null;
        }
        long base = HEADER_SIZE + slot * stride;
        if (base + HEADER_SIZE > map.capacity()) {
            return remap();
        }
        int b = (int) base;
        int seq = map.getInt(b);
        if ((seq & 1) != 0) {
            return null;
        }
        int w = map.getInt(b + 4);
        int h = map.getInt(b + 8);
        if (w <= 0 || h <= 0 || (long) w * h * 4 > stride - HEADER_SIZE) {
            return null;
        }
        if (base + HEADER_SIZE + (long) w * h * 4 > map.capacity()) {
            return remap();
        }
        Frame f = new Frame();
        f.slotBase = b;
        f.seq = seq;
        f.width = w;
        f.height = h;
        f.format = map.getInt(b + 12);
        f.frame = map.getInt(b + 16);
        f.poseValid = map.getInt(b + 20) != 0;
        f.poseSeq = map.getInt(b + 24);
        f.x = map.getFloat(b + 28);
        f.y = map.getFloat(b + 32);
        f.z = map.getFloat(b + 36);
        f.yaw = map.getFloat(b + 40);
        f.pitch = map.getFloat(b + 44);
        f.vfov = map.getFloat(b + 48);
        f.pixels = map.slice(b + HEADER_SIZE, w * h * 4).order(ByteOrder.LITTLE_ENDIAN);
        // Jak started rewriting this slot while we read its header: try again next time
        VarHandle.acquireFence();
        if (map.getInt(b) != seq) {
            return null;
        }
        lastLatest = latest;
        lastNewFrameNanos = System.nanoTime();
        framesReceived++;
        return f;
    }

    /** True if Jak did not start overwriting the frame while we were using it. */
    public boolean stillValid(Frame f) {
        VarHandle.acquireFence();
        return map != null && map.getInt(f.slotBase) == f.seq;
    }

    /** True if Jak delivered a new frame recently. */
    public boolean isFresh() {
        return lastNewFrameNanos != 0 && System.nanoTime() - lastNewFrameNanos < FRESH_NANOS;
    }

    /** Frames Jak has written in total (from the file), or -1. */
    public long framesWrittenByJak() {
        if (map == null || map.getInt(0) != MAGIC) return -1;
        return map.getInt(28) & 0xffffffffL;
    }

    /** Frames this reader has picked up in total. */
    public long framesReceived() {
        return framesReceived;
    }

    @Override
    public void close() {
        map = null;
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
            }
            channel = null;
        }
    }
}
