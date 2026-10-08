package dev.theredja.src2mc.world;

import dev.theredja.src2mc.bundle.BundleMap;
import dev.theredja.src2mc.bundle.CollisionTable;
import dev.theredja.src2mc.bundle.LogicPropTable;
import dev.theredja.src2mc.bundle.MoverTable;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The collision of the props parented anew at runtime onto a mover (format.md section 18): the
 * prop's own table, moved by its mount from its compiled place into the carrying mover's cells,
 * added to the cell's own while the prop is solid. The furnace's crane carries its crucible so.
 * The server puts mover blocks into those cells ({@code MoverSystem}); both sides build the same
 * shapes from the same mounts, so a player stands on the crucible where the server has it.
 * Copies a point_template made are not counted yet.
 */
public final class MountCollision {
    private MountCollision() {}

    /** How near a whole quarter turn a mount's rotation must be for its boxes to turn exactly. */
    private static final double SQUARE = 1e-4;

    /**
     * One sub-level's mounted collision for one pair of {@link PropMounts#version} and
     * {@link PropStates#version}: the solid props' shapes, and every cell any mount's tables reach.
     */
    public static final class Layer {
        private long mounts = Long.MIN_VALUE, states = Long.MIN_VALUE;
        private Long2ObjectOpenHashMap<VoxelShape> shapes = new Long2ObjectOpenHashMap<>();
        private LongOpenHashSet cells = new LongOpenHashSet();
        private final Long2ObjectOpenHashMap<VoxelShape> combined = new Long2ObjectOpenHashMap<>();
    }

    /** The cell's shape with every solid prop mounted on the sub-level added; {@code base} when none reaches it. */
    static VoxelShape apply(net.minecraft.world.level.Level level, MoverRegistry.Resolved resolved, int x, int y, int z, VoxelShape base) {
        Layer layer = resolved.mounted();
        synchronized (layer) {
            refresh(level, resolved, layer);
            if (layer.shapes.isEmpty() || base == Shapes.block()) return base;
            long cell = BlockPos.asLong(x, y, z);
            VoxelShape part = layer.shapes.get(cell);
            if (part == null) return base;
            VoxelShape cached = layer.combined.get(cell);
            if (cached != null) return cached;
            VoxelShape result = base == null || base.isEmpty() ? part : Shapes.or(base, part).optimize();
            layer.combined.put(cell, result);
            return result;
        }
    }

    /** Every mover-local cell a prop mounted on the sub-level reaches in any of its poses, packed. */
    public static LongOpenHashSet cells(net.minecraft.world.level.Level level, MoverRegistry.Resolved resolved) {
        Layer layer = resolved.mounted();
        synchronized (layer) {
            refresh(level, resolved, layer);
            return new LongOpenHashSet(layer.cells);
        }
    }

    private static void refresh(net.minecraft.world.level.Level level, MoverRegistry.Resolved resolved, Layer layer) {
        boolean client = level.isClientSide();
        long mounts = PropMounts.version(client), states = PropStates.version(client);
        if (mounts == layer.mounts && states == layer.states) return;
        boolean moved = mounts != layer.mounts;
        layer.mounts = mounts;
        layer.states = states;
        layer.combined.clear();
        BundleMap map = resolved.map();
        PropStates.Key key = new PropStates.Key(level.dimension().location(), resolved.instance().anchor());
        Long2ObjectOpenHashMap<ByteArrayList> solid = new Long2ObjectOpenHashMap<>();
        LongOpenHashSet cells = moved ? new LongOpenHashSet() : null;
        if (map.logicProps() != null) {
            for (PropMounts.Mount mount : PropMounts.of(client, key).values()) {
                // A copy's slot is not its record's entity; copies do not collide yet.
                if (mount.mover() != resolved.instance().entity() || mount.entity() != mount.source()) continue;
                LogicPropTable.Prop prop = map.logicProps().byEntity(mount.source());
                if (prop == null || prop.collision() == null) continue;
                if (cells != null) {
                    cells.addAll(moved(map, prop, prop.collision(), mount, resolved.mover()).keySet());
                    for (var pose : prop.poses()) cells.addAll(moved(map, prop, pose.collision(), mount, resolved.mover()).keySet());
                }
                PropStates.State state = PropStates.effective(client, key, map, prop);
                CollisionTable table = PropStates.collision(prop, state);
                if (!state.solid() || table == null) continue;
                moved(map, prop, table, mount, resolved.mover()).forEach((cell, boxes) ->
                    solid.computeIfAbsent((long) cell, ignored -> new ByteArrayList()).addAll(boxes));
            }
        }
        Long2ObjectOpenHashMap<VoxelShape> shapes = new Long2ObjectOpenHashMap<>(solid.size());
        solid.forEach((cell, boxes) -> shapes.put((long) cell, CollisionShapes.build(boxes.toByteArray())));
        layer.shapes = shapes;
        if (cells != null) layer.cells = cells;
    }

