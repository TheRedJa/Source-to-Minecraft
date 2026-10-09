package dev.theredja.src2mc.logic;

import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.world.LookState;
import dev.theredja.src2mc.world.PropStates;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Sends each running map's view state ({@link LookState.View}) to the players of its dimension
 * whenever it changes, as Source networks its fog, tonemap and colour correction entities. The
 * map's clock goes along, so a fog transition runs on the client from where the server's is.
 */
public final class LookSync {
    private LookSync() {}

    /** {@code view} null: the map's logic stopped. */
    public record ViewPayload(ResourceLocation dimension, long anchor, double now, LookState.View view) implements CustomPacketPayload {
        public static final Type<ViewPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Src2mc.MOD_ID, "look_view"));
        public static final StreamCodec<FriendlyByteBuf, ViewPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeResourceLocation(p.dimension());
                buf.writeLong(p.anchor());
                buf.writeDouble(p.now());
                buf.writeBoolean(p.view() != null);
                if (p.view() != null) LookState.write(buf, p.view());
            },
            buf -> new ViewPayload(buf.readResourceLocation(), buf.readLong(), buf.readDouble(),
                buf.readBoolean() ? LookState.read(buf) : null));

        @Override public Type<ViewPayload> type() { return TYPE; }
    }

    /** What went out last per map, to send only changes. */
    private static final Map<PropStates.Key, LookState.View> SENT = new HashMap<>();

    public static void register(PayloadRegistrar registrar) {
        registrar.playToClient(ViewPayload.TYPE, ViewPayload.STREAM_CODEC,
            (payload, context) -> dev.theredja.src2mc.client.look.LookClient.receive(payload));
    }

    /** After the map's logic ticked: tells the dimension when its view changed. */
    static void update(ServerLevel level, MapLogic logic) {
        PropStates.Key key = PropStates.key(level, logic.placement);
        LookState.View view = logic.view();
        if (view.equals(SENT.get(key))) return;
        SENT.put(key, view);
        PacketDistributor.sendToPlayersInDimension(level, new ViewPayload(key.dimension(), key.anchor(), logic.time(), view));
    }

    /** Every view of the player's dimension, for a player arriving in it. */
    static void sendAll(ServerPlayer player) {
        ResourceLocation dimension = player.serverLevel().dimension().location();
        for (Map.Entry<PropStates.Key, LookState.View> entry : SENT.entrySet()) {
            PropStates.Key key = entry.getKey();
            if (!key.dimension().equals(dimension)) continue;
            PacketDistributor.sendToPlayer(player, new ViewPayload(dimension, key.anchor(),
                LogicSystem.mapTime(player.serverLevel(), key), entry.getValue()));
        }
    }

    /** A map whose logic stopped looks as it spawns again: its record goes, and the client falls back. */
    static void forget(ServerLevel level, MapLogic logic) {
        PropStates.Key key = PropStates.key(level, logic.placement);
        if (SENT.remove(key) != null) {
            PacketDistributor.sendToPlayersInDimension(level, new ViewPayload(key.dimension(), key.anchor(), logic.time(), null));
        }
    }

    static void clear() { SENT.clear(); }

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) sendAll(player);
    }

    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        Player entity = event.getEntity();
        if (entity instanceof ServerPlayer player) sendAll(player);
    }
}
