package dev.ignition.jakbridge;

/**
 * What a triangle of Jak's level collision is made of: its "pat" (pat-surface in OpenGOAL's
 * goal_src/jak1/engine/collide/pat-h.gc), which the collision export writes before the faces that
 * use it ("usemtl pat_xxxxxxxx"). Older exports have none: everything is then plain stone ground.
 * No Minecraft dependencies.
 */
public final class JakSurface {
    private JakSurface() {
    }

    /** Jak goes through it: it only stops Jak's camera. Not solid for Steve either. */
    public static boolean noEntity(int pat) {
        return (pat & 1) != 0;
    }

    /** 0 ground, 1 wall, 2 obstacle. */
    public static int mode(int pat) {
        return (pat >>> 3) & 7;
    }

    /** pat-material: 0 stone, 1 ice, 2 quicksand, 3 waterbottom, 4 tar, 5 sand, 6 wood, 7 grass, ... */
    public static int material(int pat) {
        return (pat >>> 6) & 63;
    }

    /** pat-event: 0 none, 1 deadly, 2 endlessfall, 3 burn, 4 deadlyup, 5 burnup, 6 melt. */
    public static int event(int pat) {
        return (pat >>> 14) & 63;
    }

    // what Steve's footsteps sound like (JakGroundBlock.Surface is in the same order)
    public static final int STONE = 0;
    public static final int GRASS = 1;
    public static final int SAND = 2;
    public static final int WOOD = 3;
    public static final int METAL = 4;
    public static final int SNOW = 5;
    public static final int DIRT = 6;
    public static final int MUD = 7;
    public static final int ICE = 8;
    public static final int KINDS = 9;

    // by pat-material: stone ice quicksand waterbottom tar sand wood grass pcmetal snow deepsnow
    // hotcoals lava crwood gravel dirt metal straw tube swamp stopproj rotate neutral
    private static final int[] BY_MATERIAL = {STONE, ICE, SAND, DIRT, MUD, SAND, WOOD, GRASS, METAL, SNOW, SNOW,
            STONE, STONE, WOOD, DIRT, DIRT, METAL, GRASS, METAL, MUD, STONE, STONE, STONE};

    public static int soundKind(int pat) {
        int m = material(pat);
        return m < BY_MATERIAL.length ? BY_MATERIAL[m] : STONE;
    }

    /** "pat_0000c040" -> the pat, 0 if it can't be read. */
    public static int parse(String name) {
        String s = name.trim();
        int i = s.lastIndexOf('_');
        try {
            return (int) Long.parseLong(i >= 0 ? s.substring(i + 1) : s, 16);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