    /**
     * A prop's collision table moved by its mount into the carrying mover's cells: per packed
     * mover-local cell, boxes in sixteenths of that cell. The table is map-local for a placed prop
     * and local to the mover it was exported riding otherwise; the mount moves map-local points
     * from where they were compiled to where they are, relative to the carrier's own move. A
     * rotation of whole quarter turns turns each box exactly; any other is covered by the box
     * around the turned one.
     */
    static Long2ObjectOpenHashMap<ByteArrayList> moved(BundleMap map, LogicPropTable.Prop prop, CollisionTable table,
                                                       PropMounts.Mount mount, MoverTable.Mover carrier) {
        int ox = 0, oy = 0, oz = 0;
        if (prop.mover() >= 0 && map.movers() != null) {
            int index = map.movers().indexOfEntity(prop.mover());
            if (index >= 0) {
                MoverTable.Mover own = map.movers().movers().get(index);
                ox = own.originX(); oy = own.originY(); oz = own.originZ();
            }
        }
        org.joml.Matrix3d m = new org.joml.Matrix3d().set(new org.joml.Quaterniond(mount.qx(), mount.qy(), mount.qz(), mount.qw()));
        double[] t = {(mount.x() - carrier.originX()) * 16, (mount.y() - carrier.originY()) * 16, (mount.z() - carrier.originZ()) * 16};
        double[][] r = {{m.m00, m.m10, m.m20}, {m.m01, m.m11, m.m21}, {m.m02, m.m12, m.m22}};
        int[][] square = square(r);
        Long2ObjectOpenHashMap<ByteArrayList> out = new Long2ObjectOpenHashMap<>();
        int fx = ox, fy = oy, fz = oz;
        table.forEachCell((x, y, z, shape) -> {
            byte[] boxes = table.boxes(shape);
            int bx = (x + fx) * 16, by = (y + fy) * 16, bz = (z + fz) * 16;
            for (int b = 0; b + 5 < boxes.length; b += 6) {
                int[] lo = {bx + boxes[b], by + boxes[b + 1], bz + boxes[b + 2]};
                int[] hi = {bx + boxes[b + 3], by + boxes[b + 4], bz + boxes[b + 5]};
                int[] min = new int[3], max = new int[3];
                if (square != null) {
                    for (int axis = 0; axis < 3; axis++) {
                        int from = square[axis][0], sign = square[axis][1];
                        long shift = Math.round(t[axis]);
                        int a = sign * lo[from], c = sign * hi[from];
                        min[axis] = (int) (Math.min(a, c) + shift);
                        max[axis] = (int) (Math.max(a, c) + shift);
                    }
                } else {
                    double[] dmin = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
                    double[] dmax = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
                    for (int corner = 0; corner < 8; corner++) {
                        double px = (corner & 1) == 0 ? lo[0] : hi[0], py = (corner & 2) == 0 ? lo[1] : hi[1], pz = (corner & 4) == 0 ? lo[2] : hi[2];
                        for (int axis = 0; axis < 3; axis++) {
                            double v = r[axis][0] * px + r[axis][1] * py + r[axis][2] * pz + t[axis];
                            dmin[axis] = Math.min(dmin[axis], v);
                            dmax[axis] = Math.max(dmax[axis], v);
                        }
                    }
                    for (int axis = 0; axis < 3; axis++) {
                        min[axis] = (int) Math.floor(dmin[axis] + 1e-6);
                        max[axis] = (int) Math.ceil(dmax[axis] - 1e-6);
                    }
                }
                split(min, max, out);
            }
        });
        return out;
    }

    /**
     * For a rotation of whole quarter turns, per world axis the table axis it takes and the
     * sign; null for any other.
     */
    private static int[][] square(double[][] r) {
        int[][] out = new int[3][];
        for (int axis = 0; axis < 3; axis++) {
            for (int from = 0; from < 3; from++) {
                double v = r[axis][from];
                if (Math.abs(Math.abs(v) - 1) < SQUARE) {
                    if (out[axis] != null) return null;
                    out[axis] = new int[]{from, v > 0 ? 1 : -1};
                } else if (Math.abs(v) >= SQUARE) {
                    return null;
                }
            }
            if (out[axis] == null) return null;
        }
        return out;
    }

    /** One box in absolute sixteenths, cut along cell borders into the cells it covers. */
    private static void split(int[] min, int[] max, Long2ObjectOpenHashMap<ByteArrayList> out) {
        if (min[0] >= max[0] || min[1] >= max[1] || min[2] >= max[2]) return;
        for (int cx = Math.floorDiv(min[0], 16); cx <= Math.floorDiv(max[0] - 1, 16); cx++) {
            for (int cy = Math.floorDiv(min[1], 16); cy <= Math.floorDiv(max[1] - 1, 16); cy++) {
                for (int cz = Math.floorDiv(min[2], 16); cz <= Math.floorDiv(max[2] - 1, 16); cz++) {
                    ByteArrayList boxes = out.computeIfAbsent(BlockPos.asLong(cx, cy, cz), ignored -> new ByteArrayList());
                    boxes.add((byte) (Math.max(min[0], cx * 16) - cx * 16));
                    boxes.add((byte) (Math.max(min[1], cy * 16) - cy * 16));
                    boxes.add((byte) (Math.max(min[2], cz * 16) - cz * 16));
                    boxes.add((byte) (Math.min(max[0], cx * 16 + 16) - cx * 16));
                    boxes.add((byte) (Math.min(max[1], cy * 16 + 16) - cy * 16));
                    boxes.add((byte) (Math.min(max[2], cz * 16 + 16) - cz * 16));
                }
            }
        }
    }
}
