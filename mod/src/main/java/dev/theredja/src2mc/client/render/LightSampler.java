package dev.theredja.src2mc.client.render;

import java.util.Map;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.util.Mth;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Samples vanilla packed light for mesh vertices baked into static VBOs. Both
 * renderers upload once and rebuild on invalidation, so light is captured at
 * build time rather than read per frame.
 */
final class LightSampler {
    private LightSampler() {}

    /**
     * Samples at world position (x, y, z) offset half a block along the outward normal
     * (nx, ny, nz), so the read lands one block outside an opaque surface rather than inside it.
     * {@code cache}, keyed by {@link BlockPos#asLong()}, is owned by the caller for the duration
     * of one build; a region or prop section touches at most a few thousand distinct positions
     * and many triangles/faces share one.
     */
    static int sample(BlockAndTintGetter level, double x, double y, double z, float nx, float ny, float nz,
                      Map<Long, Integer> cache) {
        return cached(level, BlockPos.containing(x + nx * 0.5, y + ny * 0.5, z + nz * 0.5), cache);
    }

    /**
     * Smooth light for one vertex: the eight cells around the sampled point,
     * weighed by how far the point sits into each.
     *
     * One value per face is vanilla's flat lighting, and it steps in whole
     * blocks, which is what our geometry looked like. Interpolating between
     * vertices is how vanilla's own smooth lighting works, so this changes
     * nothing about the vertex format or the GPU's work -- only which number
     * each vertex carries.
     *
     * Cells that render solid are dropped from the average and the remaining
     * weights renormalized: a wall's own blocks are unlit inside and would drag
     * every vertex on its face toward black. With nothing but solid cells
     * around the point there is nothing to average, and the flat sample -- which
     * has its own brightest-neighbour fallback -- is the better answer.
     */
    static int smooth(BlockAndTintGetter level, double x, double y, double z, float nx, float ny, float nz,
                      Map<Long, Integer> cache) {
        return smooth(level, x, y, z, nx, ny, nz, cache, null);
    }

    /** Which of a vertex's neighbouring cells light may be taken from; see {@link SurfaceOcclusion}. */
    interface CornerVisibility {
        /** Whether the cell at (cellX, cellY, cellZ) can be seen from (fromX, fromY, fromZ), in world space. */
        boolean visible(double fromX, double fromY, double fromZ, int cellX, int cellY, int cellZ);
    }

