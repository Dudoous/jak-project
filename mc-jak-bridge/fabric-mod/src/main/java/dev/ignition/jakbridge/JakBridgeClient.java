package dev.ignition.jakbridge;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.block.Blocks;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.util.ActionResult;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
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
 * Client side of JakCraft (Jak x Minecraft passthrough).
 *
 * JAK mode (default): you play Jak and Daxter in Jak's window. Steve is your body's "skin": his
 * hand, held item, hotbar, blocks and Minecraft's mobs are rendered by Minecraft from Jak's camera
 * and drawn on top of Jak's frame (see JakHost); clicks / wheel / 1-9 in Jak's window drive Minecraft.
 * STEVE mode: you play Minecraft inside Jak's world. Minecraft does all the gameplay
 * (movement, blocks, hotbar, mobs); Jak renders his world from Steve's eyes and that image replaces
 * Minecraft's sky; Jak's level collision becomes invisible barrier blocks so Steve walks on Jak's
 * ground. Jak himself is hidden.
 * BODY mode: Minecraft's keys drive Jak with Jak's physics (Steve follows Jak).
 * FREE mode: Jak's camera follows Steve, no collision.
 *
 * F9 cycles the modes, F8 turns the bridge on/off, F7 puts Steve back where Jak was.
 * Target: Minecraft 1.21.1, Yarn mappings.
 */
