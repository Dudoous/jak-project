package dev.ignition.jakbridge;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.io.IOException;
import java.util.Arrays;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.List;
import java.util.Set;

/**
 * Runs on the integrated (singleplayer) server: places Jak's collision as invisible barrier blocks
 * around the player, teleports Steve to where Jak was when Minecraft takes over, lets Steve step
 * up 1-block ledges (Jak's slopes become 1-block stairs), and once a second tells Jak which blocks
 * the player built nearby (Jak's enemies walk around them; lava, fire, cactus... kill them).
 */
public final class CollisionPlacer {
    /** Blocks placed per server tick at most, so the game never hitches. */
    private static final int BLOCKS_PER_TICK = 12000;
    private static final double STEVE_STEP_HEIGHT = 1.05;
    private static final double VANILLA_STEP_HEIGHT = 0.6;
    private static final double VANILLA_JUMP = 0.42;
    private static final double VANILLA_BLOCK_RANGE = 4.5;
    private static final double VANILLA_ENTITY_RANGE = 3.0;
    private static final double VANILLA_SAFE_FALL = 3.0;
    private static final double VANILLA_SPEED = 0.1;
    /** Send the player's blocks to Jak this often (server ticks). */
    private static final int BLOCK_SCAN_TICKS = 20;
    /** Scan this many chunks around the player (horizontally) and blocks up/down. */
    private static final int BLOCK_SCAN_CHUNKS = 3;
    private static final int BLOCK_SCAN_HEIGHT = 32;

    private final JakCraftConfig config;
    private volatile CollisionVoxels voxels;
    private volatile String status = "collision not loaded yet";
    private volatile int yOffset = 0;

    private final Set<Long> doneColumns = new HashSet<>();
    /** Invisible blocks standing in for Jak's crates and closed doors, by Jak object id. */
    private final Map<Integer, ActorBlocks> actorBlocks = new HashMap<>();
    private BridgeSocket.Actors lastActors;
    private volatile Path digFile;
    private int ticksUntilDigSave = 100;

    private static final class ActorBlocks {
        float x, y, z;
        final List<Long> placed = new ArrayList<>();
    }
    private volatile boolean steveActive = false;
    private double bodyApplied = -1; // Steve's scale currently applied, 0 = vanilla, -1 = not set yet
    private volatile double[] pendingSpawn; // Jak meters, feet
    private volatile float pendingDamage = 0f;
    private volatile String worldWarning = null;
    private boolean checkedWorldHeight = false;
    private int ticksUntilBlockScan = 0;
    private volatile int lastBlocksSent = 0;
    private int[] blockBuf = new int[4 * 256];
    private long[] blockDist = new long[256];

    public CollisionPlacer(JakCraftConfig config) {
        this.config = config;
        this.yOffset = config.yOffset();
    }

    public int yOffset() {
        return yOffset;
    }

    public String status() {
        return status;
    }

    public CollisionVoxels voxels() {
        return voxels;
    }

    public boolean loaded() {
        return voxels != null;
    }

