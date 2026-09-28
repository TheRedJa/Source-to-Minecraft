package dev.theredja.src2mc.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The editable handle of the exact surface fragments its cell owns: while it stands they are
 * drawn, once it is gone they are not. The renderer never looks at world blocks by itself, so
 * every appearance and disappearance is reported to {@link SurfaceChangeTracker}. Both hooks run
 * server-side only in 1.21.1 ({@code LevelChunk#setBlockState}); clients learn of the change
 * from the tracker's payload.
 *
 * It collides as the map's real solid volume in its cell, from the collision table, which can
 * be anything from a full cube to nothing, and can reach one cell out where a floor or wall
 * rests just past it. That makes the shape depend on the position, so the block is registered
 * with a dynamic shape. Light and face culling stay those of the solid cube it always was: the
 * surfaces and the sky-light bake were built around that.
 */
final class Src2mcSurfaceBlock extends Src2mcInvisibleBlock {
    Src2mcSurfaceBlock(Properties properties) { super(properties); }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return CollisionShapes.surface(level, pos);
    }

    /** See {@link CollisionShapes#surfaceOutline}. */
    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return CollisionShapes.surfaceOutline(level, pos);
    }

    /** A block that cannot be aimed at could never be cleared out of the way, so building takes its cell. */
    @Override
    protected boolean canBeReplaced(BlockState state, net.minecraft.world.item.context.BlockPlaceContext context) {
        return CollisionShapes.surfaceOutline(context.getLevel(), context.getClickedPos()).isEmpty();
    }

    @Override
    protected VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) { return Shapes.block(); }

    @Override
    protected int getLightBlock(BlockState state, BlockGetter level, BlockPos pos) { return level.getMaxLightLevel(); }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) { return false; }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!oldState.is(this)) SurfaceChangeTracker.mark(level, pos);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!newState.is(this)) SurfaceChangeTracker.mark(level, pos);
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
