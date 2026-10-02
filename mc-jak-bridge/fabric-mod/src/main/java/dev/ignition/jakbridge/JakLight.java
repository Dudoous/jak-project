package dev.ignition.jakbridge;

/**
 * Jak's light on Minecraft's things. Jak sends the light he would be lit with where Steve stands
 * (LIGHT packet: his sun, fill and bounce lights, ambient light, fog and clock, blended by his
 * mood system between levels and times of day, darker in caves). Minecraft then:
 *  - colours its lightmap with it (everything Minecraft draws in the world is multiplied by the
 *    lightmap: Steve, mobs, your blocks, the item in your hand) - LightmapMixin;
 *  - shades entities from the direction of Jak's sun instead of Minecraft's fixed lights -
 *    DiffuseLightingMixin;
 *  - fades its blocks into Jak's fog colour at Jak's fog distance - FogMixin.
 * The math here has no Minecraft dependencies (unit tested); the mixins call it.
 */
public final class JakLight {
    // LIGHT packet layout (see PROTOCOL.md)
    public static final int DIR0 = 0, COLOR0 = 3, DIR1 = 6, COLOR1 = 9, DIR2 = 12, COLOR2 = 15;
    public static final int AMBIENT = 18, FOG_COLOR = 21, FOG_START = 24, FOG_END = 25, FOG_AMOUNT = 26;
    public static final int HOUR = 27, GROUP = 28;

    private static final long TIMEOUT_NANOS = 1_000_000_000L;
    /** Never fully black: Minecraft's own darkest is about this. */
    private static final float MIN_LIGHT = 0.08f;

    private JakLight() {
    }

    /** The newest LIGHT packet if it's recent, else null. */
    public static BridgeSocket.Light fresh() {
        BridgeSocket b = JakBridgeClient.bridge();
        BridgeSocket.Light l = b == null ? null : b.latestLight();
        if (l == null || System.nanoTime() - l.receivedNanos > TIMEOUT_NANOS) return null;
        return l;
    }

    /** Jak's light if Minecraft is playing in Jak's world with jak_light on, else null. */
    public static BridgeSocket.Light active() {
        if (!JakBridgeClient.inJakWorld() || !JakBridgeClient.config().jakLight()) return null;
        return fresh();
    }

    private static float luminance(float[] d, int i) {
        return 0.3f * d[i] + 0.59f * d[i + 1] + 0.11f * d[i + 2];
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * The colour Jak's light gives something standing here, facing the sun (Jak's merc renderer:
     * ambient plus each light times how much the surface faces it): ambient + sun + some of the fill
     * and bounce lights, as Minecraft brightness 0-1 per channel.
     */
    public static void baseColor(float[] d, float strength, float[] rgb) {
        for (int c = 0; c < 3; c++) {
            float v = d[AMBIENT + c] + d[COLOR0 + c] + 0.5f * d[COLOR1 + c] + 0.25f * d[COLOR2 + c];
            rgb[c] = clamp(v * strength, MIN_LIGHT, 1f);
        }
    }

    /** Minecraft's light level curve (LightmapTextureManager.getBrightness with no ambient light). */
    static float levelBrightness(int level) {
        float f = level / 15f;
        return f / (4f - 3f * f);
    }

