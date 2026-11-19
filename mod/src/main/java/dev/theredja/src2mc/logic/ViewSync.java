package dev.theredja.src2mc.logic;

import dev.theredja.src2mc.view.ViewNetwork;
import dev.theredja.src2mc.view.ViewState;
import dev.theredja.src2mc.world.PropStates;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Sends each running map's camera entities ({@link ViewState}) to the players of its dimension whenever they change:
 * a blend or fade once, as it starts; a moving camera every tick it moves.
 */
public final class ViewSync {
    private ViewSync() {}

    private record Sent(long tick, ViewState state) {}

    private static final Map<PropStates.Key, Sent> SENT = new HashMap<>();

    static void update(ServerLevel level, MapLogic logic) {
        PropStates.Key key = PropStates.key(level, logic.placement);
        ViewState state = logic.view(level.getGameTime());
        Sent sent = SENT.get(key);
        if (sent != null ? state.equals(sent.state) : state.isEmpty()) return;
        SENT.put(key, new Sent(level.getGameTime(), state));
        PacketDistributor.sendToPlayersInDimension(level, new ViewNetwork.StatePayload(key.dimension(), key.anchor(), level.getGameTime(), state));
    }

    static void forget(ServerLevel level, MapLogic logic) {
        PropStates.Key key = PropStates.key(level, logic.placement);
        if (SENT.remove(key) != null) {
            PacketDistributor.sendToPlayersInDimension(level, new ViewNetwork.StatePayload(key.dimension(), key.anchor(), level.getGameTime(), null));
        }
    }

    static void sendAll(ServerPlayer player) {
        ResourceLocation dimension = player.serverLevel().dimension().location();
        for (Map.Entry<PropStates.Key, Sent> entry : SENT.entrySet()) {
            if (entry.getKey().dimension().equals(dimension)) {
                PacketDistributor.sendToPlayer(player, new ViewNetwork.StatePayload(dimension, entry.getKey().anchor(), entry.getValue().tick,
                    entry.getValue().state));
            }
        }
    }

    static void clear() { SENT.clear(); }

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) sendAll(player);
    }

    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) sendAll(player);
    }
}
