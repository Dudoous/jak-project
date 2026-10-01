package dev.ignition.jakbridge;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Client side of JakCraft: Minecraft on top of Jak and Daxter.
 *
 * You play Minecraft, in the Minecraft window, with all of Minecraft's gameplay (movement, blocks,
 * hotbar, inventory, mobs, swimming). Jak and Daxter is the world around you:
 *  - Jak renders his world from Steve's eyes; the picture replaces Minecraft's sky (JakSky).
 *  - Jak's level collision becomes invisible barrier blocks, so Steve walks on Jak's ground
 *    (CollisionPlacer), and the same shape goes into the depth buffer so Minecraft blocks hide
 *    behind Jak's walls (JakDepthProxy).
 *  - Blocks you place are sent to Jak: his enemies have to walk around them, and lava / fire /
 *    cactus kill them. Punching and TNT kill them too; they hurt you back.
 *  - Jak and Daxter themselves are hidden and frozen; there is no Jak gameplay.
 *
 * F8 turns the bridge on/off, F7 puts Steve back where Jak was standing.
 * Target: Minecraft 1.21.1, Yarn mappings.
 */
public final class JakBridgeClient implements ClientModInitializer {
    public static final String MOD_ID = "jakbridge";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    /** JAK_STATE packets older than this are ignored (Jak closed, paused in a menu, ...). */
    private static final long JAK_STATE_TIMEOUT_NANOS = 500_000_000L;

    private static BridgeSocket bridge;
    private static JakCraftConfig config;
    private static CollisionPlacer collision;
    private static volatile boolean streaming = true;
    private static volatile boolean inJakWorld = false;
    private static KeyBinding toggleKey;
    private static KeyBinding respawnKey;
    private static long lastErrorLogNanos = 0;
    private static int lastSpawnActivation = -1;
    private static String lastStatusShown = "";
    private static String lastWarningShown = null;
    private static float lastJakDamage = -1f;

    public static BridgeSocket bridge() {
        return bridge;
    }

    public static CollisionPlacer collision() {
        return collision;
    }

    /** Bridge switched on (F8). */
    public static boolean streaming() {
        return streaming;
    }

