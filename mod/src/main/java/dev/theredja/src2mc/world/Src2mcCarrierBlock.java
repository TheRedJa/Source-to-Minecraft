package dev.theredja.src2mc.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Holds collision in a cell that has no map block: a thin brush, or a piece of floor with
 * nothing beside it to hang from. It is only a shape -- no light blocked, no faces culled -- and
 * it is replaceable like tall grass, so building into its cell simply takes its place.
 * With no table entry it collides as nothing.
 */
final class Src2mcCarrierBlock extends Src2mcInvisibleBlock {
    Src2mcCarrierBlock(Properties properties) { super(properties); }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return CollisionShapes.carrier(level, pos);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return CollisionShapes.carrier(level, pos);
    }

    @Override
    protected VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) { return Shapes.empty(); }

    @Override
    protected int getLightBlock(BlockState state, BlockGetter level, BlockPos pos) { return 0; }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) { return true; }

    /** See {@link Src2mcSounds}. Only this positional form changes; the plain one stays stone's for mods that read it. */
    @Override
    public net.minecraft.world.level.block.SoundType getSoundType(BlockState state, net.minecraft.world.level.LevelReader level,
                                                                  BlockPos pos, @javax.annotation.Nullable net.minecraft.world.entity.Entity entity) {
        return Src2mcSounds.SURFACE;
    }
}
