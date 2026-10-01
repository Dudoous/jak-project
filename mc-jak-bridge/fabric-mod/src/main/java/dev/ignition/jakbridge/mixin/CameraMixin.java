package dev.ignition.jakbridge.mixin;

import dev.ignition.jakbridge.JakBridgeClient;
import dev.ignition.jakbridge.JakHost;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** JAK mode: right before Minecraft computes its camera, put Steve exactly where Jak's camera is. */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Inject(method = "update", at = @At("HEAD"), require = 0)
    private void jakbridge$followJak(CallbackInfo ci) {
        JakHost.applyToPlayer(MinecraftClient.getInstance().player, JakBridgeClient.collision().yOffset());
    }
}
