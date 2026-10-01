package dev.ignition.jakbridge.mixin;

import dev.ignition.jakbridge.JakHost;
import dev.ignition.jakbridge.JakSky;
import net.minecraft.client.render.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The sky is where the two games meet:
 *  - JAK mode: transparent background + Jak's collision in the depth buffer (JakHost).
 *  - STEVE mode: Jak's rendered frame (JakSky).
 * Clouds are hidden in both. require = 0: a renamed method can't crash Minecraft.
 */
@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {
    @Inject(method = "renderSky", at = @At("HEAD"), cancellable = true, require = 0)
    private void jakbridge$replaceSky(CallbackInfo ci) {
        if (JakHost.replaceSky() || JakSky.drawInsteadOfSky()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true, require = 0)
    private void jakbridge$noClouds(CallbackInfo ci) {
        if (JakHost.active() || JakSky.active()) {
            ci.cancel();
        }
    }
}
