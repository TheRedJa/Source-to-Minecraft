package dev.theredja.src2mc.logic;

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.physics.constraint.FixedConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.FixedConstraintHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelObserver;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.neoforge.event.ForgeSablePrePhysicsTickEvent;
import dev.ryanhcode.sable.neoforge.event.ForgeSableSubLevelContainerReadyEvent;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.bundle.BundleGeneration;
import dev.theredja.src2mc.bundle.BundleMap;
import dev.theredja.src2mc.bundle.MoverTable;
import dev.theredja.src2mc.world.MapPlacement;
import dev.theredja.src2mc.world.MoverRegistry;
import dev.theredja.src2mc.world.PlacementIndex;
import dev.theredja.src2mc.world.PlacementSavedData;
import dev.theredja.src2mc.world.Src2mcWorldContent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * The map's moving entities as Sable sub-levels (D21). Every placed map gets one sub-level per
 * mover of its bundle, whether its logic runs or not; Sable saves and loads them with the world,
 * and this keeps which sub-level is which map's mover in the dimension's saved data.
 *
 * <p>A sub-level is a physics body, and gravity would pull it down. A resting mover is therefore
 * held by a fixed constraint to the world at its pose, as Create: Aeronautics' physics staff
 * freezes a sub-level. A moving one is, on every physics substep, teleported to where its entity's
 * {@link LogicEntity#pose} says it is at that moment and held there by a new constraint: it follows
 * Source's path exactly, and Sable moves the players standing on it and pushes those in its way.
 */
public final class MoverSystem {
    private MoverSystem() {}

    private static final String FILE = "src2mc_movers";
    /** How often the placements are compared with the sub-levels, in ticks. */
    private static final int ENSURE_INTERVAL = 20;

    /** What the server keeps about a loaded sub-level between substeps. */
    private static final class Live {
        FixedConstraintHandle lock;
        MoverPose pose;
    }

    private static final Map<UUID, Live> LIVE = new HashMap<>();
    private static long ensureEpoch = -1, ensureGeneration = -1;
    private static int ticks;

    // ---- Saved data.

    private record Entry(MoverRegistry.Instance instance, int signature) {}

    static final class Data extends SavedData {
        private final Map<UUID, Entry> entries = new LinkedHashMap<>();
        /** Sub-levels to remove once Sable loads them: their map was removed or re-exported while they were unloaded. */
        private final Set<UUID> removals = new HashSet<>();
        /** Whether {@link #entries} are in the registry yet. */
        private boolean registered;

        static Data get(ServerLevel level) {
            return level.getDataStorage().computeIfAbsent(new Factory<>(Data::new, Data::load), FILE);
        }

        private static Data load(CompoundTag tag, HolderLookup.Provider registries) {
            Data data = new Data();
            for (Tag element : tag.getList("movers", Tag.TAG_COMPOUND)) {
                CompoundTag mover = (CompoundTag) element;
                UUID uuid = mover.getUUID("sub_level");
                data.entries.put(uuid, new Entry(new MoverRegistry.Instance(mover.getLong("anchor"), mover.getString("campaign"),
                    mover.getString("map"), mover.getInt("entity"), uuid), mover.getInt("signature")));
            }
            for (Tag element : tag.getList("removals", Tag.TAG_INT_ARRAY)) data.removals.add(NbtUtils.loadUUID(element));
            return data;
        }

        @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            ListTag movers = new ListTag();
            for (Entry entry : entries.values()) {
                CompoundTag mover = new CompoundTag();
                mover.putLong("anchor", entry.instance.anchor());
                mover.putString("campaign", entry.instance.campaignId());
                mover.putString("map", entry.instance.mapId());
                mover.putInt("entity", entry.instance.entity());
                mover.putUUID("sub_level", entry.instance.subLevel());
                mover.putInt("signature", entry.signature);
                movers.add(mover);
            }
            tag.put("movers", movers);
            ListTag removed = new ListTag();
            for (UUID uuid : removals) removed.add(NbtUtils.createUUID(uuid));
            tag.put("removals", removed);
            return tag;
        }
    }

    /** A digest of what a mover's sub-level holds: a re-export that changes it replaces the sub-level. */
    private static int signature(MoverTable.Mover mover) {
        int result = mover.classname().hashCode();
        result = 31 * result + Arrays.hashCode(mover.cellOrigin());
        result = 31 * result + Arrays.hashCode(mover.size());
        result = 31 * result + Arrays.hashCode(mover.surfaceBlocks());
        result = 31 * result + Arrays.hashCode(mover.carrierBlocks());
        return 31 * result + mover.props().size();
    }

    // ---- Keeping the sub-levels in step with the placements.

    public static void onServerTick(ServerTickEvent.Post event) {
        BundleGeneration generation = Src2mc.bundles().active();
        long epoch = PlacementIndex.epoch();
        boolean changed = epoch != ensureEpoch || generation.sequence() != ensureGeneration;
        if (!changed && ++ticks % ENSURE_INTERVAL != 0) return;
        ensureEpoch = epoch;
        ensureGeneration = generation.sequence();
        for (ServerLevel level : event.getServer().getAllLevels()) ensure(level, generation);
    }

    /** Spawns the sub-levels placed maps lack, and removes those of maps no longer placed or re-exported. */
    private static void ensure(ServerLevel level, BundleGeneration generation) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return;
        Data data = loaded(level);
        Map<Long, MapPlacement> placements = new HashMap<>();
        for (MapPlacement placement : PlacementSavedData.get(level).index().view()) placements.put(placement.anchorWorld().asLong(), placement);
        boolean changed = false;
        // Sub-levels whose placement or mover is gone, or whose mover changed.
        Set<String> present = new HashSet<>();
        for (Entry entry : List.copyOf(data.entries.values())) {
            MoverRegistry.Instance instance = entry.instance;
            MapPlacement placement = placements.get(instance.anchor());
            BundleMap map = placement == null ? null : generation.findMap(placement.campaignId(), placement.mapId()).orElse(null);
            boolean mapLoaded = placement != null && map != null;
            MoverTable.Mover mover = mapLoaded && map.movers() != null && map.movers().indexOfEntity(instance.entity()) >= 0
                ? map.movers().movers().get(map.movers().indexOfEntity(instance.entity())) : null;
            boolean stale = placement == null || !placement.mapId().equals(instance.mapId()) || !placement.campaignId().equals(instance.campaignId())
                || (mapLoaded && (mover == null || signature(mover) != entry.signature));
            if (stale) {
                remove(level, container, data, instance.subLevel());
                changed = true;
            } else {
                present.add(instance.anchor() + "/" + instance.entity());
            }
        }
        for (MapPlacement placement : placements.values()) {
            BundleMap map = generation.findMap(placement.campaignId(), placement.mapId()).orElse(null);
            if (map == null || map.movers() == null) continue;
            for (MoverTable.Mover mover : map.movers().movers()) {
                if (present.contains(placement.anchorWorld().asLong() + "/" + mover.entity())) continue;
                if (spawn(level, container, data, placement, map, mover)) changed = true;
            }
        }
        if (changed) {
            data.setDirty();
            sync(level);
        }
    }

    private static void remove(ServerLevel level, ServerSubLevelContainer container, Data data, UUID uuid) {
        data.entries.remove(uuid);
        MoverRegistry.remove(false, uuid);
        Live live = LIVE.remove(uuid);
        if (live != null && live.lock != null && live.lock.isValid()) live.lock.remove();
        SubLevel subLevel = container.getSubLevel(uuid);
        if (subLevel instanceof ServerSubLevel server && !server.isRemoved()) {
            server.getPlot().destroyAllBlocks();
            server.markRemoved();
        } else {
            data.removals.add(uuid);
        }
    }

    /** Builds one mover's sub-level: its blocks in a new plot, placed and locked where its map puts it. */
    private static boolean spawn(ServerLevel level, ServerSubLevelContainer container, Data data, MapPlacement placement,
                                 BundleMap map, MoverTable.Mover mover) {
        double[] origin = entityOrigin(map, mover);
        BlockPos translation = placement.translation();
        Pose3d pose = new Pose3d();
        pose.position().set(translation.getX() + origin[0], translation.getY() + origin[1], translation.getZ() + origin[2]);
        ServerSubLevel subLevel = (ServerSubLevel) container.allocateNewSubLevel(pose);
        LevelPlot plot = subLevel.getPlot();
        BlockPos plotOrigin = MoverRegistry.plotOrigin(plot);
        if (plotOrigin.getY() + mover.sizeY() > level.getMaxBuildHeight()) {
            Src2mc.LOGGER.warn("src2mc movers: {} entity {} is {} blocks tall, more than a plot holds; it stays where the map compiled it",
                map.mapId(), mover.entity(), mover.sizeY());
            subLevel.markRemoved();
            return false;
        }
        for (int cx = plotOrigin.getX() >> 4; cx <= (plotOrigin.getX() + mover.sizeX() - 1) >> 4; cx++) {
            for (int cz = plotOrigin.getZ() >> 4; cz <= (plotOrigin.getZ() + mover.sizeZ() - 1) >> 4; cz++) {
                ChunkPos chunk = new ChunkPos(cx, cz);
                if (plot.getChunkHolder(plot.toLocal(chunk)) == null) plot.newEmptyChunk(chunk);
            }
        }
        int[] cells = connected(mover.blockCells());
        BlockState state = Src2mcWorldContent.MOVER.get().defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        List<BlockPos> placed = new ArrayList<>(cells.length / 3);
        for (int i = 0; i < cells.length; i += 3) {
            BlockPos pos = plotOrigin.offset(cells[i], cells[i + 1], cells[i + 2]);
            plot.getChunk(plot.toLocal(new ChunkPos(pos))).setBlockState(pos, state, false);
            placed.add(pos);
        }
        for (BlockPos pos : placed) {
            LevelChunk chunk = plot.getChunk(plot.toLocal(new ChunkPos(pos)));
            SubLevelAssemblyHelper.markAndNotifyBlock(level, pos, chunk, air, state, 3, 512);
        }
        subLevel.setName(map.mapId() + " " + mover.classname() + " #" + mover.entity());
        MoverRegistry.Instance instance = new MoverRegistry.Instance(placement.anchorWorld().asLong(), placement.campaignId(),
            placement.mapId(), mover.entity(), subLevel.getUniqueId());
        data.entries.put(instance.subLevel(), new Entry(instance, signature(mover)));
        MoverRegistry.put(false, instance);
        place(level, subLevel, currentPose(level, instance));
        subLevel.updateLastPose();
        return true;
    }

    /**
     * The mover's cells, joined into one face-connected piece. Sable splits a sub-level whose
     * blocks fall apart into several, and the pieces it splits off are none of ours: unlocked,
     * they fall out of the world. Each further piece is joined to the first by a run of cells
     * along x, then y, then z; those hold mover blocks with no table entry, which collide as
     * nothing. A mover with no cells at all -- a prop door with no collision -- gets one, since a
     * sub-level without mass is removed.
     */
    static int[] connected(int[] cells) {
        if (cells.length == 0) return new int[]{0, 0, 0};
        it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet all = new it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet();
        for (int i = 0; i < cells.length; i += 3) all.add(BlockPos.asLong(cells[i], cells[i + 1], cells[i + 2]));
        it.unimi.dsi.fastutil.longs.LongOpenHashSet reached = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        long first = all.firstLong();
        flood(all, reached, first);
        for (long cell : all.toLongArray()) {
            if (reached.contains(cell)) continue;
            int x = BlockPos.getX(cell), y = BlockPos.getY(cell), z = BlockPos.getZ(cell);
            int tx = BlockPos.getX(first), ty = BlockPos.getY(first), tz = BlockPos.getZ(first);
            while (x != tx) { x += Integer.signum(tx - x); all.add(BlockPos.asLong(x, y, z)); }
            while (y != ty) { y += Integer.signum(ty - y); all.add(BlockPos.asLong(x, y, z)); }
            while (z != tz) { z += Integer.signum(tz - z); all.add(BlockPos.asLong(x, y, z)); }
            flood(all, reached, cell);
        }
        int[] out = new int[all.size() * 3];
        int i = 0;
        for (long cell : all) { out[i++] = BlockPos.getX(cell); out[i++] = BlockPos.getY(cell); out[i++] = BlockPos.getZ(cell); }
        return out;
    }

    /** Marks every cell of {@code all} face-connected to {@code start} as reached. */
    private static void flood(it.unimi.dsi.fastutil.longs.LongSet all, it.unimi.dsi.fastutil.longs.LongSet reached, long start) {
        it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue queue = new it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue();
        if (reached.add(start)) queue.enqueue(start);
        while (!queue.isEmpty()) {
            long cell = queue.dequeueLong();
            for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
                long next = BlockPos.offset(cell, direction);
                if (all.contains(next) && reached.add(next)) queue.enqueue(next);
            }
        }
    }

    /** The map-local point a mover turns about: its entity's origin, else the middle of its cells. */
    private static double[] entityOrigin(BundleMap map, MoverTable.Mover mover) {
        if (map.logic() != null && mover.entity() < map.logic().entities().size()) {
            double[] origin = map.logic().entities().get(mover.entity()).origin();
            if (origin != null) return origin;
        }
        return new double[]{mover.originX() + mover.sizeX() / 2.0, mover.originY() + mover.sizeY() / 2.0, mover.originZ() + mover.sizeZ() / 2.0};
    }

    // ---- Moving them.

    /** The pose the mover's entity has now: its logic's, else where the map compiled it. */
    private static MoverPose currentPose(ServerLevel level, MoverRegistry.Instance instance) {
        return poseAt(level, instance, 1.0);
    }

    private static MoverPose poseAt(ServerLevel level, MoverRegistry.Instance instance, double partialTick) {
        MapLogic logic = null;
        for (MapLogic running : LogicSystem.runningMaps(level)) {
            if (running.placement.anchorWorld().asLong() == instance.anchor()) { logic = running; break; }
        }
        if (logic == null) {
            Live live = LIVE.get(instance.subLevel());
            return live != null && live.pose != null ? live.pose : MoverPose.IDENTITY;
        }
        LogicEntity entity = logic.entity(instance.entity());
        if (entity == null) return MoverPose.IDENTITY;
        MoverPose pose = entity.pose(logic.time() + partialTick * MapLogic.TICK_SECONDS);
        return pose == null ? MoverPose.IDENTITY : pose;
    }

    /**
     * Puts a sub-level at a pose and locks it there. Sable maps a plot point {@code q} to
     * {@code R (q - C) + position}, C its rotation point (the centre of mass, which Sable moves as
     * blocks change); the mover wants the plot point of its entity origin, P, at the world point W
     * of that origin moved by the pose, so {@code position = W + R (C - P)}.
     */
    private static void place(ServerLevel level, ServerSubLevel subLevel, MoverPose target) {
        MoverRegistry.Resolved resolved = MoverRegistry.resolve(level, subLevel.getUniqueId());
        if (resolved == null) return;
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        PhysicsPipeline pipeline = container.physicsSystem().getPipeline();
        double[] origin = entityOrigin(resolved.map(), resolved.mover());
        BlockPos plotOrigin = MoverRegistry.plotOrigin(subLevel.getPlot());
        BlockPos translation = resolved.placement().translation();
        Vector3d plotPoint = new Vector3d(plotOrigin.getX() + origin[0] - resolved.mover().originX(),
            plotOrigin.getY() + origin[1] - resolved.mover().originY(), plotOrigin.getZ() + origin[2] - resolved.mover().originZ());
        Vector3d world = new Vector3d(translation.getX() + origin[0] + target.x(), translation.getY() + origin[1] + target.y(),
            translation.getZ() + origin[2] + target.z());
        Quaterniond rotation = target.rotation();
        Vector3d rotationPoint = new Vector3d(subLevel.logicalPose().rotationPoint());
        Vector3d position = rotation.transform(new Vector3d(rotationPoint).sub(plotPoint)).add(world);
        Live live = LIVE.computeIfAbsent(subLevel.getUniqueId(), ignored -> new Live());
        if (live.lock != null && live.lock.isValid()) live.lock.remove();
        pipeline.teleport(subLevel, position, rotation);
        pipeline.resetVelocity(subLevel);
        live.lock = pipeline.addConstraint(null, subLevel, new FixedConstraintConfiguration(position, rotationPoint, rotation));
        live.pose = target;
    }

    /** Every physics substep: each mover whose entity's pose changed is moved to it. */
    public static void onPrePhysicsTick(ForgeSablePrePhysicsTickEvent event) {
        ServerLevel level = event.getPhysicsSystem().getLevel();
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return;
        double partial = event.getPhysicsSystem().getPartialPhysicsTick();
        for (MoverRegistry.Instance instance : MoverRegistry.instances(false)) {
            if (!(container.getSubLevel(instance.subLevel()) instanceof ServerSubLevel subLevel) || subLevel.isRemoved()) continue;
            MoverPose target = poseAt(level, instance, partial);
            Live live = LIVE.get(instance.subLevel());
            if (live != null && live.lock != null && live.lock.isValid() && target.equals(live.pose)) continue;
            place(level, subLevel, target);
        }
    }

    /**
     * Sable's container of a level is ready: watch it for our sub-levels loading and unloading.
     * Sable says so from inside the level's constructor, before the level has its saved-data
     * storage, so the saved data is only read once a sub-level actually arrives or the first
     * {@link #ensure} pass runs.
     */
    public static void onContainerReady(ForgeSableSubLevelContainerReadyEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getContainer() instanceof ServerSubLevelContainer container)) return;
        container.addObserver(new SubLevelObserver() {
            @Override public void onSubLevelAdded(SubLevel subLevel) {
                Data data = loaded(level);
                UUID uuid = subLevel.getUniqueId();
                if (data.removals.remove(uuid) && subLevel instanceof ServerSubLevel server) {
                    server.getPlot().destroyAllBlocks();
                    server.markRemoved();
                    data.setDirty();
                    return;
                }
                MoverRegistry.Instance instance = MoverRegistry.instance(false, uuid);
                // Loaded from disk: hold it where it was saved until its entity says otherwise.
                if (instance != null && subLevel instanceof ServerSubLevel server) {
                    Live live = LIVE.computeIfAbsent(uuid, ignored -> new Live());
                    if (live.pose == null) live.pose = currentPose(level, instance);
                    place(level, server, live.pose);
                }
            }

            @Override public void onSubLevelRemoved(SubLevel subLevel, SubLevelRemovalReason reason) {
                Live live = LIVE.remove(subLevel.getUniqueId());
                if (live != null && live.lock != null && live.lock.isValid()) live.lock.remove();
                if (reason == SubLevelRemovalReason.REMOVED && loaded(level).entries.remove(subLevel.getUniqueId()) != null) {
                    // Removed by something else (a command, a player): the next pass builds it again.
                    MoverRegistry.remove(false, subLevel.getUniqueId());
                    loaded(level).setDirty();
                    ensureEpoch = -1;
                }
            }
        });
    }

    /** The level's saved data, its entries put in the registry the first time it is read. */
    private static Data loaded(ServerLevel level) {
        Data data = Data.get(level);
        if (!data.registered) {
            data.registered = true;
            for (Entry entry : data.entries.values()) MoverRegistry.put(false, entry.instance);
        }
        return data;
    }

    // ---- What the clients know.

    /** The pose a client should draw and aim at, for {@link LogicSystem#use}: the eye moved into the entity's compiled frame. */
    static Vec3 toCompiled(ServerLevel level, long anchor, int entity, Vec3 world) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return world;
        for (MoverRegistry.Instance instance : MoverRegistry.instances(false)) {
            if (instance.anchor() != anchor || instance.entity() != entity) continue;
            if (!(container.getSubLevel(instance.subLevel()) instanceof ServerSubLevel subLevel)) return world;
            MoverRegistry.Resolved resolved = MoverRegistry.resolve(level, instance.subLevel());
            if (resolved == null) return world;
            Vec3 plot = subLevel.logicalPose().transformPositionInverse(world);
            BlockPos plotOrigin = MoverRegistry.plotOrigin(subLevel.getPlot());
            BlockPos translation = resolved.placement().translation();
            return new Vec3(plot.x - plotOrigin.getX() + resolved.mover().originX() + translation.getX(),
                plot.y - plotOrigin.getY() + resolved.mover().originY() + translation.getY(),
                plot.z - plotOrigin.getZ() + resolved.mover().originZ() + translation.getZ());
        }
        return world;
    }

    private static void sync(ServerLevel level) {
        PacketDistributor.sendToPlayersInDimension(level, payload(level));
    }

    private static MoverNetwork.SyncPayload payload(ServerLevel level) {
        List<MoverRegistry.Instance> instances = new ArrayList<>();
        for (Entry entry : loaded(level).entries.values()) instances.add(entry.instance);
        return new MoverNetwork.SyncPayload(level.dimension().location(), instances);
    }

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) PacketDistributor.sendToPlayer(player, payload(player.serverLevel()));
    }

    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) PacketDistributor.sendToPlayer(player, payload(player.serverLevel()));
    }

    public static void onServerStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        LIVE.clear();
        MoverRegistry.clear(false);
        ensureEpoch = -1;
        ensureGeneration = -1;
    }

    /** One line per dimension for {@code /src2mc movers}. */
    static String status(ServerLevel level) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        Data data = loaded(level);
        int loaded = 0, moving = 0;
        for (Entry entry : data.entries.values()) {
            if (container != null && container.getSubLevel(entry.instance.subLevel()) != null) loaded++;
            Live live = LIVE.get(entry.instance.subLevel());
            if (live != null && live.pose != null && !live.pose.equals(MoverPose.IDENTITY)) moving++;
        }
        return "src2mc movers: " + data.entries.size() + " sub-levels (" + loaded + " loaded, " + moving + " away from their compiled place), "
            + data.removals.size() + " waiting to be removed";
    }

    /** Removes and rebuilds every mover sub-level of the dimension. */
    static int respawn(ServerLevel level) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return 0;
        Data data = loaded(level);
        int count = data.entries.size();
        for (UUID uuid : List.copyOf(data.entries.keySet())) remove(level, container, data, uuid);
        data.setDirty();
        ensure(level, Src2mc.bundles().active());
        return count;
    }
}
