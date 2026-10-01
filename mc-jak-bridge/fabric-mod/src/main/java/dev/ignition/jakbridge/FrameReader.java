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
 * Layout: 64-byte header (magic 'JKFR', version, seq, width, height, format, frames written) then
 * RGBA8 pixels, bottom row first. seq is odd while Jak is writing a frame.
 */
public final class FrameReader implements AutoCloseable {
    public static final int MAGIC = 0x52464B4A; // "JKFR" little-endian
    public static final int VERSION = 1;
    public static final int HEADER_SIZE = 64;
    private static final long REOPEN_INTERVAL_NANOS = 2_000_000_000L;
    private static final long FRESH_NANOS = 500_000_000L;

    private final Path path;
    private FileChannel channel;
    private MappedByteBuffer map;
    private long lastOpenAttempt = 0;
    private int lastSeq = -1;
    private long lastNewFrameNanos = 0;

    public FrameReader(Path path) {
        this.path = path;
    }

    public static Path defaultPath() {
        String env = System.getenv("JAKCRAFT_FRAME_FILE");
        if (env != null && !env.isBlank()) {
            return Paths.get(env);
        }
        return Paths.get(System.getProperty("java.io.tmpdir"), "jakcraft-frame.bin");
    }

    public Path path() {
        return path;
    }

    /** A frame that can be uploaded right now. Valid until the next call to {@link #poll}. */
    public static final class Frame {
        public int seq;
        public int width;
        public int height;
        /** RGBA8 pixels, bottom row first, exactly width*height*4 bytes. Read-only view. */
        public ByteBuffer pixels;
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
            if (size < HEADER_SIZE) {
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

    /**
     * Returns the newest complete frame if Jak wrote one since the last call, else null.
     * Call {@link #stillValid(Frame)} after using the pixels to detect a frame torn by a
     * concurrent write.
     */
    public Frame poll() {
        if (!ensureOpen()) {
            return null;
        }
        if (map.getInt(0) != MAGIC || map.getInt(4) != VERSION) {
            return null;
        }
        VarHandle.acquireFence();
        int seq = map.getInt(8);
        if ((seq & 1) != 0 || seq == lastSeq) {
            return null;
        }
        int w = map.getInt(12);
        int h = map.getInt(16);
        long needed = HEADER_SIZE + (long) w * h * 4;
        if (w <= 0 || h <= 0 || needed > map.capacity()) {
            return null;
        }
        Frame f = new Frame();
        f.seq = seq;
        f.width = w;
        f.height = h;
        f.pixels = map.slice(HEADER_SIZE, w * h * 4).order(ByteOrder.LITTLE_ENDIAN);
        lastSeq = seq;
        lastNewFrameNanos = System.nanoTime();
        return f;
    }

    /** True if Jak did not start overwriting the frame while we were using it. */
    public boolean stillValid(Frame f) {
        VarHandle.acquireFence();
        return map != null && map.getInt(8) == f.seq;
    }

    /** True if Jak delivered a new frame recently (Jak is running in STEVE mode). */
    public boolean isFresh() {
        return lastNewFrameNanos != 0 && System.nanoTime() - lastNewFrameNanos < FRESH_NANOS;
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
