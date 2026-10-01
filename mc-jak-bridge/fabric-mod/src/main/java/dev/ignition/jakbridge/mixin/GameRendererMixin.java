package dev.ignition.jakbridge.mixin;

import dev.ignition.jakbridge.JakBridgeClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * In Jak's world Minecraft's projection must be exactly Jak's, or Minecraft's blocks (and the
 * holes you dig) swim over Jak's picture as you move: no view bobbing, no tilting when hurt, and
 * the plain FOV setting (no zooming out when running fast, sprinting or swimming).
 *
 * In jakbridge.optional.mixins.json; if it can't apply, JakBridgeClient switches the matching
 * Minecraft options off instead.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "bobView", at = @At("HEAD"), cancellable = true, require = 0)
    private void jakbridge$noBobbing(CallbackInfo ci) {
        JakBridgeClient.bobHookWorks();
        if (JakBridgeClient.inJakWorld()) ci.cancel();
    }

    @Inject(method = "tiltViewWhenHurt", at = @At("HEAD"), cancellable = true, require = 0)
    private void jakbridge$noHurtTilt(CallbackInfo ci) {
        if (JakBridgeClient.inJakWorld()) ci.cancel();
    }

    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true, require = 0)
    private void jakbridge$plainFov(Camera camera, float tickDelta, boolean changingFov, CallbackInfoReturnable<Double> cir) {
        JakBridgeClient.fovHookWorks();
        if (changingFov && JakBridgeClient.inJakWorld()) {
            cir.setReturnValue((double) MinecraftClient.getInstance().options.getFov().getValue());
        }
    }
}
