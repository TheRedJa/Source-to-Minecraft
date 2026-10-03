package dev.theredja.src2mc.world;

import dev.ryanhcode.sable.api.block.BlockSubLevelCollisionShape;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Every cell of a moving entity (D21): the blocks of a door, a button or a lift, standing in the
 * plot of the Sable sub-level that carries them. The mover's surfaces are drawn by src2mc at the
 * sub-level's pose; the block itself is only collision, from the mover's own table, looked up
 * through {@link MoverRegistry}.
 *
 * Three different callers ask it for a shape, and each gets what it needs:
 * <ul>
 * <li>Entities colliding with the mover ask with the level and the block's plot position, and get
 *     the table's shape for that cell, which may be nothing.</li>
 * <li>Sable's physics engine asks through {@link BlockSubLevelCollisionShape} and gets nothing:
 *     src2mc drives the sub-level along Source's path, and contacts with the door frame it slides
 *     past would only fight that.</li>
 * <li>Sable's mass and solidity checks ask once per block state with a stand-in getter at the
 *     origin, and get a full cube: a sub-level whose blocks are not solid has no mass, and Sable
 *     removes it.</li>
 * </ul>
 * It cannot be broken (the strength is bedrock's) and blocks no light, like a carrier.
 */
public final class Src2mcMoverBlock extends Src2mcInvisibleBlock implements BlockSubLevelCollisionShape {
    Src2mcMoverBlock(Properties properties) { super(properties); }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return MoverRegistry.shape(level, pos);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return MoverRegistry.shape(level, pos);
    }

    @Override
    public VoxelShape getSubLevelCollisionShape(BlockGetter level, BlockState state) { return Shapes.empty(); }

    @Override
    protected VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) { return Shapes.empty(); }

    @Override
    protected int getLightBlock(BlockState state, BlockGetter level, BlockPos pos) { return 0; }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) { return true; }
}
