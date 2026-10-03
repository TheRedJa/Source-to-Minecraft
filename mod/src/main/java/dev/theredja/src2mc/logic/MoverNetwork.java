package dev.theredja.src2mc.logic;

import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.world.MoverRegistry;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Which Sable sub-level carries which map's mover, sent whole to the players of a dimension whenever it changes. */
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
                        buffer.readVarInt(), buffer.readUUID()));
                }
                return new SyncPayload(dimension, instances);
            });

        public SyncPayload { instances = List.copyOf(instances); }

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void register(PayloadRegistrar registrar) {
        registrar.playToClient(SyncPayload.TYPE, SyncPayload.STREAM_CODEC,
            (payload, context) -> MoverRegistry.replaceClient(payload.instances()));
    }
}
