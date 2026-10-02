package dev.ignition.jakbridge;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * UDP link to the OpenGOAL runtime. Has no Minecraft dependencies so it can be unit tested and
 * reused by tools. Wire format is documented in PROTOCOL.md and must match
 * game/mods/mc_passthrough.cpp in the OpenGOAL tree.
 */
public final class BridgeSocket implements AutoCloseable {
    public static final int MAGIC = 0x4B4A434D; // "MCJK"
    public static final short VERSION = 1;
    public static final short TYPE_CAMERA = 1;
    public static final short TYPE_JAK_STATE = 2;
    public static final short TYPE_EVENT = 3;
    public static final short TYPE_BLOCKS = 5;
    public static final short TYPE_ACTORS = 6;
    public static final short TYPE_LIGHT = 7;
    public static final int LIGHT_FLOATS = 32;
    public static final int ACTOR_BOX = 1;
    public static final int ACTOR_SLAB = 2;
    public static final int EVENT_EXPLOSION = 1;
    public static final int EVENT_PACKET_SIZE = 36;
    public static final int DEFAULT_PORT = 47800;
    public static final int CAMERA_PACKET_SIZE = 88;
    /** JAK_STATE from this version of Jak's side (22 floats); older ones send 56 (11) or 44 (8). */
    public static final int JAK_STATE_PACKET_SIZE = 100;
    public static final int JAK_STATE_FLOATS = 22;
    public static final int JAK_STATE_MIN_PACKET_SIZE = 44;
    /** Most blocks one BLOCKS packet carries (must match kMaxBlocks in mc_passthrough.h). */
    public static final int MAX_BLOCKS = 2048;
    public static final int BLOCK_SOLID = 1;
    public static final int BLOCK_DEADLY = 2;

    public static final int FLAG_IN_WORLD = 1;
    public static final int FLAG_SCREEN_OPEN = 2;
    /** Minecraft is playing: Jak renders his world from Steve's eyes and is hidden/frozen. */
    public static final int FLAG_STEVE_MODE = 8;
    /** Minecraft's game menu is open (singleplayer is paused): Jak pauses too. */
    public static final int FLAG_PAUSED = 32;
    /** Steve is down a hole he dug into Jak's ground: Jak stays up on the surface. */
    public static final int FLAG_IN_HOLE = 16;
    /** Steve's left click also makes the hidden Jak punch (jak_punch setting). */
    public static final int FLAG_JAK_PUNCH = 64;

    /** JAK_STATE data[15]: what Steve's last left click did in Jak's world. */
    public static final int PUNCH_NONE = 0;
    public static final int PUNCH_HIT = 1;
    public static final int PUNCH_NO_EFFECT = 2;
    public static final int PUNCH_MISSED = 3;

    /** JAK_STATE flag: Minecraft has taken over. data[0..2] = Jak's position when that happened,
     *  data[6] = activation id (changes each time it starts again). */
    public static final int JAK_FLAG_STEVE = 2;
    /** JAK_STATE flag: a cutscene is playing with Jak's own camera (show Jak's picture full screen). */
    public static final int JAK_FLAG_CUTSCENE = 4;

    public static final int BUTTON_JUMP = 1;
    public static final int BUTTON_SNEAK = 2;
    public static final int BUTTON_SPRINT = 4;
    public static final int BUTTON_ATTACK = 8;
    public static final int BUTTON_USE = 16;
    /** Minecraft keys that are Jak's buttons (talk to people, warp gates, yes/no questions). */
    public static final int BUTTON_JAK_CIRCLE = 32;
    public static final int BUTTON_JAK_X = 64;
    public static final int BUTTON_JAK_TRIANGLE = 128;
    public static final int BUTTON_JAK_LEFT = 256;
    public static final int BUTTON_JAK_RIGHT = 512;
    public static final int BUTTON_JAK_UP = 1024;
    public static final int BUTTON_JAK_DOWN = 2048;

    /** Solid Jak objects near Steve (crates, closed doors), see PROTOCOL.md, ACTORS. */
    public static final class Actors {
        public int count;
        public final int[] id = new int[64];
        public final int[] kind = new int[64];
        /** x, y, z (Jak meters, centre), half size (m), normal x, normal z, per object. */
        public final float[] data = new float[64 * 6];
        public long receivedNanos;
    }

