package dev.ignition.jakbridge;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JakSyncTest {

    @Test
    void predictsWhereSteveWillBe() {
        JakSync.ViewPredictor p = new JakSync.ViewPredictor();
        long t = 0;
        double x = 0;
        double[] ahead = null;
        // Steve runs at 10 blocks/s along +X for two seconds at 60 fps; Jak's picture is 70 ms late
        for (int i = 0; i < 120; i++) {
            p.update(x, 64, 0, t);
            x += 10.0 / 60;
            t += 16_666_667L;
            ahead = p.ahead(70f);
        }
        assertEquals(0.7, ahead[0], 0.02);
        assertEquals(0.0, ahead[1], 1e-6);
        assertEquals(0.0, ahead[2], 1e-6);

        // a teleport doesn't fling the view
        p.update(500, 64, 0, t);
        assertEquals(0.0, p.ahead(70f)[0], 1e-6);

        // standing still: no prediction; not measured yet: none either
        JakSync.ViewPredictor still = new JakSync.ViewPredictor();
        for (int i = 0; i < 30; i++) still.update(3, 64, 3, i * 16_666_667L);
        assertEquals(0.0, still.ahead(70f)[0], 1e-6);
        assertEquals(0.0, p.ahead(-1f)[0], 1e-6);
    }

    @Test
    void predictionIsCapped() {
        JakSync.ViewPredictor p = new JakSync.ViewPredictor();
        double x = 0;
        double[] ahead = null;
        for (int i = 0; i < 300; i++) { // flying at 60 blocks/s, a huge delay
            p.update(x, 0, 0, i * 16_666_667L);
            x += 1.0;
            ahead = p.ahead(1000f);
        }
        assertTrue(ahead[0] <= 1.5 + 1e-9);
        assertEquals(1.5, p.lastAhead(), 1e-6);
    }

    @Test
    void readsSyncFields() {
        float[] d = new float[BridgeSocket.JAK_STATE_FLOATS];
        d[JakSync.JAK_GAP] = 0.03f;
        d[JakSync.ON_STEVE] = 1;
        d[JakSync.KILLS] = 2;
        d[JakSync.PUNCHES] = 5;
        d[JakSync.PUNCH_RESULT] = BridgeSocket.PUNCH_MISSED;
        d[JakSync.LEVEL0] = 2;        // Sandover
        d[JakSync.LEVEL0 + 1] = 7;    // active and shown
        d[JakSync.LEVEL1] = 6;        // Misty Island
        d[JakSync.LEVEL1 + 1] = 1;    // loading
        d[JakSync.STREAM_WANT] = 6;
        assertEquals("Misty Island", JakSync.levelStillLoading(d));
        List<String> lines = JakSync.statusLines(d, 0.4, true, true);
        assertEquals(3, lines.size());
        assertTrue(lines.get(0).startsWith("Jak on Steve: yes"));
        assertTrue(lines.get(1).contains("kills: 2"));
        assertTrue(lines.get(2).contains("loading Misty Island"));

        d[JakSync.LEVEL1 + 1] = 7; // Misty Island is there now
        assertNull(JakSync.levelStillLoading(d));

        // an older Jak side doesn't send these fields
        float[] old = new float[BridgeSocket.JAK_STATE_FLOATS];
        Arrays.fill(old, 11, old.length, Float.NaN);
        assertNull(JakSync.levelStillLoading(old));
        assertTrue(JakSync.statusLines(old, 0, true, true).get(0).contains("older version"));
    }
}
