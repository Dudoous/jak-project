package dev.ignition.jakbridge;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
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
 * Every rendered frame, sends the Minecraft camera (eye position, yaw, pitch, FOV) and the raw
 * movement keys to the OpenGOAL runtime.
 *
 * BODY mode (default, F9 toggles): Jak's physics is the source of truth. WASD/jump/sneak/clicks
 * drive Jak, Steve's own movement is switched off (see KeyboardInputMixin), and Steve is teleported
 * every tick to wherever Jak is. FREE mode: Steve moves normally and Jak's camera follows him.
 *
 * F8 toggles streaming entirely. Target: Minecraft 1.21.1, Yarn mappings.
 */
public final class JakBridgeClient implements ClientModInitializer {
    public static final String MOD_ID = "jakbridge";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    /** JAK_STATE packets older than this are ignored (Jak closed, paused in a menu, ...). */
    private static final long JAK_STATE_TIMEOUT_NANOS = 500_000_000L;

    private static BridgeSocket bridge;
    private static boolean streaming = true;
    private static boolean bodyMode = true;
    private static KeyBinding toggleKey;
    private static KeyBinding bodyModeKey;
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
        bodyModeKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.jakbridge.body_mode", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F9, "category.jakbridge"));

        ClientTickEvents.END_CLIENT_TICK.register(JakBridgeClient::onEndTick);

        // Per-frame, after the world is rendered, so the camera is the interpolated one we saw.
        WorldRenderEvents.END.register(context -> sendFrame(context.camera()));

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (bridge != null) {
                bridge.close();
            }
        });
    }

    /** Latest JAK_STATE if it is recent and says Jak is in BODY mode, else null. */
    private static BridgeSocket.JakState freshBodyState() {
        if (bridge == null) {
            return null;
        }
        BridgeSocket.JakState state = bridge.latestJakState();
        if (state == null || System.nanoTime() - state.receivedNanos > JAK_STATE_TIMEOUT_NANOS) {
            return null;
        }
        if ((((int) state.data[5]) & BridgeSocket.JAK_FLAG_BODY) == 0) {
            return null;
        }
        return state;
    }

    /** Used by KeyboardInputMixin: while Jak is driving the body, Steve must not walk by himself. */
    public static boolean isSuppressingMovement() {
        return streaming && bodyMode && freshBodyState() != null;
    }

    private static void onEndTick(MinecraftClient client) {
        while (toggleKey.wasPressed()) {
            streaming = !streaming;
            say(client, "Jak bridge " + (streaming ? "ON" : "OFF"));
        }
        while (bodyModeKey.wasPressed()) {
            bodyMode = !bodyMode;
            say(client, bodyMode
                    ? "Jak bridge: BODY mode (you move with Jak's physics)"
                    : "Jak bridge: FREE mode (Jak's camera follows Steve)");
        }

        ClientPlayerEntity player = client.player;
        if (player == null || !isSuppressingMovement()) {
            return;
        }
        BridgeSocket.JakState state = freshBodyState();
        if (state == null) {
            return;
        }
        // data[0..2] = where Steve's eyes should be. Convert to feet using the current eye offset
        // (which is lower while sneaking).
        double eyeOffset = player.getEyeY() - player.getY();
        player.setPosition(state.data[0], state.data[1] - eyeOffset, state.data[2]);
        player.setVelocity(Vec3d.ZERO);
        player.fallDistance = 0.0f; // Jak handles falling; never take Minecraft fall damage
        if (player.getAbilities().allowFlying) {
            player.getAbilities().flying = true; // no Minecraft gravity fighting the teleport
        }
    }

    private static void say(MinecraftClient client, String message) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal(message), true);
        }
    }

    private static float axis(KeyBinding positive, KeyBinding negative) {
        return (positive.isPressed() ? 1f : 0f) - (negative.isPressed() ? 1f : 0f);
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

        // Raw keys rather than player.input: in BODY mode the player's own input is zeroed.
        float forward = axis(options.forwardKey, options.backKey);
        float strafe = axis(options.rightKey, options.leftKey); // protocol: + = right

        int buttons = 0;
        if (options.jumpKey.isPressed()) buttons |= BridgeSocket.BUTTON_JUMP;
        if (options.sneakKey.isPressed()) buttons |= BridgeSocket.BUTTON_SNEAK;
        if (options.sprintKey.isPressed()) buttons |= BridgeSocket.BUTTON_SPRINT;
        if (options.attackKey.isPressed()) buttons |= BridgeSocket.BUTTON_ATTACK;
        if (options.useKey.isPressed()) buttons |= BridgeSocket.BUTTON_USE;

        int flags = BridgeSocket.FLAG_IN_WORLD;
        if (client.currentScreen != null) {
            flags |= BridgeSocket.FLAG_SCREEN_OPEN;
            forward = 0f; // typing in chat / inventory must not walk Jak around
            strafe = 0f;
            buttons = 0;
        }
        if (bodyMode) flags |= BridgeSocket.FLAG_BODY_MODE;

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
