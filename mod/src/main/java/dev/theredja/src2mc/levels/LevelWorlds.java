package dev.theredja.src2mc.levels;

import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.Src2mcConfig;
import dev.theredja.src2mc.bundle.BundleGeneration;
import dev.theredja.src2mc.logic.LogicSystem;
import dev.theredja.src2mc.logic.MapLogic;
import dev.theredja.src2mc.world.MapPlacement;
import dev.theredja.src2mc.world.PlacementSavedData;
import dev.theredja.src2mc.world.SchematicReader;
import dev.theredja.src2mc.world.WorldPlacer;
import java.nio.file.Path;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The arriving side of the one-world-per-map level changes: when the player joins a world that a
 * {@link LevelTransition} is for, the old world's player data is applied, the map is placed if this
 * world does not hold it yet, and the player is put where the transition says. Until then the player
 * is held in spectator mode behind the client's loading screen.
 */
public final class LevelWorlds {
    /** Placement time per tick while the player waits behind the loading screen. */
    private static final long LOADING_BUDGET_NANOS = 50_000_000L;

    /** Keys of the old player's data that belong to the old world and are not carried over. */
    private static final String[] WORLD_BOUND_KEYS = {"Dimension", "RootVehicle", "UUID", "SpawnX", "SpawnY", "SpawnZ",
        "SpawnForced", "SpawnAngle", "SpawnDimension", "enteredNetherPosition", "LastDeathLocation", "current_explosion_impact_pos"};

    /** Server ticks the arrival waits when the map is already placed, so the client has left vanilla's level-receiving screen. */
    private static final int ARRIVAL_DELAY_TICKS = 10;

    private record Delayed(int[] ticks, Runnable task) {}

    private static final java.util.List<Delayed> DELAYED = new java.util.ArrayList<>();

    private LevelWorlds() {}

