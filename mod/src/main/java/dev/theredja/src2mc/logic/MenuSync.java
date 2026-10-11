package dev.theredja.src2mc.logic;

import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.menu.MenuNetwork;
import dev.theredja.src2mc.menu.MenuState;
import dev.theredja.src2mc.world.PropStates;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Sends each running map's menu entities ({@link MenuState}: viewcontrols and vgui screens) to the players of its
 * dimension whenever they change, and takes a panel's {@code engine} command back. A glide is sent once, by where
 * it started; the clients draw every frame of it themselves.
 */
public final class MenuSync {
    private MenuSync() {}

    /** What went out last per map, to send only changes. */
    private static final Map<PropStates.Key, MenuState> SENT = new HashMap<>();

    /** After the map's logic ticked: tells the dimension when its menu state changed. */
    static void update(ServerLevel level, MapLogic logic) {
        PropStates.Key key = PropStates.key(level, logic.placement);
        MenuState state = logic.menu(level.getGameTime());
        if (state.equals(SENT.get(key)) || state.views().isEmpty() && state.screens().isEmpty() && !SENT.containsKey(key)) return;
        SENT.put(key, state);
        PacketDistributor.sendToPlayersInDimension(level, new MenuNetwork.StatePayload(key.dimension(), key.anchor(), state));
    }

    /** A map whose logic stopped has no menu: its record goes, and the clients drop the camera. */
    static void forget(ServerLevel level, MapLogic logic) {
        PropStates.Key key = PropStates.key(level, logic.placement);
        if (SENT.remove(key) != null) PacketDistributor.sendToPlayersInDimension(level, new MenuNetwork.StatePayload(key.dimension(), key.anchor(), null));
    }

    /** Every menu of the player's dimension, for a player arriving in it. */
    static void sendAll(ServerPlayer player) {
        ResourceLocation dimension = player.serverLevel().dimension().location();
        for (Map.Entry<PropStates.Key, MenuState> entry : SENT.entrySet()) {
            if (entry.getKey().dimension().equals(dimension)) {
                PacketDistributor.sendToPlayer(player, new MenuNetwork.StatePayload(dimension, entry.getKey().anchor(), entry.getValue()));
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

    /**
     * A panel's {@code engine ent_fire <target> <input> [parameter]}: fired in the sender's map as
     * {@code /src2mc logic fire} fires, the sender being the activator. Anything else is refused, as is a
     * map with no shown screen, so a client cannot drive a map that has no menu.
     */
    public static void fire(ServerPlayer player, String command) {
        String[] parts = command.trim().split("\\s+", 4);
        if (parts.length < 3 || !parts[0].toLowerCase(Locale.ROOT).equals("ent_fire")) {
            Src2mc.LOGGER.warn("src2mc menu: {} sent the unsupported engine command \"{}\"", player.getGameProfile().getName(), command);
            return;
        }
        String failure = LogicSystem.fireFromScreen(player, parts[1], parts[2], parts.length > 3 ? parts[3] : null);
        if (failure != null) Src2mc.LOGGER.warn("src2mc menu: {}'s \"{}\" was dropped: {}", player.getGameProfile().getName(), command, failure);
    }
}
