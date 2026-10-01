package dev.ignition.jakbridge.mixin;

import dev.ignition.jakbridge.JakDepthProxy;
import dev.ignition.jakbridge.JakSky;
import net.minecraft.client.render.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Jak's world replaces Minecraft's sky: Jak's rendered picture as the background, then Jak's
 * level shape in the depth buffer so Minecraft blocks sit inside Jak's world. Clouds are hidden.
 * require = 0: a renamed method can't crash Minecraft (you'd just see the normal sky).
 */
@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {
    @Inject(method = "renderSky", at = @At("HEAD"), cancellable = true, require = 0)
    private void jakbridge$jakWorldAsSky(CallbackInfo ci) {
        if (JakSky.drawInsteadOfSky()) {
            JakDepthProxy.draw();
            ci.cancel();
        }
    }

    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true, require = 0)
    private void jakbridge$noCloudsOverJak(CallbackInfo ci) {
        if (JakSky.active()) {
            ci.cancel();
        }
    }
}