    /** Drops queued arrivals; the server they were for has stopped. */
    public static void clear() { DELAYED.clear(); }

    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        LogicSystem.forgetLeaving(event.getEntity().getUUID());
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        if (DELAYED.isEmpty()) return;
        for (var iterator = new java.util.ArrayList<>(DELAYED).iterator(); iterator.hasNext(); ) {
            Delayed delayed = iterator.next();
            if (--delayed.ticks[0] > 0) continue;
            DELAYED.remove(delayed);
            delayed.task.run();
        }
    }

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        LevelTransition.Pending pending = LevelTransition.peek();
        if (pending == null) return;
        ServerLevel level = player.server.overworld();
        LevelWorldData world = LevelWorldData.get(level);
        if (world.claimed() ? !world.matches(pending.campaignId(), pending.mapId())
            : !PlacementSavedData.get(level).index().view().isEmpty()) {
            LevelTransition.take();
            fail(player, "world " + player.server.getWorldData().getLevelName() + " is not the world of "
                + pending.mapId() + "; open the world made for that map", null);
            return;
        }
        LevelTransition.take();
        if (!world.claimed()) world.claim(pending.campaignId(), pending.mapId());
        enter(player, level, pending);
    }

    private static void enter(ServerPlayer player, ServerLevel level, LevelTransition.Pending pending) {
        GameType realMode = player.gameMode.getGameModeForPlayer();
        if (pending.player() != null) {
            try {
                applyPlayerData(player, pending.player());
            } catch (RuntimeException exception) {
                Src2mc.LOGGER.error("src2mc level change: applying the old player data failed", exception);
                fail(player, "could not carry the player over: " + exception, null);
                return;
            }
            if (pending.player().contains("playerGameType", 99)) {
                realMode = GameType.byId(pending.player().getInt("playerGameType"));
            }
        }
        player.setGameMode(GameType.SPECTATOR);
        GameType restoreMode = pending.kind() == LevelTransition.Kind.MENU ? GameType.SPECTATOR : realMode;
        GameType restoreOnFail = pending.kind() == LevelTransition.Kind.MENU ? null : realMode;

        var located = Src2mc.bundles().active().findLocatedMap(pending.campaignId(), pending.mapId());
        if (located.isEmpty()) {
            fail(player, "no loaded bundle has map " + pending.mapId() + " of campaign " + pending.campaignId(), restoreOnFail);
            return;
        }
        String mapId = located.get().map().mapId();
        MapPlacement placed = find(level, pending.campaignId(), mapId);
        if (placed != null) {
            schedule(ARRIVAL_DELAY_TICKS, () -> {
                if (player.hasDisconnected()) return;
                safeArrive(player, level, pending, placed, restoreMode);
            });
            return;
        }
        Path schematicPath = Src2mcConfig.schematicDirectory().resolve(mapId + ".schem");
        SchematicReader.Result schematic;
        try {
            schematic = SchematicReader.read(schematicPath);
        } catch (java.io.IOException exception) {
            Src2mc.LOGGER.error("src2mc level change failed to read {}", schematicPath, exception);
            fail(player, "failed to read " + schematicPath + ": " + exception.getMessage(), restoreOnFail);
            return;
        }
        // Maps are exported stacked at the origin, so a world holding one map places it unmoved.
        BlockPos translation = BlockPos.ZERO;
        String problem = WorldPlacer.heightProblem(level, mapId, translation, schematic);
        if (problem != null) {
            fail(player, problem, restoreOnFail);
            return;
        }
        Src2mc.LOGGER.info("src2mc level change: placing {} ({} cells) for {}", mapId, schematic.cells().size(), player.getGameProfile().getName());
        PacketDistributor.sendToPlayer(player, new LevelNetwork.ProgressPayload(0f));
        float[] sent = {0f};
        WorldPlacer.enqueue(level, null, mapId, translation, schematic, LOADING_BUDGET_NANOS, error -> {
            if (player.hasDisconnected()) return;
            try {
                if (error != null) { fail(player, error, restoreOnFail); return; }
                MapPlacement placement = find(level, pending.campaignId(), mapId);
                if (placement == null) {
                    fail(player, "placing " + mapId + " registered no map (no map anchor in the schematic?)", restoreOnFail);
                    return;
                }
                arrive(player, level, pending, placement, restoreMode);
            } catch (RuntimeException exception) {
                Src2mc.LOGGER.error("src2mc level change: arriving in {} failed", mapId, exception);
                fail(player, "arriving in " + mapId + " failed: " + exception, restoreOnFail);
            }
        }, fraction -> {
            float value = (float) fraction;
            if (player.hasDisconnected() || value - sent[0] < 0.01f) return;
            sent[0] = value;
            PacketDistributor.sendToPlayer(player, new LevelNetwork.ProgressPayload(value));
        });
    }

    private static void schedule(int ticks, Runnable task) { DELAYED.add(new Delayed(new int[] {ticks}, task)); }

    private static void safeArrive(ServerPlayer player, ServerLevel level, LevelTransition.Pending pending, MapPlacement placement, GameType restoreMode) {
        try {
            arrive(player, level, pending, placement, restoreMode);
        } catch (RuntimeException exception) {
            Src2mc.LOGGER.error("src2mc level change: arriving in {} failed", placement.mapId(), exception);
            fail(player, "arriving in " + placement.mapId() + " failed: " + exception,
                pending.kind() == LevelTransition.Kind.MENU ? null : restoreMode);
        }
    }

    private static MapPlacement find(ServerLevel level, String campaignId, String mapId) {
        for (MapPlacement placement : PlacementSavedData.get(level).index().view()) {
            if (placement.campaignId().equals(campaignId) && placement.mapId().equalsIgnoreCase(mapId)) return placement;
        }
        return null;
    }

    /** The map is placed: put the player where the transition says, give back their game mode, start the map's logic. */
    private static void arrive(ServerPlayer player, ServerLevel level, LevelTransition.Pending pending, MapPlacement placement, GameType restoreMode) {
        var map = Src2mc.bundles().active().findMap(placement.campaignId(), placement.mapId()).orElse(null);
        if (map == null || map.logic() == null) {
            fail(player, placement.mapId() + " has no logic in the loaded bundles; export it again", failMode(pending, restoreMode));
            return;
        }
        BlockPos translation = placement.translation();
        Vec3 position;
        float yRot, xRot;
        Vec3 motion = Vec3.ZERO;
        MapLogic.LoadType type;
        if (pending.kind() == LevelTransition.Kind.CHANGE_LEVEL) {
            double[] landmark = LogicSystem.landmark(map.logic(), pending.landmark());
            if (landmark == null) {
                fail(player, "landmark " + pending.landmark() + " not found in " + placement.mapId(), failMode(pending, restoreMode));
                return;
            }
            position = new Vec3(landmark[0] + translation.getX(), landmark[1] + translation.getY(), landmark[2] + translation.getZ())
                .add(pending.offset());
            yRot = pending.yRot();
            xRot = pending.xRot();
            motion = pending.motion();
            type = MapLogic.LoadType.TRANSITION;
        } else if (pending.kind() == LevelTransition.Kind.CONTINUE) {
            // The world's own player data put them where they stood; nothing moves them.
            position = null;
            yRot = xRot = 0;
            type = MapLogic.LoadType.TRANSITION;
        } else {
            var start = map.logic().entities().stream()
                .filter(entity -> entity.classname().equals("info_player_start") && entity.origin() != null).findFirst().orElse(null);
            if (start == null) {
                fail(player, placement.mapId() + " has no info_player_start", failMode(pending, restoreMode));
                return;
            }
            double[] origin = start.origin();
            position = new Vec3(origin[0] + translation.getX(), origin[1] + translation.getY(), origin[2] + translation.getZ());
            double[] angles = parseAngles(start.value("angles"));
            yRot = sourceYawToMinecraft(angles[1]);
            xRot = (float) angles[0];
            type = MapLogic.LoadType.NEW_GAME;
        }
        if (position != null) {
            player.teleportTo(level, position.x, position.y, position.z, yRot, xRot);
            player.setDeltaMovement(motion);
            player.hurtMarked = true;
        }
        player.setGameMode(restoreMode);
        // The client is still behind its loading screen: the map's clock waits for its ready report (the menu has none).
        boolean hold = pending.kind() != LevelTransition.Kind.MENU;
        if (hold) LogicSystem.hold(player.getUUID());
        String failure = LogicSystem.arrive(level, placement, type, player);
        if (failure != null) {
            LogicSystem.release(player.getUUID());
            fail(player, failure, failMode(pending, restoreMode));
            return;
        }
        PacketDistributor.sendToPlayer(player, new LevelNetwork.LoadedPayload(true, ""));
    }

    /**
     * The old world's player data without what belongs to the old world: position, motion and rotation
     * stay the new player's own ({@code Entity.load} needs the three present, so they are replaced, not
     * removed), and {@link #WORLD_BOUND_KEYS} are dropped. Game mode is handled by the caller.
     */
    private static void applyPlayerData(ServerPlayer player, CompoundTag old) {
        CompoundTag tag = old.copy();
        for (String key : WORLD_BOUND_KEYS) tag.remove(key);
        ListTag pos = new ListTag(), motion = new ListTag(), rotation = new ListTag();
        pos.add(DoubleTag.valueOf(player.getX())); pos.add(DoubleTag.valueOf(player.getY())); pos.add(DoubleTag.valueOf(player.getZ()));
        motion.add(DoubleTag.valueOf(0)); motion.add(DoubleTag.valueOf(0)); motion.add(DoubleTag.valueOf(0));
        rotation.add(FloatTag.valueOf(player.getYRot())); rotation.add(FloatTag.valueOf(player.getXRot()));
        tag.put("Pos", pos);
        tag.put("Motion", motion);
        tag.put("Rotation", rotation);
        player.load(tag);
        player.onUpdateAbilities();
        player.giveExperiencePoints(0);
        player.server.getPlayerList().sendAllPlayerInfo(player);
        player.server.getPlayerList().sendActiveEffects(player, player.connection);
    }

    /** The mode to give back on failure: none for the menu's background map. */
    private static GameType failMode(LevelTransition.Pending pending, GameType restoreMode) {
        return pending.kind() == LevelTransition.Kind.MENU ? null : restoreMode;
    }

    /** Reports the failure to the client and, unless {@code restoreMode} is null, takes the player out of the loading hold. */
    private static void fail(ServerPlayer player, String message, GameType restoreMode) {
        Src2mc.LOGGER.error("src2mc level change failed: {}", message);
        if (restoreMode != null) player.setGameMode(restoreMode);
        PacketDistributor.sendToPlayer(player, new LevelNetwork.LoadedPayload(false, message));
    }

    /** A {@code pitch yaw roll} keyvalue; missing parts are 0. */
    private static double[] parseAngles(String value) {
        double[] out = new double[3];
        if (value == null) return out;
        String[] parts = value.trim().split("\\s+");
        for (int i = 0; i < 3 && i < parts.length; i++) {
            try { out[i] = Double.parseDouble(parts[i]); } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    /**
     * Source's yaw (counter-clockwise from +x, with Source's y as Minecraft's -z) as Minecraft's yaw
     * (0 faces +z, clockwise): the direction (cos, sin) in Source is (cos, -sin) in Minecraft's x, z,
     * which Minecraft's yaw reaches at -90 - yaw. Source's pitch (down positive) is Minecraft's.
     */
    private static float sourceYawToMinecraft(double yaw) {
        return (float) net.minecraft.util.Mth.wrapDegrees(-90.0 - yaw);
    }
}
