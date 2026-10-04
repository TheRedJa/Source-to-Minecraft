package dev.theredja.src2mc.network;

import dev.theredja.src2mc.world.PlacementIndex;
import dev.theredja.src2mc.world.PlacementSavedData;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class PlacementNetwork {
    private static final Map<ResourceLocation, PlacementIndex> CLIENT = new ConcurrentHashMap<>();
    private PlacementNetwork() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        // "2": surface-change payload added alongside the v2 exact-fragment surface table.
        // "3": map logic's sound sync and use payloads.
        // "4": mover sync (which Sable sub-level carries which map's mover).
        // "5": mover state (shown, solid).
        // "6": logic prop state (shown, solid, skin, tint).
        // "7": screen effects (game_text, env_fade, env_shake).
        // "8": shakes carry the map's clock; screen clear payload.
        // "9": prop states carry an animated prop's sequence and the map's clock.
        // "10": movers name the bundle mover they carry apart from their entity; prop mounts.
        var registrar = event.registrar("10");
        registrar.playToClient(PlacementSyncPayload.TYPE, PlacementSyncPayload.STREAM_CODEC,
            (payload, context) -> {
                PlacementIndex index = new PlacementIndex();
                payload.placements().forEach(index::register);
                CLIENT.put(payload.dimension(), index);
                // Registration already moved the epoch, but before the index was published.
                PlacementIndex.touch();
                // The client draws from its own light engine, so it bakes the
                // map's sky light itself from the same bundle the server used.
                if (context.player().level() instanceof net.minecraft.world.level.Level level) {
                    dev.theredja.src2mc.world.LightOcclusion.publishClient(level, payload.dimension(), index);
                }
            });
        // Handlers run on the client main thread, after the block updates the server sent first.
        registrar.playToClient(SurfaceChangePayload.TYPE, SurfaceChangePayload.STREAM_CODEC,
            (payload, context) -> dev.theredja.src2mc.client.ClientLightRefresh.onSurfaceBlocksChanged(payload.dimension(), payload.sections()));
        dev.theredja.src2mc.logic.LogicNetwork.register(registrar);
        dev.theredja.src2mc.logic.MoverNetwork.register(registrar);
        dev.theredja.src2mc.logic.PropSync.register(registrar);
        dev.theredja.src2mc.logic.ScreenEffects.register(registrar);
    }

    public static PlacementIndex clientIndex(ResourceLocation dimension) { return CLIENT.getOrDefault(dimension, new PlacementIndex()); }

    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) send(player);
    }

    public static void send(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        PacketDistributor.sendToPlayer(player, payload(level));
    }

    public static void broadcast(ServerLevel level) {
        PacketDistributor.sendToPlayersInDimension(level, payload(level));
    }

    private static PlacementSyncPayload payload(ServerLevel level) {
        return new PlacementSyncPayload(level.dimension().location(), PlacementSavedData.get(level).index().view());
    }
}
