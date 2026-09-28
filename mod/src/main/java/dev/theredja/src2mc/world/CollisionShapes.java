package dev.theredja.src2mc.world;

import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.bundle.BundleGeneration;
import dev.theredja.src2mc.bundle.CollisionTable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The collision shape of a map block or carrier at a world position, from its map's collision
 * table (format.md section 14).
 *
 * Asked on the physics hot path for every src2mc block near a moving entity, so the answer is a
 * scan of the dimension's few placements and one hash probe. What placements a dimension has is
 * kept in a snapshot per side, rebuilt when a placement is registered or a bundle generation is
 * published; the shapes themselves are built from the table's boxes the first time a cell asks.
 *
 * Vanilla hands a block the chunk it sits in and Lithium hands it the level; both lead back to
 * the level. Anything else -- a pathfinding region, a render region -- is answered from whichever
 * snapshot holds the position.
 */
public final class CollisionShapes {
    private static volatile boolean exact = true;
    private static final Map<ResourceKey<Level>, Snapshot> SERVER = new ConcurrentHashMap<>();
    private static final Map<ResourceKey<Level>, Snapshot> CLIENT = new ConcurrentHashMap<>();
    // Keyed by table identity: a table lives as long as its generation, and the shapes built
    // from it go with it.
    private static final Map<CollisionTable, AtomicReferenceArray<VoxelShape>> BUILT =
        Collections.synchronizedMap(new WeakHashMap<>());
    /**
     * Map-local cells that own a drawn fragment, built the first time one is asked. Keyed by the
     * map's collision table, whose identity is its hash: the surface table is a record, and
     * hashing one walks every fragment of the map -- as a key here it cost whole seconds per frame.
     */
    private static final Map<CollisionTable, it.unimi.dsi.fastutil.longs.LongOpenHashSet> OWNERS =
        Collections.synchronizedMap(new WeakHashMap<>());

    static final LongAdder QUERIES = new LongAdder();
    static final LongAdder SHAPED = new LongAdder();
    static final LongAdder UNKNOWN_GETTER = new LongAdder();
    static final LongAdder REBUILDS = new LongAdder();
    static final LongAdder BUILT_SHAPES = new LongAdder();

    private record Entry(MapPlacement placement, CollisionTable table, AtomicReferenceArray<VoxelShape> shapes,
                         dev.theredja.src2mc.bundle.SurfaceTable surfaces) {}
    private record Snapshot(BundleGeneration generation, long epoch, Entry[] entries) {}

    private CollisionShapes() {}

    public static boolean exact() { return exact; }

    /** Off makes every map block a full cube and every carrier nothing, which is what collision was before. */
    public static void setExact(boolean value) { exact = value; }

    /** A map block: its table shape, else a full cube. */
    public static VoxelShape surface(BlockGetter getter, BlockPos pos) {
        if (!exact) return Shapes.block();
        VoxelShape shape = lookup(getter, pos);
        return shape == null ? Shapes.block() : shape;
    }

    /**
     * What a map block is aimed at and outlined as: its collision, except where that is nothing.
     * A block with no collision that owns a drawn surface stays a full cube, so it can still be
     * broken to remove that surface; one that owns nothing -- backing hidden under terrain, a
     * block the voxelizer rounded into a room -- is outlined as nothing, and building into its
     * cell replaces it.
     */
    public static VoxelShape surfaceOutline(BlockGetter getter, BlockPos pos) {
        VoxelShape shape = surface(getter, pos);
        if (!shape.isEmpty()) return shape;
        return ownsSurface(getter, pos) ? Shapes.block() : Shapes.empty();
    }

    private static boolean ownsSurface(BlockGetter getter, BlockPos pos) {
        Level level = levelOf(getter);
        List<Snapshot> snapshots = level != null ? java.util.Collections.singletonList(snapshot(level))
            : new ArrayList<>(CLIENT.values());
        if (level == null) snapshots.addAll(SERVER.values());
        for (Snapshot snapshot : snapshots) {
            if (snapshot == null) continue;
            for (Entry entry : snapshot.entries) {
                if (!entry.placement.contains(pos)) continue;
                BlockPos local = entry.placement.toLocal(pos);
                return owners(entry).contains(local.asLong());
            }
        }
        // Outside every map this is no map's block; keep it plainly selectable.
        return true;
    }

