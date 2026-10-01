package dev.ignition.jakbridge.mixin;

import dev.ignition.jakbridge.JakBridgeClient;
import dev.ignition.jakbridge.JakSky;
import net.minecraft.client.render.Camera;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Right after Minecraft places its camera for a frame: send that (live) pose to Jak, take Jak's
 * newest picture, and draw Minecraft's world from the exact pose that picture was rendered from,
 * so Minecraft's blocks and Jak's world never slide against each other.
 *
 * In jakbridge.optional.mixins.json: if a Minecraft update renames something here, the game still
 * starts (the bridge then sends the camera from WorldRenderEvents.START, without view locking).
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Shadow
    protected abstract void setPos(double x, double y, double z);

    @Shadow
    public abstract Vec3d getPos();

    @Shadow
    public abstract float getYaw();

    @Shadow
    public abstract float getPitch();

    @Inject(method = "update", at = @At("TAIL"), require = 0)
    private void jakbridge$matchJaksPicture(CallbackInfo ci) {
        JakSky.Pose p = JakBridgeClient.onCameraUpdate(getPos(), getYaw(), getPitch());
        if (p != null) {
            setRotation(p.yaw, p.pitch);
            setPos(p.x, p.y, p.z);
        }
    }
}
