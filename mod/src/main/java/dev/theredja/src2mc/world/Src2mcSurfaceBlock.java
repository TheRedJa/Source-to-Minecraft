package dev.theredja.src2mc.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The editable handle of the exact surface fragments its cell owns: while it stands they are
 * drawn, once it is gone they are not. The renderer never looks at world blocks by itself, so
 * every appearance and disappearance is reported to {@link SurfaceChangeTracker}. Both hooks run
 * server-side only in 1.21.1 ({@code LevelChunk#setBlockState}); clients learn of the change
 * from the tracker's payload.
 */
final class Src2mcSurfaceBlock extends Src2mcInvisibleBlock {
    Src2mcSurfaceBlock(Properties properties) { super(properties); }

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
