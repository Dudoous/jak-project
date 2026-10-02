package dev.ignition.jakbridge;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
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
 *  - Jak and Daxter themselves are hidden and can't move by themselves; there is no Jak gameplay.
 *    Steve collects Jak's pickups (power cells, scout flies, orbs, eco) by walking into them.
 *  - View locking: Minecraft draws its world from the exact pose Jak's newest picture was taken
 *    from (CameraMixin), so the two pictures never slide against each other.
 *  - A few keys are Jak's buttons, for people and warp gates: R = circle, Enter = X,
 *    Backspace = triangle, arrow keys = d-pad.
 *
 * F8 turns the bridge on/off, F7 puts Steve back where Jak was standing, F6 locked/fast view.
 * Target: Minecraft 1.21.1, Yarn mappings.
 */
public final class JakBridgeClient implements ClientModInitializer {
    public static final String MOD_ID = "jakbridge";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    /** JAK_STATE packets older than this are ignored (Jak closed, paused in a menu, ...). */
    private static final long JAK_STATE_TIMEOUT_NANOS = 3_000_000_000L;

    private static BridgeSocket bridge;
    private static JakCraftConfig config;
    private static CollisionPlacer collision;
    private static volatile boolean streaming = true;
    private static volatile boolean inJakWorld = false;
    /**
     * Don't lock the view to a Jak picture older than this. Generous on purpose: while Jak's
     * picture keeps coming, a locked view that is a bit late looks right; an unlocked one doesn't.
     */
    private static final long MAX_LOCK_DELAY_NANOS = 1_500_000_000L;

    private static KeyBinding toggleKey;
    private static KeyBinding respawnKey;
    private static KeyBinding viewKey;
    private static KeyBinding overlayKey;
    private static boolean overlay = false;
    private static volatile String lockState = "not started";
    private static volatile boolean viewIsLocked = false;
    private static final KeyBinding[] jakKeys = new KeyBinding[7];
    private static final int[] jakKeyBits = {
            BridgeSocket.BUTTON_JAK_CIRCLE, BridgeSocket.BUTTON_JAK_X, BridgeSocket.BUTTON_JAK_TRIANGLE,
            BridgeSocket.BUTTON_JAK_LEFT, BridgeSocket.BUTTON_JAK_RIGHT, BridgeSocket.BUTTON_JAK_UP,
            BridgeSocket.BUTTON_JAK_DOWN};
    private static volatile boolean cutscene = false;
    private static boolean viewLocked = true;
    private static boolean cameraSentThisFrame = false;
    private static volatile boolean cameraHookSeen = false;
    /** When each CAMERA packet was sent, by sequence number (to measure how far behind Jak is). */
    private static final int[] sentSeq = new int[1024];
    private static final long[] sentNanos = new long[1024];
    private static volatile float jakDelayMs = -1f;
    // GameRendererMixin reports in; if it can't apply, the matching options are switched off
    private static volatile boolean fovHookSeen = false;
    private static volatile boolean bobHookSeen = false;
    private static int jakWorldTicks = 0;
    private static Boolean savedBobView = null;
    private static Double savedFovEffects = null;

    public static void fovHookWorks() {
        fovHookSeen = true;
    }

    public static void bobHookWorks() {
        bobHookSeen = true;
    }

    /**
     * Fallback for GameRendererMixin: view bobbing and FOV effects make Minecraft's picture move
     * against Jak's. Switched off while in Jak's world, put back afterwards.
     */
    private static void viewOptionsFallback(MinecraftClient client) {
        GameOptions o = client.options;
        if (inJakWorld) {
            if (++jakWorldTicks < 40) return;
            if (!bobHookSeen && savedBobView == null && o.getBobView().getValue()) {
                savedBobView = Boolean.TRUE;
                o.getBobView().setValue(false);
                LOG.info("View bobbing switched off while in Jak's world");
            }
            if (!fovHookSeen && savedFovEffects == null && o.getFovEffectScale().getValue() > 0.0) {
                savedFovEffects = o.getFovEffectScale().getValue();
                o.getFovEffectScale().setValue(0.0);
                LOG.info("FOV effects switched off while in Jak's world");
            }
        } else {
            jakWorldTicks = 0;
            restoreViewOptions(client);
        }
    }

