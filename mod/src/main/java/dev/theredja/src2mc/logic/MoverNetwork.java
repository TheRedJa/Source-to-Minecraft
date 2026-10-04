package dev.theredja.src2mc.logic;

import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.world.MoverRegistry;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Which Sable sub-level carries which map's mover, sent whole to the players of a dimension
 * whenever it changes, and each mover's shown and solid state, sent as the logic changes it.
 */
public final class MoverNetwork {
    private MoverNetwork() {}

    private static final int MAX_RECORDS = 1 << 20;

    public record SyncPayload(ResourceLocation dimension, List<MoverRegistry.Instance> instances) implements CustomPacketPayload {
        public static final Type<SyncPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Src2mc.MOD_ID, "mover_sync"));
        public static final StreamCodec<FriendlyByteBuf, SyncPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeResourceLocation(payload.dimension);
                buffer.writeVarInt(payload.instances.size());
                for (MoverRegistry.Instance instance : payload.instances) {
                    buffer.writeLong(instance.anchor());
                    buffer.writeUtf(instance.campaignId(), 256);
                    buffer.writeUtf(instance.mapId(), 256);
                    buffer.writeVarInt(instance.entity());
                    buffer.writeVarInt(instance.source());
                    buffer.writeUUID(instance.subLevel());
                }
            },
            buffer -> {
                ResourceLocation dimension = buffer.readResourceLocation();
                int count = buffer.readVarInt();
                if (count < 0 || count > MAX_RECORDS) throw new DecoderException("invalid src2mc mover count " + count);
                List<MoverRegistry.Instance> instances = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    instances.add(new MoverRegistry.Instance(buffer.readLong(), buffer.readUtf(256), buffer.readUtf(256),
                        buffer.readVarInt(), buffer.readVarInt(), buffer.readUUID()));
                }
                return new SyncPayload(dimension, instances);
            });

        public SyncPayload { instances = List.copyOf(instances); }

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Sub-levels whose state ({@link MoverRegistry#HIDDEN}, {@link MoverRegistry#NOT_SOLID}) the logic changed; all of them after a sync. */
    public record StatePayload(List<UUID> subLevels, int[] states) implements CustomPacketPayload {
        public static final Type<StatePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Src2mc.MOD_ID, "mover_state"));
        public static final StreamCodec<FriendlyByteBuf, StatePayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeVarInt(payload.subLevels.size());
                for (int i = 0; i < payload.states.length; i++) {
                    buffer.writeUUID(payload.subLevels.get(i));
                    buffer.writeVarInt(payload.states[i]);
                }
            },
            buffer -> {
                int count = buffer.readVarInt();
                if (count < 0 || count > MAX_RECORDS) throw new DecoderException("invalid src2mc mover state count " + count);
                List<UUID> subLevels = new ArrayList<>(count);
                int[] states = new int[count];
                for (int i = 0; i < count; i++) {
                    subLevels.add(buffer.readUUID());
                    states[i] = buffer.readVarInt();
                }
                return new StatePayload(subLevels, states);
            });

        public StatePayload {
            subLevels = List.copyOf(subLevels);
            states = states.clone();
            if (subLevels.size() != states.length) throw new IllegalArgumentException("mover state count mismatch");
        }

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void register(PayloadRegistrar registrar) {
        registrar.playToClient(SyncPayload.TYPE, SyncPayload.STREAM_CODEC,
            (payload, context) -> MoverRegistry.replaceClient(payload.instances()));
        registrar.playToClient(StatePayload.TYPE, StatePayload.STREAM_CODEC, (payload, context) -> {
            for (int i = 0; i < payload.states().length; i++) MoverRegistry.setState(true, payload.subLevels().get(i), payload.states()[i]);
        });
    }
}