    /** Jak's light, fog and clock where Steve is (see PROTOCOL.md, LIGHT and JakLight). */
    public static final class Light {
        public final float[] data = new float[LIGHT_FLOATS];
        public long receivedNanos;
    }

    /** Latest state the Jak side sent back (see PROTOCOL.md, JAK_STATE). Floats an older Jak side
     *  didn't send are NaN. */
    public static final class JakState {
        public int seq;
        public final float[] data = new float[JAK_STATE_FLOATS];
        public long receivedNanos;
    }

    private final DatagramSocket socket;
    private final InetAddress host;
    private final int port;
    private final byte[] sendBuf = new byte[CAMERA_PACKET_SIZE];
    private final ByteBuffer sendView = ByteBuffer.wrap(sendBuf).order(ByteOrder.LITTLE_ENDIAN);
    private final Thread receiver;
    private volatile boolean running = true;
    private volatile JakState latestJakState;
    private volatile Actors latestActors;
    private volatile Light latestLight;
    private int seq = 0;

    public BridgeSocket(int port) throws IOException {
        this.host = InetAddress.getLoopbackAddress();
        this.port = port;
        this.socket = new DatagramSocket(0, host); // ephemeral local port; replies come back here
        this.socket.setSoTimeout(200);
        this.receiver = new Thread(this::receiveLoop, "jakbridge-recv");
        this.receiver.setDaemon(true);
        this.receiver.start();
    }

    /**
     * Send one camera packet. Yaw/pitch/fov in degrees, position in blocks (eye position).
     * Returns the packet's sequence number (Jak tags the frame it draws from it with this number).
     */
    public synchronized int sendCamera(double x, double y, double z, float yaw, float pitch,
                                        float vfov, float forward, float strafe, int buttons,
                                        int flags) throws IOException {
        return sendCamera(x, y, z, yaw, pitch, vfov, forward, strafe, buttons, flags, Float.NaN, 0f, 0f);
    }

    /** Same, plus where Steve's feet are (Jak meters); NaN feetX = not sent. */
    public synchronized int sendCamera(double x, double y, double z, float yaw, float pitch,
                                        float vfov, float forward, float strafe, int buttons,
                                        int flags, float feetX, float feetY, float feetZ) throws IOException {
        return sendCamera(x, y, z, yaw, pitch, vfov, forward, strafe, buttons, flags, feetX, feetY, feetZ,
                Float.NaN, 0f);
    }

    /**
     * Same, plus where Steve himself looks (degrees; NaN lookYaw = not sent). The camera looks the
     * other way in Minecraft's front view; Steve's punches in Jak's world go where Steve looks.
     */
    public synchronized int sendCamera(double x, double y, double z, float yaw, float pitch,
                                        float vfov, float forward, float strafe, int buttons,
                                        int flags, float feetX, float feetY, float feetZ,
                                        float lookYaw, float lookPitch) throws IOException {
        sendView.clear();
        sendView.putInt(MAGIC);
        sendView.putShort(VERSION);
        sendView.putShort(TYPE_CAMERA);
        int mySeq = seq++;
        sendView.putInt(mySeq);
        sendView.putInt(flags);
        sendView.putDouble(x);
        sendView.putDouble(y);
        sendView.putDouble(z);
        sendView.putFloat(yaw);
        sendView.putFloat(pitch);
        sendView.putFloat(vfov);
        sendView.putFloat(forward);
        sendView.putFloat(strafe);
        sendView.putInt(buttons);
        boolean feet = !Float.isNaN(feetX);
        sendView.putFloat(feet ? feetX : 0f);
        sendView.putFloat(feet ? feetY : 0f);
        sendView.putFloat(feet ? feetZ : 0f);
        boolean look = !Float.isNaN(lookYaw);
        sendView.putInt((feet ? 1 : 0) | (look ? 2 : 0));
        sendView.putFloat(look ? lookYaw : 0f);
        sendView.putFloat(look ? lookPitch : 0f);
        socket.send(new DatagramPacket(sendBuf, CAMERA_PACKET_SIZE, host, port));
        return mySeq;
    }

