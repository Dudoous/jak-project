package dev.ignition.jakbridge.mixin;

import dev.ignition.jakbridge.JakHost;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * JAK mode: you click in Jak's window, so Minecraft's own mouse isn't captured. Holding a click to
 * mine needs Minecraft to think it is.
 */
@Mixin(Mouse.class)
public abstract class MouseMixin {
    @Inject(method = "isCursorLocked", at = @At("HEAD"), cancellable = true, require = 0)
    private void jakbridge$lockedWhileJakDrives(CallbackInfoReturnable<Boolean> cir) {
        if (JakHost.active()) cir.setReturnValue(true);
    }
}
