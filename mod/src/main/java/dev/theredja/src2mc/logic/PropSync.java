package dev.theredja.src2mc.logic;

import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.bundle.LogicPropTable;
import dev.theredja.src2mc.world.PropStates;
import io.netty.handler.codec.DecoderException;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Takes each running map's logic prop states (format.md section 18) from its entities after the
 * logic ticks, and tells the dimension's players those that changed; a player who arrives gets
 * every state the dimension holds. A map whose logic stops keeps its last states, as its movers do.
 */
public final class PropSync {
    private PropSync() {}

    private static final int MAX_RECORDS = 1 << 20;

    /** The states of one placement's props; {@code full} replaces every one the client held for it. */
    public record StatePayload(ResourceLocation dimension, long anchor, boolean full, int[] entities, int[] flags,
                               int[] skins, int[] colors) implements CustomPacketPayload {
        public static final Type<StatePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Src2mc.MOD_ID, "prop_state"));
        public static final StreamCodec<FriendlyByteBuf, StatePayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeResourceLocation(payload.dimension);
                buffer.writeLong(payload.anchor);
                buffer.writeBoolean(payload.full);
                buffer.writeVarInt(payload.entities.length);
                for (int i = 0; i < payload.entities.length; i++) {
                    buffer.writeVarInt(payload.entities[i]);
                    buffer.writeVarInt(payload.flags[i]);
                    buffer.writeVarInt(payload.skins[i]);
                    buffer.writeInt(payload.colors[i]);
                }
            },
            buffer -> {
                ResourceLocation dimension = buffer.readResourceLocation();
                long anchor = buffer.readLong();
                boolean full = buffer.readBoolean();
                int count = buffer.readVarInt();
                if (count < 0 || count > MAX_RECORDS) throw new DecoderException("invalid src2mc prop state count " + count);
                int[] entities = new int[count], flags = new int[count], skins = new int[count], colors = new int[count];
                for (int i = 0; i < count; i++) {
                    entities[i] = buffer.readVarInt();
                    flags[i] = buffer.readVarInt();
                    skins[i] = buffer.readVarInt();
                    colors[i] = buffer.readInt();
                }
                return new StatePayload(dimension, anchor, full, entities, flags, skins, colors);
            });

        public StatePayload {
            entities = entities.clone(); flags = flags.clone(); skins = skins.clone(); colors = colors.clone();
            if (flags.length != entities.length || skins.length != entities.length || colors.length != entities.length)
                throw new IllegalArgumentException("prop state count mismatch");
        }

        static StatePayload of(ResourceLocation dimension, long anchor, boolean full, Map<Integer, PropStates.State> states) {
            int n = states.size(), i = 0;
            int[] entities = new int[n], flags = new int[n], skins = new int[n], colors = new int[n];
            for (Map.Entry<Integer, PropStates.State> entry : states.entrySet()) {
                entities[i] = entry.getKey();
                flags[i] = entry.getValue().flags();
                skins[i] = entry.getValue().skin();
                colors[i++] = entry.getValue().color();
            }
            return new StatePayload(dimension, anchor, full, entities, flags, skins, colors);
        }

        Map<Integer, PropStates.State> states() {
            Map<Integer, PropStates.State> states = new HashMap<>();
            for (int i = 0; i < entities.length; i++) states.put(entities[i], new PropStates.State(flags[i], skins[i], colors[i]));
            return states;
        }

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void register(PayloadRegistrar registrar) {
        registrar.playToClient(StatePayload.TYPE, StatePayload.STREAM_CODEC, (payload, context) -> {
            PropStates.Key key = new PropStates.Key(payload.dimension(), payload.anchor());
            if (payload.full()) PropStates.replace(true, key, payload.states());
            else payload.states().forEach((entity, state) -> PropStates.set(true, key, entity, state));
        });
    }

    /** After the map's logic ticked: stores what changed and tells the dimension. */
    static void update(ServerLevel level, MapLogic logic) {
        LogicPropTable table = logic.map.logicProps();
        if (table == null) return;
        PropStates.Key key = PropStates.key(level, logic.placement);
        Map<Integer, PropStates.State> changed = new HashMap<>();
        for (LogicPropTable.Prop prop : table.props()) {
            LogicEntity entity = logic.entity(prop.entity());
            if (entity == null) continue;
            PropStates.State state = entity.propState();
            if (PropStates.set(false, key, prop.entity(), state)) changed.put(prop.entity(), state);
        }
        if (!changed.isEmpty()) PacketDistributor.sendToPlayersInDimension(level, StatePayload.of(key.dimension(), key.anchor(), false, changed));
    }

    /** Every state of the player's dimension, each placement's whole. */
    static void sendAll(ServerPlayer player) {
        ResourceLocation dimension = player.serverLevel().dimension().location();
        for (PropStates.Key key : PropStates.keys(false)) {
            if (!key.dimension().equals(dimension)) continue;
            PacketDistributor.sendToPlayer(player, StatePayload.of(dimension, key.anchor(), true, PropStates.stored(false, key)));
        }
    }

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) sendAll(player);
    }

    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) sendAll(player);
    }
}