    /** How many of the player's blocks the last BLOCKS packet told Jak about. */
    public int lastBlocksSent() {
        return lastBlocksSent;
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
                if (v.triangleCount() == 0) {
                    status = "no collision triangles in " + dir + " - run play.bat setup again";
                    return;
                }
                voxels = v;
                status = String.format("Jak collision ready: %d levels, %d triangles, %d blocks, height %.0f..%.0f m",
                        v.fileCount(), v.triangleCount(), v.voxelCount(), v.minY(), v.maxY());
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
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            digFile = server.getSavePath(WorldSavePath.ROOT).resolve("jakcraft-dug.dat");
            JakDigs.load(digFile);
        });
        // don't leave Jak's crates behind as invisible walls in the saved world
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            ServerWorld w = server.getOverworld();
            if (w != null) removeAllActors(w);
            JakDigs.saveIfDirty(digFile);
        });
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (world instanceof ServerWorld sw) onBlockBroken(sw, pos);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            synchronized (doneColumns) {
                doneColumns.clear();
            }
            bodyApplied = -1;
            JakDigs.clear();
            digFile = null;
            checkedWorldHeight = false;
            worldWarning = null;
        });
    }

    /** Called from the client thread every tick. */
    public void setSteveActive(boolean active) {
        steveActive = active;
    }

    /** Warning about the current world (e.g. not tall enough for Jak's world), or null. */
    public String worldWarning() {
        return worldWarning;
    }

    /** Called from the client thread: Jak's enemies hit Steve for this many health points. */
    public synchronized void addDamage(float hp) {
        pendingDamage += hp;
    }

    private synchronized float takeDamage() {
        float d = pendingDamage;
        pendingDamage = 0f;
        return d;
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

        applySteveBody(player, steveActive ? config.steveScale() : 0);
        CollisionVoxels v = voxels;
        if (!steveActive || v == null) {
            if (!actorBlocks.isEmpty()) removeAllActors(world);
            return;
        }

        if (!checkedWorldHeight) {
            checkedWorldHeight = true;
            int bottom = world.getBottomY();
            int top = world.getTopY();
            if (v.minY() + yOffset < bottom || v.maxY() + yOffset > top) {
                worldWarning = String.format(
                        "this world is only %d..%d blocks tall but Jak's world is %.0f..%.0f m - create a new world "
                                + "with World Type \"JakCraft (Jak's world)\", or parts of Jak's ground will be missing",
                        bottom, top, v.minY() + yOffset, v.maxY() + yOffset);
                JakBridgeClient.LOG.warn(worldWarning);
            } else {
                worldWarning = null;
            }
        }

        if (--ticksUntilBlockScan <= 0) {
            ticksUntilBlockScan = BLOCK_SCAN_TICKS;
            sendPlayerBlocks(world, player);
        }

        syncActors(world, v);
        if (--ticksUntilDigSave <= 0) {
            ticksUntilDigSave = 100;
            JakDigs.saveIfDirty(digFile);
        }

        float damage = takeDamage();
        if (damage > 0f && player.isAlive()) {
            player.damage(player.getDamageSources().generic(), damage);
        }

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
            BlockPos spawnPos = BlockPos.ofFloored(spawn[0], spawn[1] + yOffset + 0.6, spawn[2]);
            world.setSpawnPos(spawnPos, 0f); // dying respawns you in Jak's world, not the void
            player.requestTeleport(spawn[0], spawn[1] + yOffset + 0.6, spawn[2]);
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
            if (JakDigs.contains(baseX + lx, y, baseZ + lz)) return; // the player dug this out
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

    private static boolean isBarrierLike(BlockState s) {
        return s.isOf(Blocks.BARRIER) || s.isOf(Blocks.LIGHT_GRAY_STAINED_GLASS);
    }

    /**
     * Make Jak's crates (scout fly boxes too) and closed doors solid: invisible blocks where they
     * are, removed again when a crate breaks, a door opens or the object goes out of range.
     */
    private void syncActors(ServerWorld world, CollisionVoxels v) {
        BridgeSocket bridge = JakBridgeClient.bridge();
        BridgeSocket.Actors a = bridge == null ? null : bridge.latestActors();
        boolean stale = a == null || System.nanoTime() - a.receivedNanos > 2_000_000_000L;
        if (a == lastActors && !stale) return; // nothing new
        lastActors = a;
        BlockState state = config.visibleCollision()
                ? Blocks.LIGHT_GRAY_STAINED_GLASS.getDefaultState()
                : Blocks.BARRIER.getDefaultState();
        Set<Integer> keep = new HashSet<>();
        if (!stale) {
            for (int i = 0; i < a.count; i++) {
                int id = a.id[i];
                keep.add(id);
                float x = a.data[i * 6], y = a.data[i * 6 + 1], z = a.data[i * 6 + 2];
                ActorBlocks old = actorBlocks.get(id);
                if (old != null) {
                    float d = Math.abs(old.x - x) + Math.abs(old.y - y) + Math.abs(old.z - z);
                    if (d < 0.25f) continue; // hasn't moved
                    removeActor(world, old, v);
                }
                ActorBlocks ab = new ActorBlocks();
                ab.x = x;
                ab.y = y;
                ab.z = z;
                BlockPos.Mutable pos = new BlockPos.Mutable();
                for (int[] c : JakActors.cells(a.kind[i], a.data, i * 6)) {
                    pos.set(c[0], c[1] + yOffset, c[2]);
                    if (world.isOutOfHeightLimit(pos)) continue;
                    if (world.getBlockState(pos).isAir()) {
                        world.setBlockState(pos, state, Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
                        ab.placed.add(pos.asLong());
                    }
                }
                actorBlocks.put(id, ab);
            }
        }
        Iterator<Map.Entry<Integer, ActorBlocks>> it = actorBlocks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, ActorBlocks> e = it.next();
            if (!keep.contains(e.getKey())) {
                removeActor(world, e.getValue(), v);
                it.remove();
            }
        }
    }

    private void removeActor(ServerWorld world, ActorBlocks ab, CollisionVoxels v) {
        for (long p : ab.placed) {
            BlockPos pos = BlockPos.fromLong(p);
            // never punch a hole in Jak's level collision
            if (v != null && v.contains(pos.getX(), pos.getY() - yOffset, pos.getZ())) continue;
            if (isBarrierLike(world.getBlockState(pos))) {
                world.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
            }
        }
        ab.placed.clear();
    }

    private void removeAllActors(ServerWorld world) {
        for (ActorBlocks ab : actorBlocks.values()) removeActor(world, ab, voxels);
        actorBlocks.clear();
        lastActors = null;
    }

    // ---- digging into Jak's ground (see JakDigs) ----

    /** True if pos (Minecraft coordinates) is one of the invisible blocks of Jak's level. */
    public boolean isJakGround(BlockPos pos) {
        CollisionVoxels v = voxels;
        return v != null && v.contains(pos.getX(), pos.getY() - yOffset, pos.getZ());
    }

    /** Somewhere under Jak's surface: Jak's ground somewhere above, within 48 m (Jak meters). */
    private static boolean underJakGround(CollisionVoxels v, int x, int y, int z) {
        for (int dy = 1; dy <= 48; dy++) {
            if (v.contains(x, y + dy, z)) return true;
        }
        return false;
    }

    /** What Jak's ground is made of when you dig into it: dirt near the surface, stone deeper. */
    private static BlockState earthAt(CollisionVoxels v, int x, int y, int z) {
        int depth = 0;
        if (!v.contains(x, y, z)) {
            depth = 49;
            for (int dy = 1; dy <= 48; dy++) {
                if (v.contains(x, y + dy, z)) {
                    depth = dy;
                    break;
                }
            }
        }
        return depth <= 3 ? Blocks.DIRT.getDefaultState() : Blocks.STONE.getDefaultState();
    }

    /** A pickaxe or shovel hit Jak's ground: it becomes real dirt / stone that can be mined. */
    public void convertJakGround(ServerWorld world, BlockPos pos) {
        CollisionVoxels v = voxels;
        if (v == null || world == null) return;
        int x = pos.getX(), y = pos.getY() - yOffset, z = pos.getZ();
        if (v.contains(x, y, z) && isBarrierLike(world.getBlockState(pos))) {
            placeEarth(world, pos, v, x, y, z);
        }
    }

    private void placeEarth(ServerWorld world, BlockPos pos, CollisionVoxels v, int x, int y, int z) {
        world.setBlockState(pos, earthAt(v, x, y, z), Block.NOTIFY_ALL);
        JakDigs.addEarth(x, y, z);
    }

    /**
     * A block was broken. If it was Jak's ground (or dirt / stone dug into it), remember the hole
     * and give it walls and a floor: neighbours that are Jak's invisible ground become dirt /
     * stone, and so does empty space under Jak's surface that has ground right above it (so a
     * room next to a wall you dig through stays a room). Nothing is added above the hole.
     */
    private void onBlockBroken(ServerWorld world, BlockPos pos) {
        CollisionVoxels v = voxels;
        if (v == null) return;
        int x = pos.getX(), y = pos.getY() - yOffset, z = pos.getZ();
        if (!v.contains(x, y, z) && !JakDigs.isEarth(x, y, z)) return; // an ordinary block
        JakDigs.add(x, y, z);
        int[][] around = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, -1, 0}, {0, 1, 0}};
        BlockPos.Mutable np = new BlockPos.Mutable();
        for (int[] d : around) {
            int nx = x + d[0], ny = y + d[1], nz = z + d[2];
            if (JakDigs.contains(nx, ny, nz)) continue;
            np.set(nx, ny + yOffset, nz);
            if (world.isOutOfHeightLimit(np)) continue;
            BlockState ns = world.getBlockState(np);
            boolean jakGround = v.contains(nx, ny, nz);
            if (jakGround && (isBarrierLike(ns) || ns.isAir())) {
                placeEarth(world, np.toImmutable(), v, nx, ny, nz);
            } else if (d[1] != 1 && ns.isAir() && underJakGround(v, nx, ny, nz)) {
                BlockPos above = np.up();
                boolean groundAbove = JakDigs.contains(nx, ny + 1, nz) || !world.getBlockState(above).isAir();
                if (groundAbove) placeEarth(world, np.toImmutable(), v, nx, ny, nz);
            }
        }
    }

    /** Blocks that kill Jak's enemies walking into them. */
    private static boolean isDeadly(BlockState s) {
        return s.isOf(Blocks.LAVA) || s.isOf(Blocks.MAGMA_BLOCK) || s.isOf(Blocks.CACTUS)
                || s.isOf(Blocks.SWEET_BERRY_BUSH) || s.isOf(Blocks.WITHER_ROSE)
                || s.isIn(BlockTags.FIRE) || s.isIn(BlockTags.CAMPFIRES);
    }

    /** Dirt / stone of Jak's own ground around a hole (Jak meters), not something the player built. */
    private boolean isJakEarth(int x, int y, int z) {
        return JakDigs.isEarth(x, y, z);
    }

    /** Anything the player built (Jak's own ground is barrier blocks, the rest of the world is void). */
    private static boolean isPlayerBlock(BlockState s) {
        return !s.isAir() && !s.isOf(Blocks.BARRIER) && !s.isOf(Blocks.LIGHT_GRAY_STAINED_GLASS);
    }

    /**
     * Find the blocks the player built around Steve and send the nearest ones to Jak (BLOCKS
     * packet, Jak meters). Only sections that contain such a block are scanned block by block.
     */
    private void sendPlayerBlocks(ServerWorld world, ServerPlayerEntity player) {
        BridgeSocket bridge = JakBridgeClient.bridge();
        if (bridge == null) return;
        int px = player.getBlockX(), py = player.getBlockY(), pz = player.getBlockZ();
        int pcx = px >> 4, pcz = pz >> 4;
        int minSection = (py - BLOCK_SCAN_HEIGHT) >> 4;
        int maxSection = (py + BLOCK_SCAN_HEIGHT) >> 4;
        int n = 0;
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int cx = pcx - BLOCK_SCAN_CHUNKS; cx <= pcx + BLOCK_SCAN_CHUNKS; cx++) {
            for (int cz = pcz - BLOCK_SCAN_CHUNKS; cz <= pcz + BLOCK_SCAN_CHUNKS; cz++) {
                WorldChunk chunk = world.getChunkManager().getWorldChunk(cx, cz);
                if (chunk == null) continue; // not loaded
                ChunkSection[] sections = chunk.getSectionArray();
                for (int i = 0; i < sections.length; i++) {
                    int sy = chunk.sectionIndexToCoord(i);
                    if (sy < minSection || sy > maxSection) continue;
                    ChunkSection section = sections[i];
                    if (section == null || section.isEmpty() || !section.hasAny(CollisionPlacer::isPlayerBlock)) continue;
                    for (int ly = 0; ly < 16; ly++) {
                        for (int lz = 0; lz < 16; lz++) {
                            for (int lx = 0; lx < 16; lx++) {
                                BlockState s = section.getBlockState(lx, ly, lz);
                                if (!isPlayerBlock(s)) continue;
                                int x = (cx << 4) + lx, y = (sy << 4) + ly, z = (cz << 4) + lz;
                                if (isJakEarth(x, y - yOffset, z)) continue; // the dirt of a hole you dug
                                pos.set(x, y, z);
                                int kind = 0;
                                if (!s.getCollisionShape(world, pos).isEmpty()) kind |= BridgeSocket.BLOCK_SOLID;
                                if (isDeadly(s)) kind |= BridgeSocket.BLOCK_DEADLY;
                                if (kind == 0) continue; // flowers, torches, water...: nothing for Jak
                                if (n == blockDist.length) {
                                    blockDist = Arrays.copyOf(blockDist, n * 2);
                                    blockBuf = Arrays.copyOf(blockBuf, n * 8);
                                }
                                long dx = x - px, dy = y - py, dz = z - pz;
                                // sort key: distance in the high bits, index in the low 24
                                blockDist[n] = ((dx * dx + dy * dy + dz * dz) << 24) | n;
                                blockBuf[4 * n] = x;
                                blockBuf[4 * n + 1] = y - yOffset; // Jak meters
                                blockBuf[4 * n + 2] = z;
                                blockBuf[4 * n + 3] = kind;
                                n++;
                            }
                        }
                    }
                }
            }
        }
        int[] out = blockBuf;
        if (n > BridgeSocket.MAX_BLOCKS) {
            // too many: keep the ones closest to Steve
            long[] keys = Arrays.copyOf(blockDist, n);
            Arrays.sort(keys);
            out = new int[4 * BridgeSocket.MAX_BLOCKS];
            for (int k = 0; k < BridgeSocket.MAX_BLOCKS; k++) {
                int idx = (int) (keys[k] & 0xffffff);
                System.arraycopy(blockBuf, 4 * idx, out, 4 * k, 4);
            }
            n = BridgeSocket.MAX_BLOCKS;
        }
        try {
            bridge.sendBlocks(out, n);
            lastBlocksSent = n;
        } catch (IOException e) {
            JakBridgeClient.LOG.warn("Could not send blocks to Jak: {}", e.toString());
        }
    }

    private static void setBase(ServerPlayerEntity player,
                                net.minecraft.registry.entry.RegistryEntry<net.minecraft.entity.attribute.EntityAttribute> attr,
                                double value) {
        EntityAttributeInstance a = player.getAttributeInstance(attr);
        if (a != null) a.setBaseValue(value);
    }

    /** Minecraft walking speed per 0.1 of movement_speed attribute, blocks per second. */
    private static final double BLOCKS_PER_SEC_PER_SPEED = 43.17;

    /** Jump speed for a jump this many blocks high, using Minecraft's jump physics. */
    static double jumpStrengthFor(double blocks) {
        double lo = 0.1, hi = 2.0;
        for (int i = 0; i < 40; i++) {
            double mid = (lo + hi) / 2;
            if (jumpHeight(mid) < blocks) lo = mid;
            else hi = mid;
        }
        return (lo + hi) / 2;
    }

    static double jumpHeight(double strength) {
        double y = 0, v = strength, best = 0;
        for (int t = 0; t < 200 && v > -1; t++) {
            y += v;
            v = (v - 0.08) * 0.98;
            best = Math.max(best, y);
        }
        return best;
    }

    /**
     * Steve's body in Jak's world: as tall as Jak, as fast as Jak runs, jumping higher and reaching
     * further to match, and stepping up 1-block ledges (Jak's slopes become 1-block stairs).
     * scale 0 = back to vanilla.
     */
    private void applySteveBody(ServerPlayerEntity player, double scale) {
        if (scale == bodyApplied) return;
        boolean on = scale > 0;
        double s = on ? scale : 1.0;
        setBase(player, EntityAttributes.GENERIC_SCALE, s);
        setBase(player, EntityAttributes.GENERIC_STEP_HEIGHT, on ? Math.max(STEVE_STEP_HEIGHT, VANILLA_STEP_HEIGHT * s) : VANILLA_STEP_HEIGHT);
        setBase(player, EntityAttributes.GENERIC_JUMP_STRENGTH, on ? jumpStrengthFor(config.steveJump()) : VANILLA_JUMP);
        setBase(player, EntityAttributes.PLAYER_BLOCK_INTERACTION_RANGE, VANILLA_BLOCK_RANGE * s);
        setBase(player, EntityAttributes.PLAYER_ENTITY_INTERACTION_RANGE, VANILLA_ENTITY_RANGE * s);
        setBase(player, EntityAttributes.GENERIC_SAFE_FALL_DISTANCE, on ? Math.max(VANILLA_SAFE_FALL * s, config.steveJump() + 1.5) : VANILLA_SAFE_FALL);
        setBase(player, EntityAttributes.GENERIC_MOVEMENT_SPEED, on ? config.steveSpeed() / BLOCKS_PER_SEC_PER_SPEED : VANILLA_SPEED);
        bodyApplied = scale;
    }
}
