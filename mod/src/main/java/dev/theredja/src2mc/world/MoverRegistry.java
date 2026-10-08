package dev.theredja.src2mc.world;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.bundle.BundleGeneration;
import dev.theredja.src2mc.bundle.BundleMap;
import dev.theredja.src2mc.bundle.MoverTable;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReferenceArray;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Which Sable sub-level carries which map's mover, on each side (D21). The server knows from its
 * saved data, the client from the server's sync; both resolve an entry against the active
 * bundle generation when it is first asked.
 *
 * A mover's cells stand in its sub-level's plot from the plot's centre block on, mover-local
 * cell (0, 0, 0) at the centre. Sable keeps a sub-level's plot when it saves and loads it, and the
 * centre is the plot's own, so neither side has to remember where the blocks went.
 */
public final class MoverRegistry {
    /**
     * One mover's sub-level: the placement it belongs to by anchor, the entity that moves it by
     * slot, and the bundle mover it carries by {@code source}, the lump entity -- the same for a
     * map's own mover, the template's entity for a copy a point_template made.
     */
    public record Instance(long anchor, String campaignId, String mapId, int entity, int source, UUID subLevel) {
        public Instance(long anchor, String campaignId, String mapId, int entity, UUID subLevel) { this(anchor, campaignId, mapId, entity, entity, subLevel); }
        /** Whether it carries a copy, which has none of the mover's riders baked in. */
        public boolean copy() { return entity != source; }
    }

    /**
     * An instance with its map, mover and collision shapes found; {@code initialState} is its state
     * before the logic says otherwise. {@code riders} is the collision of the logic props riding it
     * that keep their own (format.md section 18), null for none; {@code mounted} that of the
     * props parented onto it at runtime.
     */
    public record Resolved(Instance instance, MapPlacement placement, BundleMap map, MoverTable.Mover mover,
                           AtomicReferenceArray<VoxelShape> shapes, int initialState, CollisionShapes.LogicProps riders,
                           MountCollision.Layer mounted) {}

    /** State bits of a mover: not drawn, does not collide. Zero for one drawn and solid. */
    public static final int HIDDEN = 1, NOT_SOLID = 2;

    private static final Map<UUID, Instance> SERVER = new ConcurrentHashMap<>();
    private static final Map<UUID, Instance> CLIENT = new ConcurrentHashMap<>();
    private static final Map<UUID, Resolved> SERVER_RESOLVED = new ConcurrentHashMap<>();
    private static final Map<UUID, Resolved> CLIENT_RESOLVED = new ConcurrentHashMap<>();
    /** The logic's latest state per sub-level; one absent has its {@link Resolved#initialState}. */
    private static final Map<UUID, Integer> SERVER_STATE = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> CLIENT_STATE = new ConcurrentHashMap<>();
    private static volatile BundleGeneration serverGeneration, clientGeneration;

    private MoverRegistry() {}

    public static void put(boolean client, Instance instance) {
        (client ? CLIENT : SERVER).put(instance.subLevel(), instance);
        (client ? CLIENT_RESOLVED : SERVER_RESOLVED).remove(instance.subLevel());
    }

    public static void remove(boolean client, UUID subLevel) {
        (client ? CLIENT : SERVER).remove(subLevel);
        (client ? CLIENT_RESOLVED : SERVER_RESOLVED).remove(subLevel);
        (client ? CLIENT_STATE : SERVER_STATE).remove(subLevel);
    }

    /** Replaces every client entry of one dimension's sync; the client knows only one dimension at a time. */
    public static void replaceClient(List<Instance> instances) {
        CLIENT.clear();
        CLIENT_RESOLVED.clear();
        CLIENT_STATE.clear();
        for (Instance instance : instances) CLIENT.put(instance.subLevel(), instance);
    }

    public static void clear(boolean client) {
        (client ? CLIENT : SERVER).clear();
        (client ? CLIENT_RESOLVED : SERVER_RESOLVED).clear();
        (client ? CLIENT_STATE : SERVER_STATE).clear();
    }

    /** Sets a sub-level's state as the logic has it. */
    public static void setState(boolean client, UUID subLevel, int state) {
        (client ? CLIENT_STATE : SERVER_STATE).put(subLevel, state);
    }

    /** The state the logic last set, or null when it has set none. */
    public static Integer storedState(boolean client, UUID subLevel) {
        return (client ? CLIENT_STATE : SERVER_STATE).get(subLevel);
    }

    /** A resolved mover's state now. */
    public static int state(boolean client, Resolved resolved) {
        Integer state = (client ? CLIENT_STATE : SERVER_STATE).get(resolved.instance().subLevel());
        return state == null ? resolved.initialState() : state;
    }

    public static Collection<Instance> instances(boolean client) { return (client ? CLIENT : SERVER).values(); }

    public static Instance instance(boolean client, UUID subLevel) { return (client ? CLIENT : SERVER).get(subLevel); }

