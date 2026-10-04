package dev.theredja.src2mc.logic;

import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.bundle.BundleGeneration;
import dev.theredja.src2mc.bundle.BundleMap;
import dev.theredja.src2mc.bundle.LogicTable;
import dev.theredja.src2mc.world.MapPlacement;
import dev.theredja.src2mc.world.PlacementSavedData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Runs every started map's logic on the server, one {@link MapLogic} per placement.
 *
 * <p>A map starts only when told to ({@code /src2mc logic start}, usable from a command block)
 * or when a player arrives through a level change. Each tick a started map with a player inside,
 * in a world whose tick rate is not frozen, advances; the others wait, as a level nobody is in
 * waits in Source. Started maps and their state are saved with the dimension.
 */
public final class LogicSystem {
    private LogicSystem() {}

    private static final Map<ResourceKey<Level>, Map<Long, MapLogic>> RUNNING = new HashMap<>();
    /** Maps that had a player inside last tick: entering an empty map by level change re-activates it. */
    private static final Map<MapLogic, Boolean> OCCUPIED = new java.util.IdentityHashMap<>();
    private static long generationSequence = -1;

    private static Map<Long, MapLogic> running(ServerLevel level) {
        return RUNNING.computeIfAbsent(level.dimension(), ignored -> new LinkedHashMap<>());
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        BundleGeneration generation = Src2mc.bundles().active();
        boolean reloaded = generation.sequence() != generationSequence;
        generationSequence = generation.sequence();
        for (ServerLevel level : event.getServer().getAllLevels()) {
            LogicSavedData data = LogicSavedData.get(level);
            Map<Long, MapLogic> maps = running(level);
            if (reloaded) {
                // The bundle may have changed under a running map: carry its state over to the new export.
                for (MapLogic logic : maps.values()) data.store(logic.placement.anchorWorld().asLong(), logic.save());
                maps.clear();
                OCCUPIED.clear();
            }
            restore(level, data, maps, generation);
            boolean ticking = level.tickRateManager().runsNormally();
            for (MapLogic logic : List.copyOf(maps.values())) {
                List<ServerPlayer> inside = playersInside(level, logic.placement);
                OCCUPIED.put(logic, !inside.isEmpty());
                if (ticking && !inside.isEmpty()) logic.tick(level, inside);
                PropSync.update(level, logic);
                LogicNetwork.Update update = logic.drainUpdate(false);
                if (!update.isEmpty()) send(level, logic, update, false);
                if (logic.dirty()) data.setDirty();
            }
        }
    }

    /** Builds the maps a save holds, once their placement and bundle are both there. */
    private static void restore(ServerLevel level, LogicSavedData data, Map<Long, MapLogic> maps, BundleGeneration generation) {
        if (data.pending().isEmpty()) return;
        for (MapPlacement placement : PlacementSavedData.get(level).index().view()) {
            long anchor = placement.anchorWorld().asLong();
            CompoundTag saved = data.pending().get(anchor);
            if (saved == null || maps.containsKey(anchor)) continue;
            BundleMap map = generation.findMap(placement.campaignId(), placement.mapId()).orElse(null);
            if (map == null || map.logic() == null) continue;
            MapLogic logic = new MapLogic(placement, map);
            if (!logic.load(saved)) {
                Src2mc.LOGGER.warn("src2mc logic: saved state of {} is of a different export of the map; starting it fresh", logic);
            }
            maps.put(anchor, logic);
            data.pending().remove(anchor);
            data.setDirty();
            send(level, logic, logic.drainUpdate(true), true);
            PropSync.update(level, logic);
        }
    }

    private static List<ServerPlayer> playersInside(ServerLevel level, MapPlacement placement) {
        List<ServerPlayer> inside = new ArrayList<>(1);
        for (ServerPlayer player : level.players()) if (placement.contains(BlockPos.containing(player.position()))) inside.add(player);
        return inside;
    }

