package dev.ignition.jakbridge;

import net.fabricmc.api.ModInitializer;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

/**
 * Runs before the client entrypoint (JakBridgeClient): registers the block Jak's ground is made of,
 * which the integrated server needs too.
 */
public final class JakBridgeMod implements ModInitializer {
    /** Jak's ground (see JakGroundBlock), null if registering it failed. */
    public static volatile JakGroundBlock jakGround;

    @Override
    public void onInitialize() {
        try {
            jakGround = Registry.register(Registries.BLOCK, Identifier.of("jakbridge", "jak_ground"), new JakGroundBlock());
        } catch (RuntimeException e) {
            JakBridgeClient.LOG.error("Could not register Jak's ground block; using barrier blocks (1 m steps)", e);
            jakGround = null;
        }
    }
}