    private static it.unimi.dsi.fastutil.longs.LongOpenHashSet owners(Entry entry) {
        var table = entry.surfaces;
        return OWNERS.computeIfAbsent(entry.table, ignored -> {
            var cells = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
            for (var section : table.sections().entrySet()) {
                int baseX = section.getKey().x() << 4, baseY = section.getKey().y() << 4, baseZ = section.getKey().z() << 4;
                for (var face : section.getValue()) {
                    if (!face.owned()) continue;
                    int local = face.localCell();
                    cells.add(BlockPos.asLong(baseX + (local & 15) + face.ownerDx(),
                        baseY + (local >> 8 & 15) + face.ownerDy(), baseZ + (local >> 4 & 15) + face.ownerDz()));
                }
            }
            cells.trim();
            return cells;
        });
    }

    private static Level levelOf(BlockGetter getter) {
        return getter instanceof Level direct ? direct
            : getter instanceof LevelChunk chunk ? chunk.getLevel() : null;
    }

    /** A carrier: its table shape, else nothing. */
    public static VoxelShape carrier(BlockGetter getter, BlockPos pos) {
        if (!exact) return Shapes.empty();
        VoxelShape shape = lookup(getter, pos);
        return shape == null ? Shapes.empty() : shape;
    }

    /** Drop the server's snapshots, for a world unload. */
    public static void clear() {
        SERVER.clear();
        CLIENT.clear();
    }

    private static VoxelShape lookup(BlockGetter getter, BlockPos pos) {
        QUERIES.increment();
        Level level = levelOf(getter);
        if (level != null) return find(snapshot(level), pos);
        UNKNOWN_GETTER.increment();
        for (Snapshot snapshot : CLIENT.values()) {
            VoxelShape shape = find(snapshot, pos);
            if (shape != null) return shape;
        }
        for (Snapshot snapshot : SERVER.values()) {
            VoxelShape shape = find(snapshot, pos);
            if (shape != null) return shape;
        }
        return null;
    }

    private static VoxelShape find(Snapshot snapshot, BlockPos pos) {
        if (snapshot == null) return null;
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        for (Entry entry : snapshot.entries) {
            MapPlacement placement = entry.placement;
            if (!placement.contains(pos)) continue;
            BlockPos translation = placement.translation();
            int id = entry.table.shapeAt(x - translation.getX(), y - translation.getY(), z - translation.getZ());
            if (id < 0) return null;
            SHAPED.increment();
            VoxelShape shape = entry.shapes.get(id);
            if (shape == null) {
                shape = build(entry.table.boxes(id));
                if (entry.shapes.compareAndSet(id, null, shape)) BUILT_SHAPES.increment();
                else shape = entry.shapes.get(id);
            }
            return shape;
        }
        return null;
    }

    private static void prebuild(CollisionTable table, AtomicReferenceArray<VoxelShape> shapes) {
        long started = System.nanoTime();
        for (int id = 0; id < shapes.length(); id++) {
            if (shapes.get(id) == null && shapes.compareAndSet(id, null, build(table.boxes(id)))) BUILT_SHAPES.increment();
        }
        Src2mc.LOGGER.info("src2mc: built {} collision shapes in {} ms", shapes.length(), (System.nanoTime() - started) / 1_000_000);
    }

    /** One shape from its boxes, in sixteenths of the cell. */
    static VoxelShape build(byte[] boxes) {
        return boxes.length == 0 ? Shapes.empty() : TableShape.of(boxes);
    }

    private static Snapshot snapshot(Level level) {
        Map<ResourceKey<Level>, Snapshot> side = level.isClientSide() ? CLIENT : SERVER;
        Snapshot snapshot = side.get(level.dimension());
        BundleGeneration generation = Src2mc.bundles().active();
        long epoch = PlacementIndex.epoch();
        if (snapshot != null && snapshot.generation == generation && snapshot.epoch == epoch) return snapshot;
        // The server's placements live in saved data that only its own thread may touch; any
        // other thread keeps the last snapshot until the server thread next asks.
        if (level instanceof ServerLevel server && !server.getServer().isSameThread()) return snapshot;
        return rebuild(level, side, generation, epoch);
    }