    private static void restoreViewOptions(MinecraftClient client) {
        if (savedBobView != null) {
            client.options.getBobView().setValue(savedBobView);
            savedBobView = null;
        }
        if (savedFovEffects != null) {
            client.options.getFovEffectScale().setValue(savedFovEffects);
            savedFovEffects = null;
        }
    }
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

    /** A cutscene is playing in Jak (Jak's own camera): show his picture full screen. */
    public static boolean cutscene() {
        return cutscene && inJakWorld;
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
        overlayKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.jakbridge.overlay", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F9, "category.jakbridge"));
        viewKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.jakbridge.view_sync", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F6, "category.jakbridge"));
        String[] jakKeyNames = {"jak_circle", "jak_x", "jak_triangle", "jak_left", "jak_right", "jak_up", "jak_down"};
        int[] jakKeyDefaults = {GLFW.GLFW_KEY_R, GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_BACKSPACE, GLFW.GLFW_KEY_LEFT,
                GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN};
        for (int i = 0; i < jakKeys.length; i++) {
            jakKeys[i] = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                    "key.jakbridge." + jakKeyNames[i], InputUtil.Type.KEYSYM, jakKeyDefaults[i], "category.jakbridge"));
        }
        viewLocked = config.viewLocked();

        ClientTickEvents.END_CLIENT_TICK.register(JakBridgeClient::onEndTick);
        WorldRenderEvents.START.register(context -> {
            if (!cameraSentThisFrame) {
                // CameraMixin didn't run (renamed in this Minecraft version?): send from here
                sendCamera(context.camera().getPos(), context.camera().getYaw(), context.camera().getPitch());
                JakSky.latch();
            }
            JakDepthProxy.rememberMatrices(context);
        });
        WorldRenderEvents.END.register(context -> cameraSentThisFrame = false);
        HudRenderCallback.EVENT.register((drawContext, tickCounter) -> {
            if (cutscene()) {
                drawContext.draw(); // flush the HUD first so the cutscene covers it
                JakSky.drawCutscene();
            }
            if (overlay) drawOverlay(drawContext);
        });

        // Jak's ground is barrier blocks: don't let a left click (creative) knock holes in it.
        // Jak's ground is invisible barrier blocks. A pickaxe or shovel digs into it (it turns
        // into dirt / stone you can mine); anything else must not knock holes in it, and the
        // stand-ins for Jak's crates and doors can't be broken at all.
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            BlockState s = world.getBlockState(pos);
            if (!collision.loaded() || !(s.isOf(Blocks.BARRIER) || s.isOf(Blocks.LIGHT_GRAY_STAINED_GLASS))) {
                return ActionResult.PASS;
            }
            if (!collision.isJakGround(pos)) {
                return s.isOf(Blocks.BARRIER) ? ActionResult.FAIL : ActionResult.PASS;
            }
            boolean tool = player.getMainHandStack().isIn(ItemTags.PICKAXES)
                    || player.getMainHandStack().isIn(ItemTags.SHOVELS);
            if (!tool) return ActionResult.FAIL;
            BlockPos at = pos.toImmutable();
            if (world.isClient) {
                MinecraftServer server = MinecraftClient.getInstance().getServer();
                if (server != null) {
                    server.execute(() -> collision.convertJakGround(server.getWorld(world.getRegistryKey()), at));
                }
            } else if (world instanceof ServerWorld sw) {
                collision.convertJakGround(sw, at);
            }
            return ActionResult.PASS;
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("jakcraft").executes(ctx -> {
                    for (String line : statusLines(MinecraftClient.getInstance())) {
                        ctx.getSource().sendFeedback(Text.literal(line));
                    }
                    return 1;
                })));

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            restoreViewOptions(client);
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

    /**
     * Called by CameraMixin each rendered frame with Minecraft's live camera: send it to Jak, take
     * Jak's newest picture, and return the pose (Minecraft coordinates) Minecraft should draw its
     * world from to match that picture, or null to keep the live camera.
     */
    public static JakSky.Pose onCameraUpdate(Vec3d pos, float yaw, float pitch) {
        cameraSentThisFrame = true;
        cameraHookSeen = true;
        sendCamera(pos, yaw, pitch);
        JakSky.latch();
        JakSky.Pose p = lockPose();
        viewIsLocked = p != null;
        return p;
    }

    private static JakSky.Pose lockPose() {
        if (!streaming) return unlocked("bridge off (F8)");
        if (!viewLocked) return unlocked("fast view (F6)");
        if (!inJakWorld) return unlocked("Jak not connected");
        if (cutscene) return unlocked("Jak's own camera (cutscene / warp / loading)");
        JakSky.Pose p = JakSky.framePose();
        if (p == null) return unlocked(JakSky.active() ? "Jak's picture has no camera pose" : "no picture from Jak");
        int slot = p.seq & (sentSeq.length - 1);
        if (sentSeq[slot] != p.seq || sentNanos[slot] == 0) return unlocked("picture pose too old");
        long delay = System.nanoTime() - sentNanos[slot];
        jakDelayMs = delay / 1_000_000f;
        if (delay > MAX_LOCK_DELAY_NANOS) return unlocked(String.format("picture %.0f ms behind", jakDelayMs));
        lockState = String.format("LOCKED, %.0f ms behind your mouse", jakDelayMs);
        return new JakSky.Pose(p.seq, p.x, p.y + collision.yOffset(), p.z, p.yaw, p.pitch);
    }

    private static JakSky.Pose unlocked(String why) {
        lockState = "NOT locked: " + why;
        return null;
    }

    /** F9: what the bridge is doing, to see why the two pictures could drift apart. */
    private static void drawOverlay(net.minecraft.client.gui.DrawContext ctx) {
        MinecraftClient client = MinecraftClient.getInstance();
        Framebuffer fb = client.getFramebuffer();
        float mcAspect = fb == null ? 0 : (float) fb.textureWidth / Math.max(1, fb.textureHeight);
        float jakAspect = JakSky.frameAspect();
        List<String> lines = new ArrayList<>();
        lines.add("JakCraft view: " + lockState);
        lines.add("Picture: " + JakSky.stats());
        lines.add(String.format("Shape: Jak %dx%d (%.3f), Minecraft %dx%d (%.3f)%s", JakSky.frameWidth(), JakSky.frameHeight(),
                jakAspect, fb == null ? 0 : fb.textureWidth, fb == null ? 0 : fb.textureHeight, mcAspect,
                Math.abs(jakAspect - mcAspect) > 0.01f && jakAspect > 0 ? "  <- DIFFERENT" : ""));
        lines.add(String.format("FOV %d, hooks: camera %s, fov %s, bobbing %s", client.options.getFov().getValue(),
                cameraHookSeen ? "ok" : "MISSING", fovHookSeen ? "ok" : "fallback", bobHookSeen ? "ok" : "fallback"));
        if (client.player != null) {
            lines.add(String.format("Steve %.1f %.1f %.1f (Jak meters)%s", client.player.getX(),
                    client.player.getY() - collision.yOffset(), client.player.getZ(), cutscene ? ", Jak's camera" : ""));
        }
        int y = 4;
        for (String line : lines) {
            int color = line.contains("NOT locked") || line.contains("DIFFERENT") || line.contains("MISSING") ? 0xFFFF6060 : 0xFFFFFFFF;
            ctx.drawTextWithShadow(client.textRenderer, line, 4, y, color);
            y += 10;
        }
    }


    /** /jakcraft: everything needed to tell what's wrong. */
    private static List<String> statusLines(MinecraftClient client) {
        List<String> out = new ArrayList<>();
        out.add("[JakCraft] bridge " + (streaming ? "ON" : "OFF - press F8"));
        BridgeSocket.JakState any = bridge == null ? null : bridge.latestJakState();
        boolean jakTalking = any != null && System.nanoTime() - any.receivedNanos < JAK_STATE_TIMEOUT_NANOS;
        out.add("Jak game: " + (jakTalking ? "connected" : "NOT connected - start play.bat and load your save"));
        out.add("Jak picture: " + (JakSky.active() ? "receiving" : "not receiving")
                + (jakDelayMs >= 0 ? String.format(", %.0f ms behind your mouse", jakDelayMs) : ""));
        out.add("Picture: " + JakSky.stats());
        out.add("View: " + lockState + (viewLocked ? " (F6 for fast)" : " (F6 to lock)") + " - F9 shows this on screen");
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
            restoreViewOptions(client);
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
        while (overlayKey.wasPressed()) {
            overlay = !overlay;
        }
        while (viewKey.wasPressed()) {
            viewLocked = !viewLocked;
            config.setViewLocked(viewLocked);
            say(client, viewLocked ? "View locked to Jak's picture (nothing slides, a little mouse delay)"
                    : "Fast view (no mouse delay, Jak's world trails a little)");
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
        cutscene = s != null && (((int) s.data[5]) & BridgeSocket.JAK_FLAG_CUTSCENE) != 0;
        viewOptionsFallback(client);
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

    /** Where Steve's feet are drawn this frame (between two ticks, like the camera). */
    private static Vec3d steveFeet(MinecraftClient client) {
        try {
            return client.player.getLerpedPos(client.getRenderTickCounter().getTickDelta(true));
        } catch (Throwable t) {
            return client.player.getPos();
        }
    }

    /** Steve is down a hole he dug into Jak's ground (Jak then stays up on the surface). */
    private static boolean inDugHole(ClientPlayerEntity p) {
        if (JakDigs.isEmpty()) return false;
        int x = MathHelper.floor(p.getX()), z = MathHelper.floor(p.getZ());
        int feet = MathHelper.floor(p.getY() - collision.yOffset());
        int eye = MathHelper.floor(p.getEyeY() - collision.yOffset());
        for (int y = feet - 1; y <= eye; y++) {
            if (JakDigs.contains(x, y, z)) return true;
        }
        return false;
    }

    /** Send Minecraft's live camera to Jak (eye position, look direction, FOV, keys). */
    private static void sendCamera(Vec3d pos, float yawDeg, float pitchDeg) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!streaming || bridge == null || client.player == null || pos == null) {
            return;
        }

        double x = pos.x;
        double y = pos.y - collision.yOffset(); // packets carry Jak-space meters
        double z = pos.z;
        float yaw = MathHelper.wrapDegrees(yawDeg);
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
            for (int i = 0; i < jakKeys.length; i++) {
                if (jakKeys[i] != null && jakKeys[i].isPressed()) buttons |= jakKeyBits[i];
            }
        }

        int flags = BridgeSocket.FLAG_IN_WORLD | BridgeSocket.FLAG_STEVE_MODE;
        if (inDugHole(client.player)) flags |= BridgeSocket.FLAG_IN_HOLE;
        if (client.currentScreen != null) flags |= BridgeSocket.FLAG_SCREEN_OPEN;
        // window shape (width/height * 1000) in the top 16 bits: Jak renders the same shape
        Framebuffer fb = client.getFramebuffer();
        if (fb != null && fb.textureHeight > 0) {
            int aspect1000 = Math.round(1000f * fb.textureWidth / fb.textureHeight);
            flags |= (Math.max(0, Math.min(0xffff, aspect1000)) << 16);
        }

        try {
            // Steve's eye height (he's scaled up in Jak's world; lower while sneaking)
            float eye = (float) (client.player.getEyeY() - client.player.getY());
            // Steve's feet: in third person the camera isn't above them (Jak must be under Steve)
            Vec3d feet = steveFeet(client);
            int seq = bridge.sendCamera(x, y, z, yaw, pitchDeg, vfov, eye, 0f, buttons, flags,
                    (float) feet.x, (float) (feet.y - collision.yOffset()), (float) feet.z);
            int slot = seq & (sentSeq.length - 1);
            sentSeq[slot] = seq;
            sentNanos[slot] = System.nanoTime();
        } catch (IOException e) {
            long now = System.nanoTime();
            if (now - lastErrorLogNanos > 5_000_000_000L) { // don't spam the log every frame
                LOG.warn("Jak bridge send failed: {}", e.toString());
                lastErrorLogNanos = now;
            }
        }
    }
}
