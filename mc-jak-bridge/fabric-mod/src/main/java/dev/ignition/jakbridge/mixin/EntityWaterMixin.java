package dev.ignition.jakbridge.mixin;

import dev.ignition.jakbridge.JakWater;
import net.minecraft.entity.Entity;
import net.minecraft.fluid.Fluid;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.registry.tag.TagKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * STEVE mode: Jak's water counts as water for the player (see JakWater). Runs on both the client
 * (movement, swimming) and the integrated server (drowning). require = 0 so a renamed method can't
 * crash Minecraft; you'd just not be able to swim.
 */
@Mixin(Entity.class)
public abstract class EntityWaterMixin {
    @Inject(method = "isTouchingWater", at = @At("HEAD"), cancellable = true, require = 0)
    private void jakbridge$touchingJakWater(CallbackInfoReturnable<Boolean> cir) {
        if (JakWater.touching((Entity) (Object) this)) cir.setReturnValue(true);
    }

    @Inject(method = "isInsideWaterOrBubbleColumn", at = @At("HEAD"), cancellable = true, require = 0)
    private void jakbridge$insideJakWater(CallbackInfoReturnable<Boolean> cir) {
        if (JakWater.touching((Entity) (Object) this)) cir.setReturnValue(true);
    }

    @Inject(method = "isSubmergedInWater", at = @At("HEAD"), cancellable = true, require = 0)
    private void jakbridge$submergedInJakWater(CallbackInfoReturnable<Boolean> cir) {
        if (JakWater.submerged((Entity) (Object) this)) cir.setReturnValue(true);
    }

    @Inject(method = "isSubmergedIn", at = @At("HEAD"), cancellable = true, require = 0)
    private void jakbridge$submergedInJakFluid(TagKey<Fluid> fluidTag, CallbackInfoReturnable<Boolean> cir) {
        if (fluidTag == FluidTags.WATER && JakWater.submerged((Entity) (Object) this)) cir.setReturnValue(true);
    }

    @Inject(method = "getFluidHeight", at = @At("HEAD"), cancellable = true, require = 0)
    private void jakbridge$jakWaterDepth(TagKey<Fluid> fluid, CallbackInfoReturnable<Double> cir) {
        if (fluid == FluidTags.WATER && JakWater.touching((Entity) (Object) this)) {
            cir.setReturnValue(JakWater.depth((Entity) (Object) this));
        }
    }
}
