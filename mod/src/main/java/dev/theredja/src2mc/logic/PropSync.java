package dev.theredja.src2mc.logic;

import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.bundle.LogicPropTable;
import dev.theredja.src2mc.world.PropMounts;
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

    /**
     * The states of one placement's props; {@code full} replaces every one the client held for it.
     * {@code now} is the map's time when sent, which the clients advance animations from.
     */
    public record StatePayload(ResourceLocation dimension, long anchor, boolean full, double now, int[] entities,
                               PropStates.State[] states) implements CustomPacketPayload {
        public static final Type<StatePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Src2mc.MOD_ID, "prop_state"));
        public static final StreamCodec<FriendlyByteBuf, StatePayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeResourceLocation(payload.dimension);
                buffer.writeLong(payload.anchor);
                buffer.writeBoolean(payload.full);
                buffer.writeDouble(payload.now);
                buffer.writeVarInt(payload.entities.length);
                for (int i = 0; i < payload.entities.length; i++) {
                    PropStates.State state = payload.states[i];
                    buffer.writeVarInt(payload.entities[i]);
                    buffer.writeVarInt(state.flags());
                    buffer.writeVarInt(state.skin());
                    buffer.writeInt(state.color());
                    buffer.writeVarInt(state.sequence() + 1);
                    if (state.sequence() < 0) continue;
                    buffer.writeFloat(state.cycle());
                    buffer.writeFloat(state.rate());
                    buffer.writeDouble(state.time());
                    buffer.writeVarInt(state.parity());
                    buffer.writeVarInt(state.pose() + 1);
                }
            },
            buffer -> {
                ResourceLocation dimension = buffer.readResourceLocation();
                long anchor = buffer.readLong();
                boolean full = buffer.readBoolean();
                double now = buffer.readDouble();
                int count = buffer.readVarInt();
                if (count < 0 || count > MAX_RECORDS) throw new DecoderException("invalid src2mc prop state count " + count);
                int[] entities = new int[count];
                PropStates.State[] states = new PropStates.State[count];
                for (int i = 0; i < count; i++) {
                    entities[i] = buffer.readVarInt();
                    int flags = buffer.readVarInt(), skin = buffer.readVarInt(), color = buffer.readInt();
                    int sequence = buffer.readVarInt() - 1;
                    if (sequence < 0) { states[i] = new PropStates.State(flags, skin, color); continue; }
                    float cycle = buffer.readFloat(), rate = buffer.readFloat();
                    double time = buffer.readDouble();
                    int parity = buffer.readVarInt(), pose = buffer.readVarInt() - 1;
                    if (!Float.isFinite(cycle) || !Float.isFinite(rate) || !Double.isFinite(time)) throw new DecoderException("non-finite src2mc prop animation");
                    states[i] = new PropStates.State(flags, skin, color, sequence, cycle, rate, time, parity, pose);
                }
                return new StatePayload(dimension, anchor, full, now, entities, states);
            });

        @Override public PropStates.State[] states() { return states.clone(); }
        @Override public int[] entities() { return entities.clone(); }

        public StatePayload {
            entities = entities.clone(); states = states.clone();
            if (states.length != entities.length) throw new IllegalArgumentException("prop state count mismatch");
        }

        static StatePayload of(ResourceLocation dimension, long anchor, boolean full, double now, Map<Integer, PropStates.State> states) {
            int n = states.size(), i = 0;
            int[] entities = new int[n];
            PropStates.State[] values = new PropStates.State[n];
            for (Map.Entry<Integer, PropStates.State> entry : states.entrySet()) {
                entities[i] = entry.getKey();
                values[i++] = entry.getValue();
            }
            return new StatePayload(dimension, anchor, full, now, entities, values);
        }

        Map<Integer, PropStates.State> stateMap() {
            Map<Integer, PropStates.State> out = new HashMap<>();
            for (int i = 0; i < entities.length; i++) out.put(entities[i], states[i]);
            return out;
        }

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Every mount of one placement (see {@link PropMounts}), replacing those the client held. */
    public record MountPayload(ResourceLocation dimension, long anchor, java.util.List<PropMounts.Mount> mounts) implements CustomPacketPayload {
        public static final Type<MountPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Src2mc.MOD_ID, "prop_mounts"));
        public static final StreamCodec<FriendlyByteBuf, MountPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeResourceLocation(payload.dimension);
                buffer.writeLong(payload.anchor);
                buffer.writeVarInt(payload.mounts.size());
                for (PropMounts.Mount mount : payload.mounts) {
                    buffer.writeVarInt(mount.entity());
                    buffer.writeVarInt(mount.source());
                    buffer.writeVarInt(mount.mover() + 1);
                    for (double value : new double[]{mount.qx(), mount.qy(), mount.qz(), mount.qw(), mount.x(), mount.y(), mount.z()}) buffer.writeDouble(value);
                }
            },
            buffer -> {
                ResourceLocation dimension = buffer.readResourceLocation();
                long anchor = buffer.readLong();
                int count = buffer.readVarInt();
                if (count < 0 || count > MAX_RECORDS) throw new DecoderException("invalid src2mc prop mount count " + count);
                java.util.List<PropMounts.Mount> mounts = new java.util.ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    int entity = buffer.readVarInt(), source = buffer.readVarInt(), mover = buffer.readVarInt() - 1;
                    double[] v = new double[7];
                    for (int k = 0; k < 7; k++) {
                        v[k] = buffer.readDouble();
                        if (!Double.isFinite(v[k])) throw new DecoderException("non-finite src2mc prop mount");
                    }
                    mounts.add(new PropMounts.Mount(entity, source, mover, v[0], v[1], v[2], v[3], v[4], v[5], v[6]));
                }
                return new MountPayload(dimension, anchor, mounts);
            });

        public MountPayload { mounts = java.util.List.copyOf(mounts); }

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** What each placement's clients were last told of its mounts, by placement. */
    private static final Map<PropStates.Key, java.util.List<MapLogic.Mount>> SENT_MOUNTS = new HashMap<>();

    public static void register(PayloadRegistrar registrar) {
        registrar.playToClient(MountPayload.TYPE, MountPayload.STREAM_CODEC, (payload, context) -> {
            Map<Integer, PropMounts.Mount> mounts = new HashMap<>();
            for (PropMounts.Mount mount : payload.mounts()) mounts.put(mount.entity(), mount);
            PropMounts.replace(new PropStates.Key(payload.dimension(), payload.anchor()), mounts);
        });
        registrar.playToClient(StatePayload.TYPE, StatePayload.STREAM_CODEC, (payload, context) -> {
            PropStates.Key key = new PropStates.Key(payload.dimension(), payload.anchor());
            PropStates.syncClock(key, payload.now(), dev.theredja.src2mc.client.logic.ScreenOverlay.clock());
            if (payload.full()) PropStates.replace(true, key, payload.stateMap());
            else payload.stateMap().forEach((entity, state) -> PropStates.set(true, key, entity, state));
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
        // Copies by their own slots; a mount draws them.
        java.util.List<MapLogic.Mount> mounts = logic.mounts();
        for (MapLogic.Mount mount : mounts) {
            if (!logic.isCopy(logic.entity(mount.entity()))) continue;
            PropStates.State state = logic.entity(mount.entity()).propState();
            if (PropStates.set(false, key, mount.entity(), state)) changed.put(mount.entity(), state);
        }
        if (!sameMounts(SENT_MOUNTS.get(key), mounts)) {
            SENT_MOUNTS.put(key, mounts);
            MountPayload payload = mountPayload(key, mounts);
            Map<Integer, PropMounts.Mount> byEntity = new HashMap<>();
            for (PropMounts.Mount mount : payload.mounts()) byEntity.put(mount.entity(), mount);
            PropMounts.replace(false, key, byEntity);
            PacketDistributor.sendToPlayersInDimension(level, payload);
        }
        if (!changed.isEmpty())
            PacketDistributor.sendToPlayersInDimension(level, StatePayload.of(key.dimension(), key.anchor(), false, logic.time(), changed));
    }

    /** Every state of the player's dimension, each placement's whole, and its mounts. */
    static void sendAll(ServerPlayer player) {
        ResourceLocation dimension = player.serverLevel().dimension().location();
        for (PropStates.Key key : PropStates.keys(false)) {
            if (!key.dimension().equals(dimension)) continue;
            PacketDistributor.sendToPlayer(player, StatePayload.of(dimension, key.anchor(), true, LogicSystem.mapTime(player.serverLevel(), key),
                PropStates.stored(false, key)));
        }
        for (Map.Entry<PropStates.Key, java.util.List<MapLogic.Mount>> entry : SENT_MOUNTS.entrySet()) {
            if (entry.getKey().dimension().equals(dimension)) PacketDistributor.sendToPlayer(player, mountPayload(entry.getKey(), entry.getValue()));
        }
    }

    /** Whether two mount lists hold the same entities on the same movers, moved alike to a thousandth of a millimetre. */
    private static boolean sameMounts(java.util.List<MapLogic.Mount> sent, java.util.List<MapLogic.Mount> now) {
        if (sent == null) return now.isEmpty();
        if (sent.size() != now.size()) return false;
        for (int i = 0; i < now.size(); i++) {
            MapLogic.Mount a = sent.get(i), b = now.get(i);
            if (a.entity() != b.entity() || a.source() != b.source() || a.mover() != b.mover() || !a.motion().near(b.motion(), 1e-6)) return false;
        }
        return true;
    }

    private static MountPayload mountPayload(PropStates.Key key, java.util.List<MapLogic.Mount> mounts) {
        java.util.List<PropMounts.Mount> out = new java.util.ArrayList<>(mounts.size());
        for (MapLogic.Mount mount : mounts) {
            Rigid m = mount.motion();
            out.add(new PropMounts.Mount(mount.entity(), mount.source(), mount.mover(), m.qx(), m.qy(), m.qz(), m.qw(), m.x(), m.y(), m.z()));
        }
        return new MountPayload(key.dimension(), key.anchor(), out);
    }

    /** Forgets what was sent, for a server stop. */
    static void clear() {
        SENT_MOUNTS.clear();
        PropMounts.clear(false);
    }

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) sendAll(player);
    }

    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) sendAll(player);
    }
}