    /** Send a gameplay event to Jak (see PROTOCOL.md, EVENT). Coordinates in Jak meters. */
    public synchronized void sendEvent(int event, float x, float y, float z, float radius, float power)
            throws IOException {
        ByteBuffer b = ByteBuffer.allocate(EVENT_PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(MAGIC).putShort(VERSION).putShort(TYPE_EVENT).putInt(seq++).putInt(event);
        b.putFloat(x).putFloat(y).putFloat(z).putFloat(radius).putFloat(power);
        socket.send(new DatagramPacket(b.array(), EVENT_PACKET_SIZE, host, port));
    }

    /**
     * Send the blocks Steve built near him (see PROTOCOL.md, BLOCKS). xyzk holds 4 ints per block:
     * x, y, z (Jak meters, the block's minimum corner) and kind (BLOCK_SOLID / BLOCK_DEADLY bits).
     * The list replaces the previous one; send an empty list to clear it.
     */
    public synchronized void sendBlocks(int[] xyzk, int count) throws IOException {
        int n = Math.max(0, Math.min(count, Math.min(MAX_BLOCKS, xyzk.length / 4)));
        ByteBuffer b = ByteBuffer.allocate(16 + 16 * n).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(MAGIC).putShort(VERSION).putShort(TYPE_BLOCKS).putInt(seq++).putInt(n);
        for (int i = 0; i < n * 4; i++) {
            b.putInt(xyzk[i]);
        }
        socket.send(new DatagramPacket(b.array(), b.capacity(), host, port));
    }

    public JakState latestJakState() {
        return latestJakState;
    }

    public Actors latestActors() {
        return latestActors;
    }

    public Light latestLight() {
        return latestLight;
    }

    public int sentPackets() {
        return seq;
    }

    private void receiveLoop() {
        byte[] buf = new byte[4096];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        while (running) {
            try {
                packet.setLength(buf.length);
                socket.receive(packet);
                if (packet.getLength() < 16) {
                    continue;
                }
                ByteBuffer in = ByteBuffer.wrap(buf, 0, packet.getLength()).order(ByteOrder.LITTLE_ENDIAN);
                if (in.getInt() != MAGIC || in.getShort() != VERSION) {
                    continue;
                }
                short type = in.getShort();
                if (type == TYPE_LIGHT) {
                    in.getInt(); // seq
                    int n = Math.min(LIGHT_FLOATS, (packet.getLength() - 12) / 4);
                    Light l = new Light();
                    for (int i = 0; i < n; i++) l.data[i] = in.getFloat();
                    l.receivedNanos = System.nanoTime();
                    latestLight = l;
                    continue;
                }
                if (type == TYPE_ACTORS) {
                    in.getInt(); // seq
                    int n = Math.min(64, in.getInt());
                    if (n < 0 || 16 + n * 32 > packet.getLength()) continue;
                    Actors a = new Actors();
                    a.count = n;
                    for (int i = 0; i < n; i++) {
                        a.id[i] = in.getInt();
                        a.kind[i] = in.getInt();
                        for (int k = 0; k < 6; k++) a.data[i * 6 + k] = in.getFloat();
                    }
                    a.receivedNanos = System.nanoTime();
                    latestActors = a;
                    continue;
                }
                if (type != TYPE_JAK_STATE || packet.getLength() < JAK_STATE_MIN_PACKET_SIZE) {
                    continue;
                }
                JakState state = new JakState();
                state.seq = in.getInt();
                int floats = Math.min(state.data.length, (packet.getLength() - 12) / 4);
                for (int i = 0; i < state.data.length; i++) {
                    state.data[i] = i < floats ? in.getFloat() : Float.NaN;
                }
                state.receivedNanos = System.nanoTime();
                latestJakState = state;
            } catch (SocketTimeoutException e) {
                // poll running flag
            } catch (IOException e) {
                if (running) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ignored) {
                        return;
                    }
                }
            }
        }
    }

    @Override
    public void close() {
        running = false;
        socket.close();
    }
}
