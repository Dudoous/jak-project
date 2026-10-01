package dev.ignition.jakbridge.mixin;

import dev.ignition.jakbridge.JakBridgeClient;
import dev.ignition.jakbridge.JakSky;
import net.minecraft.client.render.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * STEVE mode: Jak's world replaces the sky, and clouds are hidden so they don't float in front of
 * it. require = 0: if these methods ever change name, the mod still loads (you'd just see the
 * normal sky), instead of crashing Minecraft.
 */
@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {
    @Inject(method = "renderSky", at = @At("HEAD"), cancellable = true, require = 0)
    private void jakbridge$jakWorldAsSky(CallbackInfo ci) {
        if (JakSky.drawInsteadOfSky()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true, require = 0)
    private void jakbridge$noCloudsOverJak(CallbackInfo ci) {
        if (JakBridgeClient.mode() == JakBridgeClient.Mode.STEVE && JakSky.active()) {
            ci.cancel();
        }
    }
}