    /** Jak is connected and has handed his world over to Minecraft right now. */
    public static boolean inJakWorld() {
        return inJakWorld;
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
        respawnKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.jakbridge.respawn", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F7, "category.jakbridge"));

        ClientTickEvents.END_CLIENT_TICK.register(JakBridgeClient::onEndTick);
        WorldRenderEvents.START.register(JakDepthProxy::rememberMatrices);
        // Per-frame, after the world is rendered, so the camera is the interpolated one we saw.
        WorldRenderEvents.END.register(context -> sendFrame(context.camera()));

        // Jak's ground is barrier blocks: don't let a left click (creative) knock holes in it.
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) ->
                world.getBlockState(pos).isOf(Blocks.BARRIER) && collision.loaded()
                        ? ActionResult.FAIL : ActionResult.PASS);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("jakcraft").executes(ctx -> {
                    for (String line : statusLines(MinecraftClient.getInstance())) {
                        ctx.getSource().sendFeedback(Text.literal(line));
                    }
                    return 1;
                })));

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (bridge != null) {
                bridge.close();
            }
        });
    }

    /** Latest JAK_STATE if it is recent and says Minecraft has taken over, else null. */
    private static BridgeSocket.JakState freshState() {
        if (bridge == null) {
            return null;
        }
        BridgeSocket.JakState state = bridge.latestJakState();
        if (state == null || System.nanoTime() - state.receivedNanos > JAK_STATE_TIMEOUT_NANOS) {
            return null;
        }
        if ((((int) state.data[5]) & BridgeSocket.JAK_FLAG_STEVE) == 0) {
            return null;
        }
        return state;
    }

    /** /jakcraft: everything needed to tell what's wrong. */
    private static List<String> statusLines(MinecraftClient client) {
        List<String> out = new ArrayList<>();
        out.add("[JakCraft] bridge " + (streaming ? "ON" : "OFF - press F8"));
        BridgeSocket.JakState any = bridge == null ? null : bridge.latestJakState();
        boolean jakTalking = any != null && System.nanoTime() - any.receivedNanos < JAK_STATE_TIMEOUT_NANOS;
        out.add("Jak game: " + (jakTalking ? "connected" : "NOT connected - start play.bat and load your save"));
        out.add("Jak picture: " + (JakSky.active() ? "receiving" : "not receiving"));
        out.add("JakCraft folder: " + (config.jakcraftDir() == null ? "unknown (run play.bat once)" : config.jakcraftDir()));
        out.add("Collision: " + collision.status());
        if (collision.worldWarning() != null) out.add("World: " + collision.worldWarning());
        out.add("Your blocks sent to Jak: " + collision.lastBlocksSent());
        if (client.player != null) {
            out.add(String.format("Steve at %.1f %.1f %.1f (Jak meters), Jak water surface %s",
                    client.player.getX(), client.player.getY() - collision.yOffset(), client.player.getZ(),
                    Double.isNaN(JakWater.surface()) ? "none" : String.format("%.1f", JakWater.surface())));
        }
        return out;
    }

    /** Called from ExplosionMixin on the server thread. */
    public static void onExplosion(double x, double y, double z) {
        if (bridge == null || !streaming || !inJakWorld) return;
        try {
            bridge.sendEvent(BridgeSocket.EVENT_EXPLOSION, (float) x, (float) (y - collision.yOffset()), (float) z, 6f, 4f);
        } catch (IOException e) {
            LOG.warn("Could not send explosion to Jak: {}", e.toString());
        }
    }

    private static void onEndTick(MinecraftClient client) {
        while (toggleKey.wasPressed()) {
            streaming = !streaming;
            say(client, "Jak bridge " + (streaming ? "ON" : "OFF"));
        }

        ClientPlayerEntity player = client.player;
        if (player == null) {
            inJakWorld = false;
            collision.setSteveActive(false);
            lastStatusShown = ""; // show the collision status again when joining a world
            return;
        }

        // collision status: progress on the action bar, the final result once in chat
        String status = collision.status();
        if (!status.equals(lastStatusShown)) {
            boolean finalResult = collision.loaded() || !status.startsWith("loading");
            player.sendMessage(Text.literal("[JakCraft] " + status), !finalResult);
            lastStatusShown = status;
        }
        while (respawnKey.wasPressed()) {
            lastSpawnActivation = -1; // next state puts Steve back where Jak started
            say(client, "Back to where Jak was standing");
        }

        String warning = collision.worldWarning();
        if (warning != null && !warning.equals(lastWarningShown)) {
            player.sendMessage(Text.literal("[JakCraft] " + warning), false);
        }
        lastWarningShown = warning;

        BridgeSocket.JakState s = streaming ? freshState() : null;
        inJakWorld = s != null;
        collision.setSteveActive(inJakWorld);
        if (s == null) {
            JakWater.clear();
            return;
        }
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

    private static void say(MinecraftClient client, String message) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal(message), true);
        }
    }

    private static void sendFrame(Camera camera) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!streaming || bridge == null || client.player == null || camera == null) {
            return;
        }

        Vec3d pos = camera.getPos();
        double x = pos.x;
        double y = pos.y - collision.yOffset(); // packets carry Jak-space meters
        double z = pos.z;
        float yaw = MathHelper.wrapDegrees(camera.getYaw());
        float pitch = camera.getPitch();
        GameOptions options = client.options;
        // Base FOV only. Set "FOV Effects" to 0% so sprinting/potions don't desync the two views.
        float vfov = options.getFov().getValue().floatValue();

        int buttons = 0;
        if (client.currentScreen == null) {
            if (options.jumpKey.isPressed()) buttons |= BridgeSocket.BUTTON_JUMP;
            if (options.sneakKey.isPressed()) buttons |= BridgeSocket.BUTTON_SNEAK;
            if (options.sprintKey.isPressed()) buttons |= BridgeSocket.BUTTON_SPRINT;
            if (options.attackKey.isPressed()) buttons |= BridgeSocket.BUTTON_ATTACK;
            if (options.useKey.isPressed()) buttons |= BridgeSocket.BUTTON_USE;
        }

        int flags = BridgeSocket.FLAG_IN_WORLD | BridgeSocket.FLAG_STEVE_MODE;
        if (client.currentScreen != null) flags |= BridgeSocket.FLAG_SCREEN_OPEN;
        // window shape (width/height * 1000) in the top 16 bits: Jak renders the same shape
        Framebuffer fb = client.getFramebuffer();
        if (fb != null && fb.textureHeight > 0) {
            int aspect1000 = Math.round(1000f * fb.textureWidth / fb.textureHeight);
            flags |= (Math.max(0, Math.min(0xffff, aspect1000)) << 16);
        }

        try {
            bridge.sendCamera(x, y, z, yaw, pitch, vfov, 0f, 0f, buttons, flags);
        } catch (IOException e) {
            long now = System.nanoTime();
            if (now - lastErrorLogNanos > 5_000_000_000L) { // don't spam the log every frame
                LOG.warn("Jak bridge send failed: {}", e.toString());
                lastErrorLogNanos = now;
            }
        }
    }
}
