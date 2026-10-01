package dev.ignition.jakbridge.mixin;

import dev.ignition.jakbridge.JakBridgeClient;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.explosion.Explosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** TNT, creepers and other Minecraft explosions also blow up Jak's enemies. */
@Mixin(Explosion.class)
public abstract class ExplosionMixin {
    @Inject(method = "collectBlocksAndDamageEntities", at = @At("HEAD"), require = 0)
    private void jakbridge$tellJak(CallbackInfo ci) {
        Vec3d pos = ((Explosion) (Object) this).getPosition();
        JakBridgeClient.onExplosion(pos.x, pos.y, pos.z);
    }
}
