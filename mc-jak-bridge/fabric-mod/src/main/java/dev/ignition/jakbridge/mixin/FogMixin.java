package dev.ignition.jakbridge.mixin;

import dev.ignition.jakbridge.JakRender;
import net.minecraft.client.render.BackgroundRenderer;
import net.minecraft.client.render.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Far-away Minecraft blocks fade into Jak's fog (colour and distance), like Jak's own world around
 * them, instead of into Minecraft's sky-blue fog. In jakbridge.optional.mixins.json.
 */
@Mixin(BackgroundRenderer.class)
public abstract class FogMixin {
    @Inject(method = "applyFog", at = @At("TAIL"), require = 0)
    private static void jakbridge$jakFog(Camera camera, BackgroundRenderer.FogType fogType, float viewDistance,
                                         boolean thickFog, float tickDelta, CallbackInfo ci) {
        JakRender.applyFog(camera, "FOG_TERRAIN".equals(fogType.name()), viewDistance);
    }
}