    private static synchronized Snapshot rebuild(Level level, Map<ResourceKey<Level>, Snapshot> side,
                                                 BundleGeneration generation, long epoch) {
        Snapshot current = side.get(level.dimension());
        if (current != null && current.generation == generation && current.epoch == epoch) return current;
        PlacementIndex index = level instanceof ServerLevel server ? PlacementSavedData.get(server).index()
            : dev.theredja.src2mc.network.PlacementNetwork.clientIndex(level.dimension().location());
        List<Entry> entries = new ArrayList<>();
        for (MapPlacement placement : index.view()) {
            var map = generation.findMap(placement.campaignId(), placement.mapId()).orElse(null);
            if (map == null || map.collision() == null) continue;
            CollisionTable table = map.collision();
            AtomicReferenceArray<VoxelShape> shapes = BUILT.computeIfAbsent(table, ignored -> {
                var fresh = new AtomicReferenceArray<VoxelShape>(table.shapeCount());
                // A whole map's shapes take about a tenth of a second; building them all now,
                // off-thread, leaves nothing to build while walking into a new area.
                java.util.concurrent.CompletableFuture.runAsync(() -> prebuild(table, fresh));
                return fresh;
            });
            entries.add(new Entry(placement, table, shapes, map.surfaces()));
        }
        Snapshot snapshot = new Snapshot(generation, epoch, entries.toArray(Entry[]::new));
        side.put(level.dimension(), snapshot);
        REBUILDS.increment();
        return snapshot;
    }

    /** One line for the status command. */
    public static String status(Level level) {
        Snapshot snapshot = (level.isClientSide() ? CLIENT : SERVER).get(level.dimension());
        int placements = snapshot == null ? 0 : snapshot.entries.length;
        long cells = 0, shapes = 0;
        if (snapshot != null) for (Entry entry : snapshot.entries) {
            cells += entry.table.cellCount();
            shapes += entry.table.shapeCount();
        }
        return "src2mc collision: " + (exact ? "exact" : "full") + "; placements with a table=" + placements
            + ", shaped cells=" + cells + ", distinct shapes=" + shapes + " (built " + BUILT_SHAPES.sum() + ")"
            + "; queries=" + QUERIES.sum() + " shaped=" + SHAPED.sum() + " other-getter=" + UNKNOWN_GETTER.sum()
            + " rebuilds=" + REBUILDS.sum();
    }

    /** What the table says about one world cell, for the probe command. */
    public static String describe(Level level, BlockPos pos) {
        Snapshot snapshot = snapshot(level);
        if (snapshot == null) return "no collision snapshot for this dimension";
        for (Entry entry : snapshot.entries) {
            if (!entry.placement.contains(pos)) continue;
            BlockPos local = entry.placement.toLocal(pos);
            int id = entry.table.shapeAt(local.getX(), local.getY(), local.getZ());
            StringBuilder out = new StringBuilder(entry.placement.mapId()).append(" local ")
                .append(local.getX()).append(' ').append(local.getY()).append(' ').append(local.getZ());
            if (id < 0) return out.append(": no entry (block default)").toString();
            byte[] boxes = entry.table.boxes(id);
            out.append(": shape ").append(id).append(", ").append(boxes.length / 6).append(" box(es) in 1/16:");
            for (int i = 0; i < boxes.length && i < 6 * 8; i += 6) {
                out.append(" [").append(boxes[i]).append(' ').append(boxes[i + 1]).append(' ').append(boxes[i + 2])
                    .append(" - ").append(boxes[i + 3]).append(' ').append(boxes[i + 4]).append(' ').append(boxes[i + 5]).append(']');
            }
            if (boxes.length > 6 * 8) out.append(" ...");
            return out.toString();
        }
        return "not inside a placement with a collision table";
    }
}
