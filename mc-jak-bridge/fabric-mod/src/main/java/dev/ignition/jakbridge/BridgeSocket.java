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
    public static final int DEFAULT_PORT = 47800;
    public static final int CAMERA_PACKET_SIZE = 64;
    public static final int JAK_STATE_PACKET_SIZE = 44;

    public static final int FLAG_IN_WORLD = 1;
    public static final int FLAG_SCREEN_OPEN = 2;
    /** Ask Jak for BODY mode: Jak's physics moves you, Minecraft keys drive Jak. */
    public static final int FLAG_BODY_MODE = 4;
    /** Ask Jak for STEVE mode: Minecraft is the game, Jak renders his world for it. */
    public static final int FLAG_STEVE_MODE = 8;

    /** JAK_STATE flag: Jak is in BODY mode, data[0..2] is where the Minecraft eyes should be. */
    public static final int JAK_FLAG_BODY = 1;
    /** JAK_STATE flag: Jak is in STEVE mode, data[0..2] = Jak's position when Steve took over,
     *  data[6] = activation id (changes each time STEVE mode starts). */
    public static final int JAK_FLAG_STEVE = 2;

    public static final int BUTTON_JUMP = 1;
    public static final int BUTTON_SNEAK = 2;
    public static final int BUTTON_SPRINT = 4;
    public static final int BUTTON_ATTACK = 8;
    public static final int BUTTON_USE = 16;

    /** Latest state the Jak side sent back (see PROTOCOL.md, JAK_STATE). */
    public static final class JakState {
        public int seq;
        public final float[] data = new float[8];
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

    /** Send one camera packet. Yaw/pitch/fov in degrees, position in blocks (eye position). */
    public synchronized void sendCamera(double x, double y, double z, float yaw, float pitch,
                                        float vfov, float forward, float strafe, int buttons,
                                        int flags) throws IOException {
        sendView.clear();
        sendView.putInt(MAGIC);
        sendView.putShort(VERSION);
        sendView.putShort(TYPE_CAMERA);
        sendView.putInt(seq++);
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
        socket.send(new DatagramPacket(sendBuf, CAMERA_PACKET_SIZE, host, port));
    }

    public JakState latestJakState() {
        return latestJakState;
    }

    public int sentPackets() {
        return seq;
    }

    private void receiveLoop() {
        byte[] buf = new byte[256];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        while (running) {
            try {
                packet.setLength(buf.length);
                socket.receive(packet);
                if (packet.getLength() < JAK_STATE_PACKET_SIZE) {
                    continue;
                }
                ByteBuffer in = ByteBuffer.wrap(buf, 0, packet.getLength()).order(ByteOrder.LITTLE_ENDIAN);
                if (in.getInt() != MAGIC || in.getShort() != VERSION || in.getShort() != TYPE_JAK_STATE) {
                    continue;
                }
                JakState state = new JakState();
                state.seq = in.getInt();
                for (int i = 0; i < state.data.length; i++) {
                    state.data[i] = in.getFloat();
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
