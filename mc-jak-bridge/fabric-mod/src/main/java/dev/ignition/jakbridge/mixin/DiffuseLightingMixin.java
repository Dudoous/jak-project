package dev.ignition.jakbridge.mixin;

import dev.ignition.jakbridge.JakRender;
import net.minecraft.client.render.DiffuseLighting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft shades entities with two fixed lights from above. In Jak's world they come from Jak's
 * sun and fill light instead, so Steve's lit side faces the same way as everything in Jak's picture.
 * In jakbridge.optional.mixins.json.
 */
@Mixin(DiffuseLighting.class)
public abstract class DiffuseLightingMixin {
    @Inject(method = "enableForLevel", at = @At("TAIL"), require = 0)
    private static void jakbridge$jakSun(CallbackInfo ci) {
        JakRender.applyShaderLights();
    }
}
