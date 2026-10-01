package dev.ignition.jakbridge;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Runs on the integrated (singleplayer) server: places Jak's collision as invisible barrier blocks
 * around the player, teleports Steve to where Jak was when STEVE mode starts, and lets Steve step
 * up 1-block ledges (Jak's slopes become 1-block stairs).
 */
public final class CollisionPlacer {
    /** Blocks placed per server tick at most, so the game never hitches. */
    private static final int BLOCKS_PER_TICK = 12000;
    private static final double STEVE_STEP_HEIGHT = 1.05;
    private static final double VANILLA_STEP_HEIGHT = 0.6;

    private final JakCraftConfig config;
    private volatile CollisionVoxels voxels;
    private volatile String status = "collision not loaded yet";
    private volatile int yOffset = 0;

    private final Set<Long> doneColumns = new HashSet<>();
    private volatile boolean steveActive = false;
    private boolean stepHeightApplied = false;
    private volatile double[] pendingSpawn; // Jak meters, feet

    public CollisionPlacer(JakCraftConfig config) {
        this.config = config;
        Integer saved = config.yOffset();
        if (saved != null) yOffset = saved;
    }

    public int yOffset() {
        return yOffset;
    }

    public String status() {
        return status;
    }

    public boolean loaded() {
        return voxels != null;
    }

    /** Load the .obj collision in the background. */
    public void startLoading() {
        Path dir = config.collisionDir();
        if (dir == null) {
            Path base = config.jakcraftDir();
            status = base == null
                    ? "JakCraft folder unknown - run play.bat once, or set jakcraft_dir in " + config.file()
                    : "no collision files in " + base + " - run play.bat setup again (it exports them)";
            JakBridgeClient.LOG.warn("Jak collision: {}", status);
            return;
        }
        Thread t = new Thread(() -> {
            long t0 = System.nanoTime();
            try {
                status = "loading Jak collision...";
                CollisionVoxels v = CollisionVoxels.loadFolder(dir, name -> status = "loading " + name);
                Integer saved = config.yOffset();
                int off = saved != null ? saved : JakCraftConfig.chooseYOffset(v.minY());
                if (saved == null) config.setYOffset(off);
                yOffset = off;
                voxels = v;
                status = String.format("Jak collision ready: %d levels, %d triangles, %d blocks (y offset %d)",
                        v.fileCount(), v.triangleCount(), v.voxelCount(), off);
                if (v.maxY() + off > 318) {
                    status += " - warning: tops above " + (318 - off) + " m are cut off";
                }
                JakBridgeClient.LOG.info("{} in {} ms", status, (System.nanoTime() - t0) / 1_000_000);
            } catch (Exception e) {
                status = "failed to load Jak collision: " + e;
                JakBridgeClient.LOG.error("Jak collision load failed", e);
            }
        }, "jakbridge-collision");
        t.setDaemon(true);
        t.start();
    }

    public void register() {
        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            synchronized (doneColumns) {
                doneColumns.clear();
            }
            stepHeightApplied = false;
        });
    }

    /** Called from the client thread every tick. */
    public void setSteveActive(boolean active) {
        steveActive = active;
    }

    /** Called from the client thread when Jak starts a new STEVE activation. */
    public void requestSpawn(double jakX, double jakY, double jakZ) {
        pendingSpawn = new double[] {jakX, jakY, jakZ};
    }

    private void onServerTick(MinecraftServer server) {
        List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();
        if (players.isEmpty()) return;
        ServerPlayerEntity player = players.get(0);
        ServerWorld world = player.getServerWorld();

        applyStepHeight(player, steveActive);
        CollisionVoxels v = voxels;
        if (!steveActive || v == null) return;

        BlockState state = config.visibleCollision()
                ? Blocks.LIGHT_GRAY_STAINED_GLASS.getDefaultState()
                : Blocks.BARRIER.getDefaultState();

        double[] spawn = pendingSpawn;
        if (spawn != null) {
            pendingSpawn = null;
            int scx = (int) Math.floor(spawn[0]) >> 4;
            int scz = (int) Math.floor(spawn[2]) >> 4;
            // make sure there is ground before Steve lands there
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    placeColumn(world, v, scx + dx, scz + dz, state, Integer.MAX_VALUE);
                }
            }
            player.requestTeleport(spawn[0], spawn[1] + yOffset + 0.05, spawn[2]);
            player.setVelocity(0, 0, 0);
            player.fallDistance = 0;
            return;
        }

        int pcx = player.getBlockX() >> 4;
        int pcz = player.getBlockZ() >> 4;
        int radius = config.collisionRadius();
        int budget = BLOCKS_PER_TICK;
        // nearest rings first
        for (int r = 0; r <= radius && budget > 0; r++) {
            for (int dx = -r; dx <= r && budget > 0; dx++) {
                for (int dz = -r; dz <= r && budget > 0; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    budget -= placeColumn(world, v, pcx + dx, pcz + dz, state, budget);
                }
            }
        }
    }

    /** Place one chunk column's voxels. Returns the number of blocks examined (0 if done before). */
    private int placeColumn(ServerWorld world, CollisionVoxels v, int cx, int cz, BlockState state, int budget) {
        long key = CollisionVoxels.columnKey(cx, cz);
        synchronized (doneColumns) {
            if (doneColumns.contains(key)) return 0;
        }
        CollisionVoxels.Column col = v.column(cx, cz);
        if (col == null) {
            synchronized (doneColumns) {
                doneColumns.add(key);
            }
            return 1;
        }
        if (col.count() > budget && budget != Integer.MAX_VALUE) {
            // too big for what's left of this tick; it gets a full tick next time
            return budget;
        }
        BlockPos.Mutable pos = new BlockPos.Mutable();
        int baseX = cx << 4;
        int baseZ = cz << 4;
        int off = yOffset;
        col.forEach((lx, y, lz) -> {
            pos.set(baseX + lx, y + off, baseZ + lz);
            if (world.isOutOfHeightLimit(pos)) return;
            if (world.getBlockState(pos).isAir()) {
                world.setBlockState(pos, state, Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
            }
        });
        synchronized (doneColumns) {
            doneColumns.add(key);
        }
        return col.count();
    }

    private void applyStepHeight(ServerPlayerEntity player, boolean steve) {
        if (steve == stepHeightApplied) return;
        EntityAttributeInstance step = player.getAttributeInstance(EntityAttributes.GENERIC_STEP_HEIGHT);
        if (step != null) {
            step.setBaseValue(steve ? STEVE_STEP_HEIGHT : VANILLA_STEP_HEIGHT);
        }
        stepHeightApplied = steve;
    }
}
