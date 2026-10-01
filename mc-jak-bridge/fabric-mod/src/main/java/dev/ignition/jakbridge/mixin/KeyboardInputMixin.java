package dev.ignition.jakbridge.mixin;

import dev.ignition.jakbridge.JakBridgeClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * In BODY mode Jak's physics moves the player, so Steve's own walking/jumping/sneaking is zeroed
 * after the keyboard is read. The mod still sees the raw keys and forwards them to Jak.
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin {
    // No target-method arguments captured, so this works whatever tick()'s parameters are.
    @Inject(method = "tick", at = @At("TAIL"))
    private void jakbridge$suppressWhileJakDrives(CallbackInfo ci) {
        if (!JakBridgeClient.isSuppressingMovement()) {
            return;
        }
        Input self = (Input) (Object) this;
        self.movementForward = 0.0f;
        self.movementSideways = 0.0f;
        self.pressingForward = false;
        self.pressingBack = false;
        self.pressingLeft = false;
        self.pressingRight = false;
        self.jumping = false;
        self.sneaking = false;
    }
}
