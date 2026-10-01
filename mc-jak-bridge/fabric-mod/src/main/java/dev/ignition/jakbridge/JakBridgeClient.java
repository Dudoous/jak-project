package dev.ignition.jakbridge;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Client side of the Jak x Minecraft passthrough.
 *
 * Every rendered frame, sends the Minecraft camera (eye position, yaw, pitch, FOV) and the
 * player's movement input to the OpenGOAL runtime, which uses it to drive Jak's camera.
 *
 * F8 toggles streaming. Target: Minecraft 1.21.1, Yarn mappings.
 */
public final class JakBridgeClient implements ClientModInitializer {
    public static final String MOD_ID = "jakbridge";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    private static BridgeSocket bridge;
    private static boolean streaming = true;
    private static KeyBinding toggleKey;
    private static long lastErrorLogNanos = 0;

    @Override
    public void onInitializeClient() {
        int port = BridgeSocket.DEFAULT_PORT;
        String env = System.getenv("OPENGOAL_MC_PORT");
        if (env != null) {
            try {
                port = Integer.parseInt(env.trim());
            } catch (NumberFormatException ignored) {
                LOG.warn("Ignoring bad OPENGOAL_MC_PORT={}", env);
            }
        }

        try {
            bridge = new BridgeSocket(port);
            LOG.info("Jak bridge sending to UDP 127.0.0.1:{}", port);
        } catch (IOException e) {
            LOG.error("Could not open Jak bridge socket", e);
            return;
        }

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.jakbridge.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F8, "category.jakbridge"));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.wasPressed()) {
                streaming = !streaming;
                if (client.player != null) {
                    client.player.sendMessage(Text.literal(
                            "Jak bridge " + (streaming ? "ON" : "OFF")), true);
                }
            }
        });

        // Per-frame, after the world is rendered, so the camera is the interpolated one we saw.
        WorldRenderEvents.END.register(context -> sendFrame(context.camera()));

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (bridge != null) {
                bridge.close();
            }
        });
    }

    private static void sendFrame(Camera camera) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!streaming || bridge == null || client.player == null || camera == null) {
            return;
        }

        Vec3d pos = camera.getPos();
        float yaw = MathHelper.wrapDegrees(camera.getYaw());
        float pitch = camera.getPitch();
        GameOptions options = client.options;
        // Base FOV only. Set "FOV Effects" to 0% so sprinting/potions don't desync the two views.
        float vfov = options.getFov().getValue().floatValue();

        Input input = client.player.input;
        float forward = input.movementForward;
        float strafe = -input.movementSideways; // Minecraft: +sideways = left. Protocol: + = right.

        int buttons = 0;
        if (input.jumping) buttons |= BridgeSocket.BUTTON_JUMP;
        if (input.sneaking) buttons |= BridgeSocket.BUTTON_SNEAK;
        if (options.sprintKey.isPressed()) buttons |= BridgeSocket.BUTTON_SPRINT;
        if (options.attackKey.isPressed()) buttons |= BridgeSocket.BUTTON_ATTACK;
        if (options.useKey.isPressed()) buttons |= BridgeSocket.BUTTON_USE;

        int flags = BridgeSocket.FLAG_IN_WORLD;
        if (client.currentScreen != null) flags |= BridgeSocket.FLAG_SCREEN_OPEN;

        try {
            bridge.sendCamera(pos.x, pos.y, pos.z, yaw, pitch, vfov, forward, strafe, buttons, flags);
        } catch (IOException e) {
            long now = System.nanoTime();
            if (now - lastErrorLogNanos > 5_000_000_000L) { // don't spam the log every frame
                LOG.warn("Jak bridge send failed: {}", e.toString());
                lastErrorLogNanos = now;
            }
        }
    }
}