    /** The resolved mover a sub-level carries, or null when it is none of src2mc's or its map is not loaded. */
    public static Resolved resolve(Level level, UUID subLevel) {
        boolean client = level.isClientSide();
        Map<UUID, Resolved> resolved = client ? CLIENT_RESOLVED : SERVER_RESOLVED;
        BundleGeneration generation = Src2mc.bundles().active();
        if (generation != (client ? clientGeneration : serverGeneration)) {
            resolved.clear();
            if (client) clientGeneration = generation; else serverGeneration = generation;
        }
        Resolved hit = resolved.get(subLevel);
        if (hit != null) return hit;
        Instance instance = (client ? CLIENT : SERVER).get(subLevel);
        if (instance == null) return null;
        PlacementIndex index = level instanceof net.minecraft.server.level.ServerLevel server ? PlacementSavedData.get(server).index()
            : dev.theredja.src2mc.network.PlacementNetwork.clientIndex(level.dimension().location());
        MapPlacement placement = null;
        for (MapPlacement candidate : index.view()) {
            if (candidate.anchorWorld().asLong() == instance.anchor()) { placement = candidate; break; }
        }
        if (placement == null) return null;
        BundleMap map = generation.findMap(instance.campaignId(), instance.mapId()).orElse(null);
        if (map == null || map.movers() == null) return null;
        int index2 = map.movers().indexOfEntity(instance.source());
        if (index2 < 0) return null;
        MoverTable.Mover mover = map.movers().movers().get(index2);
        var shapes = new AtomicReferenceArray<VoxelShape>(mover.collision() == null ? 0 : mover.collision().shapeCount());
        var riders = CollisionShapes.LogicProps.of(map, new PropStates.Key(level.dimension().location(), instance.anchor()), mover.entity());
        hit = new Resolved(instance, placement, map, mover, shapes, initialState(map, mover), riders, new MountCollision.Layer());
        resolved.put(subLevel, hit);
        return hit;
    }

    /**
     * A mover's state as its entity spawns, before any input: a {@code func_brush} starts hidden
     * with {@code StartDisabled}, and collides by its {@code solidity} -- 0 while shown, 1 never
     * (INFRA puts such brushes in doorways as player clips the logic never turns on), 2 always.
     */
    private static int initialState(BundleMap map, MoverTable.Mover mover) {
        // A point_template's member is out of the map until it makes a copy.
        if (dev.theredja.src2mc.logic.Templates.removedAtSpawn(map.logic()).get(mover.entity())) return HIDDEN | NOT_SOLID;
        if (!mover.classname().equals("func_brush") || map.logic() == null || mover.entity() >= map.logic().entities().size()) return 0;
        var entity = map.logic().entities().get(mover.entity());
        return brushState(dev.theredja.src2mc.logic.Variant.integer(entity.value("startdisabled")) != 0,
            dev.theredja.src2mc.logic.Variant.integer(entity.value("solidity")));
    }

    /** {@code CFuncBrush}'s look and solidity: hidden when disabled, solid by its solidity. */
    public static int brushState(boolean disabled, int solidity) {
        boolean solid = solidity == 2 || (solidity != 1 && !disabled);
        return (disabled ? HIDDEN : 0) | (solid ? 0 : NOT_SOLID);
    }

    private static Level levelOf(dev.ryanhcode.sable.util.LevelAccelerator accelerator, BlockPos pos) {
        LevelChunk chunk = accelerator.getChunk(pos);
        return chunk == null ? null : chunk.getLevel();
    }

    /** Mover-local cell (0, 0, 0) of a sub-level's plot. */
    public static BlockPos plotOrigin(LevelPlot plot) { return plot.getCenterBlock(); }

    /**
     * A mover block's collision at {@code pos}. Asked with no level -- Sable's per-state solidity
     * and mass checks -- it is a full cube; see {@link Src2mcMoverBlock}.
     */
    static VoxelShape shape(BlockGetter getter, BlockPos pos) {
        Level level = getter instanceof Level direct ? direct
            : getter instanceof LevelChunk chunk ? chunk.getLevel()
            // Sable resolves entity collision against sub-levels through its own block cache.
            : getter instanceof dev.ryanhcode.sable.util.LevelAccelerator accelerator ? levelOf(accelerator, pos)
            : null;
        if (level == null) return Shapes.block();
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null || !container.inBounds(pos)) return Shapes.block();
        LevelPlot plot = container.getPlot(pos.getX() >> 4, pos.getZ() >> 4);
        if (plot == null) return Shapes.empty();
        Resolved resolved = resolve(level, plot.getSubLevel().getUniqueId());
        if (resolved == null || (state(level.isClientSide(), resolved) & NOT_SOLID) != 0) return Shapes.empty();
        BlockPos origin = plotOrigin(plot);
        int x = pos.getX() - origin.getX(), y = pos.getY() - origin.getY(), z = pos.getZ() - origin.getZ();
        int id = resolved.mover().collision() == null ? -1 : resolved.mover().collision().shapeAt(x, y, z);
        VoxelShape shape = Shapes.empty();
        if (id >= 0) {
            shape = resolved.shapes().get(id);
            if (shape == null) {
                shape = CollisionShapes.build(resolved.mover().collision().boxes(id));
                if (!resolved.shapes().compareAndSet(id, null, shape)) shape = resolved.shapes().get(id);
            }
        }
        // Riders that keep their own collision add it while they are solid, in the pose they stand in.
        if (resolved.riders() != null) shape = resolved.riders().apply(level.isClientSide(), x, y, z, shape);
        // And props parented onto it since, where it carries them.
        return MountCollision.apply(level, resolved, x, y, z, shape);
    }
}
