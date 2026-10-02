package dev.ignition.jakbridge;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeping Jak's world in step with Steve. No Minecraft dependencies so it can be unit tested.
 *
 *  - {@link ViewPredictor}: where Steve will be by the time Jak's picture of him is on screen
 *    (predict_view), so Jak's world doesn't trail behind Steve when he moves.
 *  - Reading the sync / punch / level fields of JAK_STATE (data[11..20], see PROTOCOL.md) for
 *    /jakcraft, the F9 overlay and the action bar.
 */
public final class JakSync {
    private JakSync() {
    }

    /** JAK_STATE data indices (see PROTOCOL.md). */
    public static final int JAK_GAP = 11;
    public static final int ON_STEVE = 12;
    public static final int KILLS = 13;
    public static final int PUNCHES = 14;
    public static final int PUNCH_RESULT = 15;
    public static final int LEVEL0 = 16;
    public static final int LEVEL1 = 18;
    public static final int STREAM_WANT = 20;

    /** Level slot state: active (3) + drawn (4). */
    private static final int LEVEL_SHOWING = 7;

    /** Jak and Daxter's levels by level-load-info index (goal_src/jak1/engine/level/level-info.gc). */
    private static final String[] LEVEL_NAMES = {
            "none", "Geyser Rock", "Sandover Village", "Sentinel Beach", "Forbidden Jungle",
            "Forbidden Jungle (temple)", "Misty Island", "Fire Canyon", "Rock Village",
            "Lost Precursor City", "Lost Precursor City (helix)", "Boggy Swamp", "Precursor Basin",
            "Mountain Pass", "Volcanic Crater", "Snowy Mountain", "Spider Cave", "Spider Cave (dark)",
            "Spider Cave (robots)", "Lava Tube", "Gol and Maia's Citadel", "Final Boss", "intro", "demo",
            "title", "halfpipe", "default"};

    /** True if this JAK_STATE came from a Jak side that sends the sync fields (JakCraft 0.14+). */
    public static boolean hasSyncFields(float[] data) {
        return data.length > STREAM_WANT && !Float.isNaN(data[JAK_GAP]) && !Float.isNaN(data[STREAM_WANT]);
    }

    public static String levelName(float index) {
        if (Float.isNaN(index)) return "?";
        int i = Math.round(index);
        return i >= 0 && i < LEVEL_NAMES.length ? LEVEL_NAMES[i] : "level " + i;
    }

    static String levelSlot(float index, float code) {
        if (Float.isNaN(code) || Math.round(code) == 0) return "-";
        int c = Math.round(code);
        String state = switch (c & 3) {
            case 1 -> "loading";
            case 2 -> "loaded";
            case 3 -> "ready";
            default -> "?";
        };
        return levelName(index) + " (" + state + ((c & 4) != 0 ? ", shown" : "") + ")";
    }

    /** The name of the Jak level Steve is in if it isn't loaded and showing yet, else null. */
    public static String levelStillLoading(float[] data) {
        if (!hasSyncFields(data)) return null;
        int want = Math.round(data[STREAM_WANT]);
        if (want <= 0) return null;
        for (int slot : new int[]{LEVEL0, LEVEL1}) {
            if (Math.round(data[slot]) == want && Math.round(data[slot + 1]) == LEVEL_SHOWING) return null;
        }
        return levelName(want);
    }