    /**
     * As above, but a cell {@code visibility} cannot see from just in front of the vertex is
     * dropped like a solid one. {@code null} sees every cell.
     */
    static int smooth(BlockAndTintGetter level, double x, double y, double z, float nx, float ny, float nz,
                      Map<Long, Integer> cache, CornerVisibility visibility) {
        double qx = x + nx * SurfaceOcclusion.START_OFFSET, qy = y + ny * SurfaceOcclusion.START_OFFSET,
            qz = z + nz * SurfaceOcclusion.START_OFFSET;
        double px = x + nx * 0.5, py = y + ny * 0.5, pz = z + nz * 0.5;
        // Cell centres sit at +0.5, so the lower cell of each pair is found by
        // stepping back half a block from the sampled point.
        int x0 = Mth.floor(px - 0.5), y0 = Mth.floor(py - 0.5), z0 = Mth.floor(pz - 0.5);
        double fx = px - 0.5 - x0, fy = py - 0.5 - y0, fz = pz - 0.5 - z0;
        int[] corners = new int[8];
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < 2; dx++) {
            for (int dy = 0; dy < 2; dy++) {
                for (int dz = 0; dz < 2; dz++) {
                    cursor.set(x0 + dx, y0 + dy, z0 + dz);
                    corners[dx << 2 | dy << 1 | dz] = level.getBlockState(cursor).isSolidRender(level, cursor)
                        || (visibility != null && !visibility.visible(qx, qy, qz, x0 + dx, y0 + dy, z0 + dz))
                        ? SOLID : cached(level, cursor, cache);
                }
            }
        }
        int blended = blend(fx, fy, fz, corners);
        if (blended != SOLID) return blended;
        if (visibility == null) return sample(level, x, y, z, nx, ny, nz, cache);
        return unweighted(level, qx, qy, qz, px, py, pz, corners, cache, visibility);
    }

    /**
     * What a map surface's vertex takes when every weighted corner was dropped. An exact surface
     * can lie well inside the cell of the block it belongs to -- a floor an eighth of a block
     * above the bottom of its block -- so the point half a block out is still inside that block
     * and every cell with weight is solid. The flat sample's brightest-neighbour rule then reads
     * whatever the solid cell happens to border, which can be daylight from the other side of the
     * map: exactly the glow along the foot of every wall. Instead the answer stays with cells the
     * vertex can see: the visible open corners regardless of weight, then the nearest visible open
     * cell around the sampled point, and darkness when there is none.
     */
    private static int unweighted(BlockAndTintGetter level, double qx, double qy, double qz,
                                  double px, double py, double pz, int[] corners, Map<Long, Integer> cache,
                                  CornerVisibility visibility) {
        int open = averageOpen(corners);
        if (open != SOLID) return open;
        int cx = Mth.floor(px), cy = Mth.floor(py), cz = Mth.floor(pz);
        double nearest = Double.POSITIVE_INFINITY;
        int best = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    cursor.set(cx + dx, cy + dy, cz + dz);
                    double ex = cursor.getX() + 0.5 - px, ey = cursor.getY() + 0.5 - py, ez = cursor.getZ() + 0.5 - pz;
                    double distance = ex * ex + ey * ey + ez * ez;
                    if (distance >= nearest || level.getBlockState(cursor).isSolidRender(level, cursor)
                        || !visibility.visible(qx, qy, qz, cursor.getX(), cursor.getY(), cursor.getZ())) continue;
                    nearest = distance;
                    best = cached(level, cursor, cache);
                }
            }
        }
        return best;
    }

    /**
     * The same sample as {@link #smooth}, spelled out for {@code /src2mc_light_probe}: every
     * corner cell with its block, whether it renders solid, whether {@code visibility} sees it,
     * its engine sky and block light and the baked sky light, then the blended result.
     */
    static String describe(net.minecraft.world.level.Level level, double x, double y, double z, float nx, float ny, float nz,
                           Map<Long, Integer> cache, CornerVisibility visibility) {
        double qx = x + nx * SurfaceOcclusion.START_OFFSET, qy = y + ny * SurfaceOcclusion.START_OFFSET,
            qz = z + nz * SurfaceOcclusion.START_OFFSET;
        double px = x + nx * 0.5, py = y + ny * 0.5, pz = z + nz * 0.5;
        int x0 = Mth.floor(px - 0.5), y0 = Mth.floor(py - 0.5), z0 = Mth.floor(pz - 0.5);
        double fx = px - 0.5 - x0, fy = py - 0.5 - y0, fz = pz - 0.5 - z0;
        StringBuilder out = new StringBuilder();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < 2; dx++) {
            for (int dy = 0; dy < 2; dy++) {
                for (int dz = 0; dz < 2; dz++) {
                    cursor.set(x0 + dx, y0 + dy, z0 + dz);
                    BlockState state = level.getBlockState(cursor);
                    boolean solid = state.isSolidRender(level, cursor);
                    boolean seen = visibility == null || visibility.visible(qx, qy, qz, x0 + dx, y0 + dy, z0 + dz);
                    double weight = (dx == 0 ? 1 - fx : fx) * (dy == 0 ? 1 - fy : fy) * (dz == 0 ? 1 - fz : fz);
                    int packed = LevelRenderer.getLightColor(level, state, cursor);
                    out.append(String.format(java.util.Locale.ROOT,
                        "    corner (%d,%d,%d) w=%.3f %s solid=%s seen=%s engine sky=%d block=%d raw sky=%d block=%d%s%n",
                        cursor.getX(), cursor.getY(), cursor.getZ(), weight,
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()),
                        solid, seen, LightTexture.sky(packed), LightTexture.block(packed),
                        level.getBrightness(net.minecraft.world.level.LightLayer.SKY, cursor),
                        level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, cursor),
                        solid || !seen ? "  (dropped)" : ""));
                }
            }
        }
        int result = smooth(level, x, y, z, nx, ny, nz, cache, visibility);
        out.append(String.format(java.util.Locale.ROOT, "    => sky=%d block=%d%n", LightTexture.sky(result), LightTexture.block(result)));
        return out.toString();
    }

    /** Plain average of every corner that is not {@link #SOLID}, whatever its weight; SOLID if none. */
    static int averageOpen(int[] corners) {
        double sky = 0, block = 0;
        int count = 0;
        for (int corner : corners) {
            if (corner == SOLID) continue;
            sky += LightTexture.sky(corner);
            block += LightTexture.block(corner);
            count++;
        }
        return count == 0 ? SOLID : LightTexture.pack((int) Math.round(block / count), (int) Math.round(sky / count));
    }

    /** A corner that contributes nothing, and the answer when none of them does. */
    static final int SOLID = -1;

    /**
     * Trilinear blend of eight packed light values, indexed {@code dx<<2|dy<<1|dz}, with
     * {@link #SOLID} corners left out and the remaining weights renormalized. Sky and block
     * are separate channels and are averaged separately; blending the packed integers would
     * mix one into the other.
     */
    static int blend(double fx, double fy, double fz, int[] corners) {
        double sky = 0, block = 0, total = 0;
        for (int corner = 0; corner < 8; corner++) {
            if (corners[corner] == SOLID) continue;
            double weight = ((corner & 4) == 0 ? 1 - fx : fx)
                * ((corner & 2) == 0 ? 1 - fy : fy)
                * ((corner & 1) == 0 ? 1 - fz : fz);
            if (weight <= 0) continue;
            sky += weight * LightTexture.sky(corners[corner]);
            block += weight * LightTexture.block(corners[corner]);
            total += weight;
        }
        if (total <= 0) return SOLID;
        return LightTexture.pack((int) Math.round(block / total), (int) Math.round(sky / total));
    }

    private static int cached(BlockAndTintGetter level, BlockPos pos, Map<Long, Integer> cache) {
        long key = pos.asLong();
        Integer hit = cache.get(key);
        if (hit != null) return hit;
        int light = compute(level, pos.immutable());
        cache.put(key, light);
        return light;
    }

    /** Falls back to the brightest neighbour when the sampled cell is itself solid and dark,
     * which keeps geometry embedded in a solid block (common for props) from going black. */
    private static int compute(BlockAndTintGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        int packed = LevelRenderer.getLightColor(level, state, pos);
        if (packed != 0 || !state.isSolidRender(level, pos)) return packed;
        int best = 0;
        for (Direction direction : Direction.values()) {
            int neighbour = LevelRenderer.getLightColor(level, pos.relative(direction));
            if (neighbour > best) best = neighbour;
        }
        return best;
    }
}
