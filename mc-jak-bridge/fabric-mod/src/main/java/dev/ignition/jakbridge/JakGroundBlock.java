package dev.ignition.jakbridge;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;

/**
 * Jak's ground, as Steve walks on it: an invisible block like a barrier, but its top can be
 * anywhere in the block (16ths of a block), so it sits exactly at Jak's floor, and it sounds like
 * what Jak's floor is made of (grass, sand, wood, metal, snow...).
 *
 * The integrated server and the client share it (single player). Registered in JakBridgeMod.
 */
public final class JakGroundBlock extends Block {
    public static final IntProperty HEIGHT = IntProperty.of("height", 1, 16);
    public static final EnumProperty<Surface> SURFACE = EnumProperty.of("surface", Surface.class);

    /** Same order as JakSurface's sound kinds. */
    public enum Surface implements StringIdentifiable {
        STONE("stone", BlockSoundGroup.STONE),
        GRASS("grass", BlockSoundGroup.GRASS),
        SAND("sand", BlockSoundGroup.SAND),
        WOOD("wood", BlockSoundGroup.WOOD),
        METAL("metal", BlockSoundGroup.METAL),
        SNOW("snow", BlockSoundGroup.SNOW),
        DIRT("dirt", BlockSoundGroup.GRAVEL),
        MUD("mud", BlockSoundGroup.MUD),
        ICE("ice", BlockSoundGroup.GLASS);

        private final String id;
        final BlockSoundGroup sounds;

        Surface(String id, BlockSoundGroup sounds) {
            this.id = id;
            this.sounds = sounds;
        }

        @Override
        public String asString() {
            return id;
        }

        public static Surface of(int kind) {
            Surface[] all = values();
            return kind >= 0 && kind < all.length ? all[kind] : STONE;
        }
    }

    private static final VoxelShape[] SHAPES = new VoxelShape[17];

    static {
        for (int h = 1; h <= 16; h++) SHAPES[h] = Block.createCuboidShape(0, 0, 0, 16, h, 16);
    }

    public JakGroundBlock() {
        super(AbstractBlock.Settings.create()
                .mapColor(MapColor.CLEAR)
                // breakable with a pickaxe or shovel (JakBridgeClient stops anything else), blast proof
                .strength(0.5f, 3600000.0f)
                .dropsNothing()
                .nonOpaque()
                .allowsSpawning((state, world, pos, type) -> false)
                .solidBlock((state, world, pos) -> false)
                .suffocates((state, world, pos) -> false)
                .blockVision((state, world, pos) -> false)
                .noBlockBreakParticles()
                .pistonBehavior(PistonBehavior.BLOCK)
                .sounds(BlockSoundGroup.STONE));
        setDefaultState(getStateManager().getDefaultState().with(HEIGHT, 16).with(SURFACE, Surface.STONE));
    }

    /** The state for a block whose top is height/16 up, sounding like that kind of ground. */
    public BlockState state(int height, int soundKind) {
        return getDefaultState().with(HEIGHT, Math.max(1, Math.min(16, height))).with(SURFACE, Surface.of(soundKind));
    }

    public static boolean isPartial(BlockState s) {
        return s.getBlock() instanceof JakGroundBlock && s.get(HEIGHT) < 16;
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(HEIGHT, SURFACE);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return SHAPES[state.get(HEIGHT)];
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return SHAPES[state.get(HEIGHT)];
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.INVISIBLE;
    }

    @Override
    public BlockSoundGroup getSoundGroup(BlockState state) {
        return state.get(SURFACE).sounds;
    }

    @Override
    public float getAmbientOcclusionLightLevel(BlockState state, BlockView world, BlockPos pos) {
        return 1.0f;
    }

    @Override
    public boolean isTransparent(BlockState state, BlockView world, BlockPos pos) {
        return true;
    }
}
