package dev.theredja.src2mc.logic;

import dev.theredja.src2mc.Src2mc;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * What the map logic tells the clients, and what a client tells it. The server sends each running
 * map's sound state as it changes, and a full copy to a player who arrives; a client sends the use
 * key pressed on, or held on, an entity it can use.
 */
public final class LogicNetwork {
    private LogicNetwork() {}

    private static final int MAX_RECORDS = 1 << 16;

    /**
     * One entity's sound as the clients keep it: whether it plays, a serial that changes each time
     * it starts over, its level 0..1 and pitch factor (each -1 for the sound's own) and, where the
     * server chose it, which of its files ({@code sound} into the sound table, else -1).
     */
    public record SoundState(int entity, boolean on, int serial, float volume, float pitch, int sound) {}

    /**
     * A sound played once: {@code script} names the sound table's script; {@code at} is map-local or
     * null; {@code speaker} the player who says it, or null; {@code caption} a caption token or null;
     * {@code duration} the line's length in seconds, or -1; {@code volume} a level or -1; {@code voice}
     * whether it is speech rather than a block's noise.
     */
    public record SoundEvent(long id, String script, double[] at, UUID speaker, String caption, float duration, float volume,
                             boolean voice) {}

    /** The sound changes of one tick, or a full copy. */
    record Update(List<SoundState> states, List<SoundEvent> events, List<Long> stops) {
        boolean isEmpty() { return states.isEmpty() && events.isEmpty() && stops.isEmpty(); }
    }

    /**
     * One map's sound update. {@code running} false means the map's logic has stopped and the client
     * goes back to what the map does without it; {@code full} replaces every state it held.
     */
    public record SyncPayload(ResourceLocation dimension, long anchor, boolean running, boolean full,
                              List<SoundState> states, List<SoundEvent> events, List<Long> stops) implements CustomPacketPayload {
        public static final Type<SyncPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Src2mc.MOD_ID, "logic_sync"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SyncPayload> STREAM_CODEC = new StreamCodec<>() {
            @Override public SyncPayload decode(RegistryFriendlyByteBuf buffer) {
                ResourceLocation dimension = buffer.readResourceLocation();
                long anchor = buffer.readLong();
                boolean running = buffer.readBoolean(), full = buffer.readBoolean();
                int stateCount = count(buffer);
                List<SoundState> states = new ArrayList<>(stateCount);
                for (int i = 0; i < stateCount; i++) {
                    states.add(new SoundState(buffer.readVarInt(), buffer.readBoolean(), buffer.readVarInt(), buffer.readFloat(),
                        buffer.readFloat(), buffer.readVarInt() - 1));
                }
                int eventCount = count(buffer);
                List<SoundEvent> events = new ArrayList<>(eventCount);
                for (int i = 0; i < eventCount; i++) {
                    long id = buffer.readVarLong();
                    String script = buffer.readUtf(1024);
                    double[] at = buffer.readBoolean() ? new double[]{buffer.readDouble(), buffer.readDouble(), buffer.readDouble()} : null;
                    UUID speaker = buffer.readBoolean() ? buffer.readUUID() : null;
                    String caption = buffer.readBoolean() ? buffer.readUtf(1024) : null;
                    events.add(new SoundEvent(id, script, at, speaker, caption, buffer.readFloat(), buffer.readFloat(), buffer.readBoolean()));
                }
                int stopCount = count(buffer);
                List<Long> stops = new ArrayList<>(stopCount);
                for (int i = 0; i < stopCount; i++) stops.add(buffer.readVarLong());
                return new SyncPayload(dimension, anchor, running, full, states, events, stops);
            }

            @Override public void encode(RegistryFriendlyByteBuf buffer, SyncPayload payload) {
                buffer.writeResourceLocation(payload.dimension);
                buffer.writeLong(payload.anchor);
                buffer.writeBoolean(payload.running);
                buffer.writeBoolean(payload.full);
                buffer.writeVarInt(payload.states.size());
                for (SoundState state : payload.states) {
                    buffer.writeVarInt(state.entity()); buffer.writeBoolean(state.on()); buffer.writeVarInt(state.serial());
                    buffer.writeFloat(state.volume()); buffer.writeFloat(state.pitch()); buffer.writeVarInt(state.sound() + 1);
                }
                buffer.writeVarInt(payload.events.size());
                for (SoundEvent event : payload.events) {
                    buffer.writeVarLong(event.id());
                    buffer.writeUtf(event.script(), 1024);
                    buffer.writeBoolean(event.at() != null);
                    if (event.at() != null) for (double value : event.at()) buffer.writeDouble(value);
                    buffer.writeBoolean(event.speaker() != null);
                    if (event.speaker() != null) buffer.writeUUID(event.speaker());
                    buffer.writeBoolean(event.caption() != null);
                    if (event.caption() != null) buffer.writeUtf(event.caption(), 1024);
                    buffer.writeFloat(event.duration()); buffer.writeFloat(event.volume()); buffer.writeBoolean(event.voice());
                }
                buffer.writeVarInt(payload.stops.size());
                for (long id : payload.stops) buffer.writeVarLong(id);
            }
        };

        public SyncPayload {
            states = List.copyOf(states);
            events = List.copyOf(events);
            stops = List.copyOf(stops);
        }

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** The use key pressed ({@code pressed}) or still held on one entity of the map at {@code anchor}. */
    public record UsePayload(long anchor, int entity, boolean pressed) implements CustomPacketPayload {
        public static final Type<UsePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Src2mc.MOD_ID, "logic_use"));
        public static final StreamCodec<FriendlyByteBuf, UsePayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> { buffer.writeLong(payload.anchor); buffer.writeVarInt(payload.entity); buffer.writeBoolean(payload.pressed); },
            buffer -> new UsePayload(buffer.readLong(), buffer.readVarInt(), buffer.readBoolean()));

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private static int count(FriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_RECORDS) throw new DecoderException("invalid src2mc logic record count " + count);
        return count;
    }

    public static void register(PayloadRegistrar registrar) {
        registrar.playToClient(SyncPayload.TYPE, SyncPayload.STREAM_CODEC,
            (payload, context) -> dev.theredja.src2mc.client.logic.ClientLogic.apply(payload));
        registrar.playToServer(UsePayload.TYPE, UsePayload.STREAM_CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) LogicSystem.use(player, payload.anchor(), payload.entity(), payload.pressed());
        });
    }
}