    /** Lines for /jakcraft and F9: how well Jak keeps up with Steve, punches, Jak's levels. */
    public static List<String> statusLines(float[] data, double viewAheadM, boolean predictView, boolean jakPunch) {
        List<String> out = new ArrayList<>();
        if (!hasSyncFields(data)) {
            out.add("Jak sync: Jak's side is an older version - run the play.bat package that came with this jar");
            return out;
        }
        boolean onSteve = data[ON_STEVE] > 0.5f;
        out.add(String.format("Jak on Steve: %s, %.2f m apart before each fix-up; view %.2f m ahead (predict_view %s)",
                onSteve ? "yes" : "NO (the game is using him, Steve is in a hole, or Steve is on his way to him)",
                data[JAK_GAP], viewAheadM, predictView ? "on" : "off"));
        String punch = switch (Math.round(data[PUNCH_RESULT])) {
            case BridgeSocket.PUNCH_HIT -> "hit";
            case BridgeSocket.PUNCH_NO_EFFECT -> "hit an enemy that ignored it";
            case BridgeSocket.PUNCH_MISSED -> "nothing of Jak's in reach where you looked";
            default -> "none yet";
        };
        out.add(String.format("Punches: %d, kills: %d, last punch: %s%s", Math.round(data[PUNCHES]),
                Math.round(data[KILLS]), punch, jakPunch ? " (Jak punches too)" : ""));
        String loading = levelStillLoading(data);
        out.add("Jak levels: " + levelSlot(data[LEVEL0], data[LEVEL0 + 1]) + " + "
                + levelSlot(data[LEVEL1], data[LEVEL1 + 1])
                + (loading != null ? " - loading " + loading + " for you" : ""));
        return out;
    }

    /**
     * Predicts where Steve will be a moment from now from his smoothed velocity. Jak draws his
     * world from Steve's eyes moved ahead by the time it takes the picture to reach the screen
     * (measured: camera sent -> Jak's frame with that camera shown), so it shows where Steve is
     * now instead of where he was a few frames ago.
     */
    public static final class ViewPredictor {
        /** How quickly the velocity follows Steve (s). */
        private static final double VELOCITY_SMOOTHING = 0.05;
        /** Never predict further ahead than this (s) or this far (blocks): a wrong guess stays small. */
        private static final double MAX_LEAD_S = 0.15;
        private static final double MAX_AHEAD = 1.5;

        private double vx, vy, vz;
        private double lx, ly, lz;
        private long lastNanos = 0;
        private boolean have = false;
        private double leadS = 0;
        private double lastAhead = 0;

        public void reset() {
            vx = vy = vz = 0;
            have = false;
            lastAhead = 0;
        }

        /** Steve's feet (or eyes) this frame. */
        public void update(double x, double y, double z, long nanos) {
            if (!have) {
                lx = x;
                ly = y;
                lz = z;
                lastNanos = nanos;
                have = true;
                return;
            }
            double dt = (nanos - lastNanos) / 1e9;
            if (dt < 0.0005) return; // same frame
            double dx = x - lx, dy = y - ly, dz = z - lz;
            if (dt > 0.25 || dx * dx + dy * dy + dz * dz > 25) {
                // a hitch or a teleport: start over
                vx = vy = vz = 0;
            } else {
                double k = 1 - Math.exp(-dt / VELOCITY_SMOOTHING);
                vx += (dx / dt - vx) * k;
                vy += (dy / dt - vy) * k;
                vz += (dz / dt - vz) * k;
            }
            lx = x;
            ly = y;
            lz = z;
            lastNanos = nanos;
        }

        /**
         * How far ahead of Steve to draw (x, y, z blocks), given how long Jak's picture takes to reach
         * the screen (ms, negative = not measured yet).
         */
        public double[] ahead(float delayMs) {
            if (!have || delayMs < 0 || Float.isNaN(delayMs)) {
                lastAhead = 0;
                return new double[]{0, 0, 0};
            }
            double target = Math.min(MAX_LEAD_S, delayMs / 1000.0);
            leadS += (target - leadS) * 0.1; // the delay jitters frame to frame: follow it slowly
            double ax = vx * leadS, ay = vy * leadS, az = vz * leadS;
            double len = Math.sqrt(ax * ax + ay * ay + az * az);
            if (len > MAX_AHEAD) {
                double s = MAX_AHEAD / len;
                ax *= s;
                ay *= s;
                az *= s;
                len = MAX_AHEAD;
            }
            lastAhead = len;
            return new double[]{ax, ay, az};
        }

        /** How far ahead the last prediction was (blocks). */
        public double lastAhead() {
            return lastAhead;
        }
    }
}
