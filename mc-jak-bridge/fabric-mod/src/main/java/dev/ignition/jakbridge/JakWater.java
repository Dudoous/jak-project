package dev.ignition.jakbridge;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;

/**
 * STEVE mode: Jak's water. Jak tells us the water surface height where Steve stands (from Jak's
 * own water volumes), and EntityWaterMixin makes Minecraft treat the player as being in water
 * below that height: swimming, slow sinking, drowning, putting out fire. Nothing is placed in the
 * world, so Jak's own water is what you see.
 */
public final class JakWater {
    private static volatile double surfaceY = Double.NaN; // Minecraft coordinates

    private JakWater() {
    }

    public static void setSurface(double y) {
        surfaceY = y;
    }

    public static void clear() {
        surfaceY = Double.NaN;
    }

    public static double surface() {
        return surfaceY;
    }

    private static boolean applies(Entity e) {
        return e instanceof PlayerEntity && !Double.isNaN(surfaceY) && JakBridgeClient.mode() == JakBridgeClient.Mode.STEVE;
    }

    /** Feet below the surface. */
    public static boolean touching(Entity e) {
        return applies(e) && e.getY() < surfaceY;
    }

    /** Eyes below the surface (same 0.11 margin Minecraft uses). */
    public static boolean submerged(Entity e) {
        return applies(e) && e.getEyeY() - 0.11111111 < surfaceY;
    }

    /** How deep the feet are, like Entity.getFluidHeight(WATER). */
    public static double depth(Entity e) {
        return applies(e) ? Math.max(0.0, surfaceY - e.getY()) : 0.0;
    }
}
