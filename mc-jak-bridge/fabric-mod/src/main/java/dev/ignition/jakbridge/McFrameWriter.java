package dev.ignition.jakbridge;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * JAK mode: Minecraft's side of the frame Jak draws over its own (see game/mods/mc_overlay.cpp).
 * No Minecraft dependencies.
 *
 * Same layout as FrameReader, magic 'MCFR': 64-byte header (magic, version, seq, width, height,
 * format, frames written) then RGBA8 premultiplied-alpha pixels, bottom row first. seq is odd while
 * a frame is being written.
 */
public final class McFrameWriter implements AutoCloseable {
    public static final int MAGIC = 0x5246434D; // "MCFR" little-endian
    public static final int HEADER_SIZE = 64;
    public static final int MAX_WIDTH = 3840;
    public static final int MAX_HEIGHT = 2160;
    private static final long FILE_SIZE = HEADER_SIZE + (long) MAX_WIDTH * MAX_HEIGHT * 4;

    private final RandomAccessFile file;
    private final FileChannel channel;
    private final MappedByteBuffer map;
    private int seq;
    private int frames = 0;

    public McFrameWriter(Path path) throws IOException {
        file = new RandomAccessFile(path.toFile(), "rw");
        // never resize a file Jak may have mapped (Windows refuses), only grow a new/short one
        if (file.length() != FILE_SIZE) {
            file.setLength(FILE_SIZE);
        }
        channel = file.getChannel();
        map = channel.map(FileChannel.MapMode.READ_WRITE, 0, FILE_SIZE);
        map.order(ByteOrder.LITTLE_ENDIAN);
        seq = map.getInt(8);
        if ((seq & 1) != 0) seq++;
        map.putInt(0, MAGIC);
        map.putInt(4, 1);
        map.putInt(20, 1);
        map.putInt(8, seq);
    }

    public static Path defaultPath() {
        String env = System.getenv("JAKCRAFT_MC_FRAME_FILE");
        if (env != null && !env.isBlank()) {
            return Paths.get(env);
        }
        return Paths.get(System.getProperty("java.io.tmpdir"), "jakcraft-mc-frame.bin");
    }

    /**
     * Start a frame of the given size. Returns the buffer to fill with width*height*4 bytes
     * (e.g. straight from glReadPixels), or null if the size is too big. Call {@link #end()} after.
     */
    public ByteBuffer begin(int width, int height) {
        if (width <= 0 || height <= 0 || width > MAX_WIDTH || height > MAX_HEIGHT) {
            return null;
        }
        seq++; // odd: writing
        map.putInt(8, seq);
        VarHandle.fullFence();
        map.putInt(12, width);
        map.putInt(16, height);
        return map.slice(HEADER_SIZE, width * height * 4).order(ByteOrder.LITTLE_ENDIAN);
    }

    public void end() {
        VarHandle.fullFence();
        map.putInt(24, ++frames);
        seq++; // even: complete
        map.putInt(8, seq);
    }

    @Override
    public void close() throws IOException {
        channel.close();
        file.close();
    }
}