    /**
     * The 16 x 16 lightmap (index sky * 16 + block, ABGR like NativeImage): Jak's light where the
     * sky reaches (fading under your own roofs like Minecraft's sky light does), plus Minecraft's
     * torch light, with Minecraft's brightness setting (gamma) applied the way Minecraft does.
     */
    public static void fillLightmap(float[] d, float strength, float gamma, float flicker, int[] out) {
        float[] base = new float[3];
        baseColor(d, strength, base);
        float torch = flicker + 1.5f;
        float[] c = new float[3];
        for (int sky = 0; sky < 16; sky++) {
            float s = levelBrightness(sky);
            for (int block = 0; block < 16; block++) {
                float h = levelBrightness(block) * torch;
                c[0] = base[0] * s + h;
                c[1] = base[1] * s + h * ((h * 0.6f + 0.4f) * 0.6f + 0.4f);
                c[2] = base[2] * s + h * (h * h * 0.6f + 0.4f);
                for (int k = 0; k < 3; k++) {
                    float v = clamp(c[k], 0f, 1f);
                    float inv = 1f - v;
                    float bright = 1f - inv * inv * inv * inv;
                    v = v + (bright - v) * Math.max(0f, gamma);
                    v = v + (0.75f - v) * 0.04f;
                    c[k] = clamp(v, 0f, 1f);
                }
                int r = (int) (c[0] * 255f), g = (int) (c[1] * 255f), b = (int) (c[2] * 255f);
                out[sky * 16 + block] = 0xFF000000 | (b << 16) | (g << 8) | r;
            }
        }
    }

    /**
     * Directions (Minecraft = Jak axes, towards the light) for Minecraft's two entity lights: Jak's
     * sun and fill light, each scaled by how strong it is compared to the stronger one. Returns false
     * if Jak has no directional light here (then Minecraft's flat 40% ambient shading stays).
     */
    public static boolean shaderLights(float[] d, float[] out) {
        float l0 = luminance(d, COLOR0), l1 = luminance(d, COLOR1);
        float max = Math.max(l0, l1);
        if (!(max > 1e-4f)) return false;
        return direction(d, DIR0, l0 / max, out, 0) | direction(d, DIR1, l1 / max, out, 3);
    }

    private static boolean direction(float[] d, int i, float weight, float[] out, int o) {
        float x = d[i], y = d[i + 1], z = d[i + 2];
        float len = (float) Math.sqrt(x * x + y * y + z * z);
        if (!(len > 1e-4f) || !(weight > 0f)) {
            out[o] = out[o + 1] = out[o + 2] = 0f;
            return false;
        }
        float k = clamp(weight, 0f, 1f) / len;
        out[o] = x * k;
        out[o + 1] = y * k;
        out[o + 2] = z * k;
        return true;
    }

    /**
     * Minecraft's linear fog for Jak's: Jak's fog starts at fog start and reaches "fog amount" (it
     * never fogs completely) at fog end. Minecraft fogs completely at its end, so its end is put
     * where Jak's fog line would reach full fog, but never further than Minecraft's own (so chunks
     * at the edge of your render distance don't pop in). out = start, end, r, g, b.
     */
    public static boolean fog(float[] d, float vanillaEnd, float[] out) {
        float start = d[FOG_START], end = d[FOG_END], amount = d[FOG_AMOUNT];
        if (!(end > start) || !(start >= 0f) || !(amount > 0.02f)) return false;
        float mcEnd = Math.min(start + (end - start) / clamp(amount, 0.05f, 1f), vanillaEnd);
        out[0] = Math.min(start, mcEnd * 0.9f);
        out[1] = mcEnd;
        out[2] = clamp(d[FOG_COLOR], 0f, 1f);
        out[3] = clamp(d[FOG_COLOR + 1], 0f, 1f);
        out[4] = clamp(d[FOG_COLOR + 2], 0f, 1f);
        return true;
    }

    /** For the F9 overlay. */
    public static String describe() {
        BridgeSocket.Light l = fresh();
        if (l == null) return "Jak's light: none";
        float[] d = l.data;
        float[] base = new float[3];
        baseColor(d, JakBridgeClient.config().jakLightStrength(), base);
        int hour = (int) d[HOUR], minute = (int) ((d[HOUR] - hour) * 60);
        return String.format("Jak's light: %.2f %.2f %.2f (group %d), fog %.0f-%.0f m, Jak's clock %02d:%02d%s",
                base[0], base[1], base[2], (int) d[GROUP], d[FOG_START], d[FOG_END], hour, minute,
                JakBridgeClient.config().jakLight() ? "" : " (jak_light off)");
    }
}