    private static void send(ServerLevel level, MapLogic logic, LogicNetwork.Update update, boolean full) {
        PacketDistributor.sendToPlayersInDimension(level, new LogicNetwork.SyncPayload(level.dimension().location(),
            logic.placement.anchorWorld().asLong(), true, full, update.states(), update.events(), update.stops()));
    }

    /** A player joining or changing dimension gets a full copy of what plays there. */
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) sendAll(player);
    }

    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) sendAll(player);
    }

    private static void sendAll(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        for (MapLogic logic : running(level).values()) {
            LogicNetwork.Update update = logic.fullState();
            PacketDistributor.sendToPlayer(player, new LogicNetwork.SyncPayload(level.dimension().location(),
                logic.placement.anchorWorld().asLong(), true, true, update.states(), List.of(), List.of()));
        }
    }

    public static void onServerStopped(ServerStoppedEvent event) {
        RUNNING.clear();
        dev.theredja.src2mc.world.PropStates.clear(false);
        PropSync.clear();
        OCCUPIED.clear();
        generationSequence = -1;
    }

    // ---- Starting and stopping.

    /** The running logic of a placement, or null. */
    static MapLogic get(ServerLevel level, MapPlacement placement) { return running(level).get(placement.anchorWorld().asLong()); }

    /** The map time of a placement's logic, or 0 when it is not running. */
    static double mapTime(ServerLevel level, dev.theredja.src2mc.world.PropStates.Key key) {
        MapLogic logic = running(level).get(key.anchor());
        return logic == null ? 0 : logic.time();
    }

    /** Starts a placement's logic as a new game; null with the reason when it cannot. */
    static String start(ServerLevel level, MapPlacement placement, MapLogic.LoadType type) {
        if (get(level, placement) != null) return placement.mapId() + " is already running; /src2mc logic stop it first to start over";
        BundleMap map = Src2mc.bundles().active().findMap(placement.campaignId(), placement.mapId()).orElse(null);
        if (map == null) return "no loaded bundle has " + placement.mapId();
        if (map.logic() == null) return placement.mapId() + " was exported without its logic; export it again";
        MapLogic logic = new MapLogic(placement, map);
        logic.spawn(type);
        running(level).put(placement.anchorWorld().asLong(), logic);
        LogicSavedData data = LogicSavedData.get(level);
        data.pending().remove(placement.anchorWorld().asLong());
        data.setDirty();
        send(level, logic, logic.drainUpdate(true), true);
        clearScreens(level, placement);
        return null;
    }

    /** Takes the map's texts, fades and shakes off the screens of the players inside it. */
    private static void clearScreens(ServerLevel level, MapPlacement placement) {
        for (ServerPlayer player : playersInside(level, placement)) PacketDistributor.sendToPlayer(player, new ScreenEffects.ClearPayload());
    }

    static boolean stop(ServerLevel level, MapPlacement placement) {
        MapLogic logic = running(level).remove(placement.anchorWorld().asLong());
        LogicSavedData data = LogicSavedData.get(level);
        boolean had = logic != null | data.pending().remove(placement.anchorWorld().asLong()) != null;
        if (logic != null) OCCUPIED.remove(logic);
        data.setDirty();
        PacketDistributor.sendToPlayersInDimension(level, new LogicNetwork.SyncPayload(level.dimension().location(),
            placement.anchorWorld().asLong(), false, true, List.of(), List.of(), List.of()));
        clearScreens(level, placement);
        return had;
    }

    static List<MapLogic> runningMaps(ServerLevel level) { return List.copyOf(running(level).values()); }

    // ---- Use.

    /** A player's use key on an entity: checked against reach, then handed to the entity. */
    static void use(ServerPlayer player, long anchor, int index, boolean pressed) {
        MapLogic logic = running(player.serverLevel()).get(anchor);
        if (logic == null || !(logic.entity(index) instanceof Movers.Usable usable) || !usable.usable()) return;
        // A door that has moved is aimed at where it is now: the eye goes into the frame it was compiled in.
        Vec3 eye = MoverSystem.toCompiled(player.serverLevel(), anchor, index, player.getEyePosition());
        double distance;
        LogicTable.Volume volume = logic.volume(logic.entity(index));
        if (volume != null) {
            double[] b = volume.bounds();
            Vec3 min = logic.world(new double[]{b[0], b[1], b[2]}), max = logic.world(new double[]{b[3], b[4], b[5]});
            double dx = Math.max(0, Math.max(min.x - eye.x, eye.x - max.x)), dy = Math.max(0, Math.max(min.y - eye.y, eye.y - max.y)),
                dz = Math.max(0, Math.max(min.z - eye.z, eye.z - max.z));
            distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        } else {
            PropUseBox box = PropUseBox.of(logic.map, logic.entity(index).source);
            if (box == null) return;
            Vec3 origin = logic.world(new double[3]);
            distance = box.distance(eye.x - origin.x, eye.y - origin.y, eye.z - origin.z);
        }
        // The client aimed within its block reach; a little slack for the shape's corners and latency.
        if (distance > player.blockInteractionRange() + 1.5) return;
        usable.use(new PlayerActor(player.getUUID()), pressed);
    }

    // ---- Level changes.

    /**
     * {@code CChangeLevel::ChangeLevelNow}, between placed maps: the player moves to the placement of
     * the next map nearest them, keeping their place relative to the landmark both maps share. The
     * next map starts if it has not, as a level change, and an empty one is entered anew.
     */
    static void changeLevel(MapLogic from, Triggers.ChangeLevel trigger, ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        String nextMap = trigger.nextMap();
        String landmark = trigger.landmark();
        MapPlacement target = null;
        double best = Double.POSITIVE_INFINITY;
        for (MapPlacement placement : PlacementSavedData.get(level).index().view()) {
            if (!placement.mapId().equalsIgnoreCase(nextMap) || placement.equals(from.placement)) continue;
            Vec3 centre = Vec3.atCenterOf(placement.worldMin()).add(Vec3.atCenterOf(placement.worldMax())).scale(0.5);
            double distance = centre.distanceToSqr(player.position());
            if (distance < best) { best = distance; target = placement; }
        }
        if (target == null) {
            player.displayClientMessage(Component.literal("Level change to " + nextMap + " (not placed)"), true);
            return;
        }
        BundleMap targetMap = Src2mc.bundles().active().findMap(target.campaignId(), target.mapId()).orElse(null);
        double[] fromLandmark = landmark(from.table, landmark), toLandmark = targetMap == null || targetMap.logic() == null ? null : landmark(targetMap.logic(), landmark);
        if (fromLandmark == null || toLandmark == null) {
            player.displayClientMessage(Component.literal("Level change to " + nextMap + ": landmark " + landmark + " not found"), true);
            return;
        }
        Vec3 offset = player.position().subtract(from.world(fromLandmark));
        Vec3 to = new Vec3(toLandmark[0] + target.translation().getX(), toLandmark[1] + target.translation().getY(),
            toLandmark[2] + target.translation().getZ()).add(offset);
        player.teleportTo(level, to.x, to.y, to.z, player.getYRot(), player.getXRot());
        MapLogic next = get(level, target);
        if (next == null) {
            String failure = start(level, target, MapLogic.LoadType.TRANSITION);
            if (failure != null) { player.displayClientMessage(Component.literal(failure), true); return; }
            next = get(level, target);
        } else if (!OCCUPIED.getOrDefault(next, false)) {
            next.reenter();
        }
        next.arrived(player);
        OCCUPIED.put(next, true);
    }

    /** The map-local origin of the {@code info_landmark} named {@code name}, or null. */
    private static double[] landmark(LogicTable table, String name) {
        for (LogicTable.Entity entity : table.entities()) {
            if (entity.classname().equals("info_landmark") && name.equalsIgnoreCase(entity.targetname()) && entity.origin() != null) return entity.origin();
        }
        return null;
    }
}