public final class JakBridgeClient implements ClientModInitializer {
    public static final String MOD_ID = "jakbridge";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    public enum Mode {
        JAK("JAK mode: play in Jak's window with Steve's Minecraft gameplay on top"),
        STEVE("STEVE mode: Minecraft gameplay in Jak's world, in the Minecraft window"),
        BODY("BODY mode: your keys drive Jak"),
        FREE("FREE mode: Jak's camera follows Steve");

        final String description;

        Mode(String description) {
            this.description = description;
        }

        Mode next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    /** JAK_STATE packets older than this are ignored (Jak closed, paused in a menu, ...). */
    private static final long JAK_STATE_TIMEOUT_NANOS = 500_000_000L;

    private static BridgeSocket bridge;
    private static JakCraftConfig config;
    private static CollisionPlacer collision;
    private static boolean streaming = true;
    private static volatile Mode mode = Mode.JAK;
    private static KeyBinding toggleKey;
    private static KeyBinding modeKey;
    private static KeyBinding respawnKey;
    private static long lastErrorLogNanos = 0;
    private static int lastSpawnActivation = -1;
    private static boolean toldStatus = false;
    private static String lastStatusShown = "";
    private static String lastWarningShown = null;
    private static float lastJakDamage = -1f;

    private static boolean playerHeldByJak = false;

    public static Mode mode() {
        return mode;
    }

    public static BridgeSocket bridge() {
        return bridge;
    }

    public static CollisionPlacer collision() {
        return collision;
    }

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

        config = new JakCraftConfig(FabricLoader.getInstance().getConfigDir());
        collision = new CollisionPlacer(config);
        collision.register();
        collision.startLoading();
        JakSky.init();

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.jakbridge.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F8, "category.jakbridge"));
        modeKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.jakbridge.mode", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F9, "category.jakbridge"));
        respawnKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.jakbridge.respawn", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F7, "category.jakbridge"));

        ClientTickEvents.START_CLIENT_TICK.register(client -> JakHost.injectInput(client, bridge));
        ClientTickEvents.END_CLIENT_TICK.register(JakBridgeClient::onEndTick);
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            // JAK mode: you play in Jak's window, so Minecraft must keep running unfocused
            client.options.pauseOnLostFocus = false;
        });
        WorldRenderEvents.START.register(JakHost::rememberMatrices);
        HudRenderCallback.EVENT.register((drawContext, tickCounter) -> {
            if (JakHost.active()) {
                drawContext.draw(); // flush the HUD so it's in the frame we copy
                JakHost.exportFrame();
            }
        });

        // Jak's ground is barrier blocks: don't let a left click (creative) knock holes in it.
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) ->
                mode == Mode.STEVE && world.getBlockState(pos).isOf(Blocks.BARRIER)
                        ? ActionResult.FAIL : ActionResult.PASS);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("jakcraft").executes(ctx -> {
                    for (String line : statusLines(MinecraftClient.getInstance())) {
                        ctx.getSource().sendFeedback(Text.literal(line));
                    }
                    return 1;
                })));

        // Per-frame, after the world is rendered, so the camera is the interpolated one we saw.
        WorldRenderEvents.END.register(context -> sendFrame(context.camera()));

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (bridge != null) {
                bridge.close();
            }
        });
    }

    /** Latest JAK_STATE if it is recent and has the given flag, else null. */
    private static BridgeSocket.JakState freshState(int flag) {
        if (bridge == null) {
            return null;
        }
        BridgeSocket.JakState state = bridge.latestJakState();
        if (state == null || System.nanoTime() - state.receivedNanos > JAK_STATE_TIMEOUT_NANOS) {
            return null;
        }
        if ((((int) state.data[5]) & flag) == 0) {
            return null;
        }
        return state;
    }

    /** /jakcraft: everything needed to tell what's wrong. */
    private static java.util.List<String> statusLines(MinecraftClient client) {
        java.util.List<String> out = new java.util.ArrayList<>();
        out.add("[JakCraft] mode " + mode + (streaming ? "" : " (bridge OFF - press F8)"));
        BridgeSocket.JakState any = bridge == null ? null : bridge.latestJakState();
        boolean jakTalking = any != null && System.nanoTime() - any.receivedNanos < JAK_STATE_TIMEOUT_NANOS;
        out.add("Jak game: " + (jakTalking ? "connected" : "NOT connected - start play.bat and load your save"));
        if (mode == Mode.JAK) {
            out.add("Jak camera/body: " + (JakHost.active() ? "receiving (play in Jak's window)" : "not receiving - get into gameplay in Jak"));
        } else {
            out.add("Jak picture: " + (JakSky.active() ? "receiving" : "not receiving"));
        }
        out.add("JakCraft folder: " + (config.jakcraftDir() == null ? "unknown (run play.bat once)" : config.jakcraftDir()));
        out.add("Collision: " + collision.status());
        if (collision.worldWarning() != null) out.add("World: " + collision.worldWarning());
        if (client.player != null) {
            out.add(String.format("Steve at %.1f %.1f %.1f (Jak meters), Jak water surface %s",
                    client.player.getX(), client.player.getY() - collision.yOffset(), client.player.getZ(),
                    Double.isNaN(JakWater.surface()) ? "none" : String.format("%.1f", JakWater.surface())));
        }
        return out;
    }

    /** Called from ExplosionMixin on the server thread. */
    public static void onExplosion(double x, double y, double z) {
        if (bridge == null || !streaming || mode != Mode.STEVE) return;
        try {
            bridge.sendEvent(BridgeSocket.EVENT_EXPLOSION, (float) x, (float) (y - collision.yOffset()), (float) z, 6f, 4f);
        } catch (IOException e) {
            LOG.warn("Could not send explosion to Jak: {}", e.toString());
        }
    }

    /** Used by KeyboardInputMixin: in BODY mode Jak drives the body, Steve must not walk by himself. */
    public static boolean isSuppressingMovement() {
        if (!streaming) return false;
        if (mode == Mode.JAK) return JakHost.active();
        return mode == Mode.BODY && freshState(BridgeSocket.JAK_FLAG_BODY) != null;
    }

    private static void onEndTick(MinecraftClient client) {
        while (toggleKey.wasPressed()) {
            streaming = !streaming;
            say(client, "Jak bridge " + (streaming ? "ON" : "OFF"));
        }
        while (modeKey.wasPressed()) {
            mode = mode.next();
            lastSpawnActivation = -1;
            say(client, mode.description);
        }

        ClientPlayerEntity player = client.player;
        if (player == null) {
            toldStatus = false;
            lastStatusShown = ""; // show the collision status again when joining a world
            return;
        }

        // collision status: progress on the action bar, the final result once in chat
        String status = collision.status();
        if ((mode == Mode.STEVE || mode == Mode.JAK) && !status.equals(lastStatusShown)) {
            boolean finalResult = collision.loaded() || !status.startsWith("loading");
            player.sendMessage(Text.literal("[JakCraft] " + status), !finalResult);
            toldStatus = true;
            lastStatusShown = status;
        }
        while (respawnKey.wasPressed()) {
            lastSpawnActivation = -1; // next STEVE state puts Steve back where Jak started
            say(client, "Back to where Jak was standing");
        }

        String warning = collision.worldWarning();
        if (warning != null && !warning.equals(lastWarningShown)) {
            player.sendMessage(Text.literal("[JakCraft] " + warning), false);
        }
        lastWarningShown = warning;

        // JAK mode: Steve rides along with Jak (also updated every frame by CameraMixin)
        boolean jak = streaming && JakHost.active();
        if (jak) {
            JakHost.applyToPlayer(player, collision.yOffset());
            playerHeldByJak = true;
        } else if (playerHeldByJak) {
            JakHost.releasePlayer(player);
            playerHeldByJak = false;
        }

        boolean steve = streaming && mode == Mode.STEVE && freshState(BridgeSocket.JAK_FLAG_STEVE) != null;
        collision.setSteveActive(steve || jak);
        if (!steve) {
            JakWater.clear();
        }
        if (steve) {
            BridgeSocket.JakState s = freshState(BridgeSocket.JAK_FLAG_STEVE);
            // data[3]: total damage Jak's enemies have done to Steve (Minecraft health points)
            float jakDamage = s.data[3];
            if (lastJakDamage >= 0f && jakDamage > lastJakDamage) {
                collision.addDamage(jakDamage - lastJakDamage);
            }
            lastJakDamage = jakDamage;
            // data[7]: Jak's water surface here (Jak meters), or very negative for none
            if (s.data[7] > -50000f) {
                JakWater.setSurface(s.data[7] + collision.yOffset());
            } else {
                JakWater.clear();
            }
            int activation = (int) s.data[6];
            if (activation != lastSpawnActivation && collision.loaded()) {
                // Jak just handed over: put Steve where Jak was standing.
                lastSpawnActivation = activation;
                collision.requestSpawn(s.data[0], s.data[1], s.data[2]);
            }
        }

        if (!isSuppressingMovement()) {
            return;
        }
        BridgeSocket.JakState state = freshState(BridgeSocket.JAK_FLAG_BODY);
        if (state == null) {
            return;
        }
        // BODY: data[0..2] = where Steve's eyes should be. Convert to feet using the current eye
        // offset (which is lower while sneaking).
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
        double x = pos.x, y = pos.y, z = pos.z;
        if (mode == Mode.STEVE) {
            y -= collision.yOffset(); // STEVE packets carry Jak-space meters
        }
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
        if (mode == Mode.BODY) flags |= BridgeSocket.FLAG_BODY_MODE;
        if (mode == Mode.JAK) flags |= BridgeSocket.FLAG_JAK_MODE;
        if (mode == Mode.STEVE || mode == Mode.JAK) {
            if (mode == Mode.STEVE) flags |= BridgeSocket.FLAG_STEVE_MODE;
            // window shape (width/height * 1000) in the top 16 bits: Jak renders the same shape
            Framebuffer fb = client.getFramebuffer();
            if (fb != null && fb.textureHeight > 0) {
                int aspect1000 = Math.round(1000f * fb.textureWidth / fb.textureHeight);
                flags |= (Math.max(0, Math.min(0xffff, aspect1000)) << 16);
            }
        }

        try {
            bridge.sendCamera(x, y, z, yaw, pitch, vfov, forward, strafe, buttons, flags);
        } catch (IOException e) {
            long now = System.nanoTime();
            if (now - lastErrorLogNanos > 5_000_000_000L) { // don't spam the log every frame
                LOG.warn("Jak bridge send failed: {}", e.toString());
                lastErrorLogNanos = now;
            }
        }
    }
}
