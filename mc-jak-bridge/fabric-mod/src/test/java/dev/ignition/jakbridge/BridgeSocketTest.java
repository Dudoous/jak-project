package dev.ignition.jakbridge;

import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Checks the byte layout against PROTOCOL.md using a fake runtime socket. */
class BridgeSocketTest {

    @Test
    void cameraPacketLayoutAndJakStateReply() throws Exception {
        try (DatagramSocket fakeRuntime = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            fakeRuntime.setSoTimeout(2000);
            try (BridgeSocket bridge = new BridgeSocket(fakeRuntime.getLocalPort())) {
                bridge.sendCamera(1.5, 65.62, -3.25, 90f, -12.5f, 70f, 1f, -1f,
                        BridgeSocket.BUTTON_JUMP | BridgeSocket.BUTTON_ATTACK, BridgeSocket.FLAG_IN_WORLD,
                        1.25f, 63.0f, -7.5f);

                byte[] buf = new byte[256];
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                fakeRuntime.receive(p);
                assertEquals(BridgeSocket.CAMERA_PACKET_SIZE, p.getLength());

                ByteBuffer in = ByteBuffer.wrap(buf, 0, p.getLength()).order(ByteOrder.LITTLE_ENDIAN);
                assertEquals(BridgeSocket.MAGIC, in.getInt(0));
                assertEquals(1, in.getShort(4));
                assertEquals(BridgeSocket.TYPE_CAMERA, in.getShort(6));
                assertEquals(0, in.getInt(8));                    // seq
                assertEquals(BridgeSocket.FLAG_IN_WORLD, in.getInt(12));
                assertEquals(1.5, in.getDouble(16));
                assertEquals(65.62, in.getDouble(24));
                assertEquals(-3.25, in.getDouble(32));
                assertEquals(90f, in.getFloat(40));
                assertEquals(-12.5f, in.getFloat(44));
                assertEquals(70f, in.getFloat(48));
                assertEquals(1f, in.getFloat(52));
                assertEquals(-1f, in.getFloat(56));
                assertEquals(BridgeSocket.BUTTON_JUMP | BridgeSocket.BUTTON_ATTACK, in.getInt(60));
                assertEquals(1.25f, in.getFloat(64));   // Steve's feet
                assertEquals(63.0f, in.getFloat(68));
                assertEquals(-7.5f, in.getFloat(72));
                assertEquals(1, in.getInt(76));

                // reply like the runtime does, to the sender's address
                ByteBuffer out = ByteBuffer.allocate(BridgeSocket.JAK_STATE_PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN);
                out.putInt(BridgeSocket.MAGIC).putShort((short) 1).putShort(BridgeSocket.TYPE_JAK_STATE).putInt(7);
                for (int i = 0; i < 8; i++) out.putFloat(i * 1.5f);
                fakeRuntime.send(new DatagramPacket(out.array(), out.capacity(), p.getSocketAddress()));

                BridgeSocket.JakState state = null;
                for (int i = 0; i < 100 && state == null; i++) {
                    Thread.sleep(20);
                    state = bridge.latestJakState();
                }
                assertNotNull(state);
                assertEquals(7, state.seq);
                assertEquals(4.5f, state.data[3]);
            }
        }
    }
    @Test
    void blocksPacketLayout() throws Exception {
        try (DatagramSocket fakeRuntime = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            fakeRuntime.setSoTimeout(2000);
            try (BridgeSocket bridge = new BridgeSocket(fakeRuntime.getLocalPort())) {
                int[] blocks = {10, -3, 7, BridgeSocket.BLOCK_SOLID, -1, 64, 2000, BridgeSocket.BLOCK_DEADLY};
                bridge.sendBlocks(blocks, 2);

                byte[] buf = new byte[65536];
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                fakeRuntime.receive(p);
                assertEquals(16 + 2 * 16, p.getLength());
                ByteBuffer in = ByteBuffer.wrap(buf, 0, p.getLength()).order(ByteOrder.LITTLE_ENDIAN);
                assertEquals(BridgeSocket.MAGIC, in.getInt(0));
                assertEquals(BridgeSocket.TYPE_BLOCKS, in.getShort(6));
                assertEquals(2, in.getInt(12));
                for (int i = 0; i < 8; i++) assertEquals(blocks[i], in.getInt(16 + 4 * i));

                // never more than MAX_BLOCKS, so the packet always fits in one datagram
                bridge.sendBlocks(new int[4 * (BridgeSocket.MAX_BLOCKS + 10)], BridgeSocket.MAX_BLOCKS + 10);
                p.setLength(buf.length);
                fakeRuntime.receive(p);
                assertEquals(16 + 16 * BridgeSocket.MAX_BLOCKS, p.getLength());
            }
        }
    }
}
