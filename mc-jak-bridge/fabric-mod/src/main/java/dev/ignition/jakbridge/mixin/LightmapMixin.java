package dev.ignition.jakbridge.mixin;

import dev.ignition.jakbridge.BridgeSocket;
import dev.ignition.jakbridge.JakBridgeClient;
import dev.ignition.jakbridge.JakLight;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Everything Minecraft draws in the world (Steve, mobs, your blocks, the item in your hand) is
 * multiplied by its lightmap. In Jak's world the lightmap is Jak's light where Steve is (sunny
 * day, sunset, night, caves...), see JakLight.fillLightmap.
 *
 * In jakbridge.optional.mixins.json: if a Minecraft update renames something, Minecraft keeps its
 * own lighting.
 */
@Mixin(LightmapTextureManager.class)
public abstract class LightmapMixin {
    @Shadow
    @Final
    private NativeImage image;

    @Shadow
    @Final
    private NativeImageBackedTexture texture;

    @Shadow
    private boolean dirty;

    @Shadow
    private float flickerIntensity;

    @Unique
    private boolean jakbridge$wasDirty;

    @Unique
    private boolean jakbridge$applied;

    @Unique
    private long jakbridge$lightSeen;

    @Unique
    private final int[] jakbridge$pixels = new int[256];

    @Inject(method = "update", at = @At("HEAD"), require = 0)
    private void jakbridge$beforeUpdate(float delta, CallbackInfo ci) {
        jakbridge$wasDirty = dirty;
    }

    @Inject(method = "update", at = @At("RETURN"), require = 0)
    private void jakbridge$jakLight(float delta, CallbackInfo ci) {
        BridgeSocket.Light light = JakLight.active();
        if (light == null) {
            if (jakbridge$applied) {
                jakbridge$applied = false;
                dirty = true; // Minecraft's own lightmap again next frame
            }
            return;
        }
        if (jakbridge$applied && !jakbridge$wasDirty && light.receivedNanos == jakbridge$lightSeen) return;
        jakbridge$lightSeen = light.receivedNanos;
        float gamma = MinecraftClient.getInstance().options.getGamma().getValue().floatValue();
        JakLight.fillLightmap(light.data, JakBridgeClient.config().jakLightStrength(), gamma, flickerIntensity,
                jakbridge$pixels);
        for (int sky = 0; sky < 16; sky++) {
            for (int block = 0; block < 16; block++) {
                image.setColor(block, sky, jakbridge$pixels[sky * 16 + block]);
            }
        }
        texture.upload();
        jakbridge$applied = true;
        JakBridgeClient.lightmapHookWorks();
    }
}
