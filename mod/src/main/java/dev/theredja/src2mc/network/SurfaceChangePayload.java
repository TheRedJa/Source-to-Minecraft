package dev.theredja.src2mc.network;

import dev.theredja.src2mc.Src2mc;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** World sections of one dimension whose {@code src2mc:surface} blocks changed; packed {@link net.minecraft.core.SectionPos} longs. */
public record SurfaceChangePayload(ResourceLocation dimension, long[] sections) implements CustomPacketPayload {
    public static final Type<SurfaceChangePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Src2mc.MOD_ID, "surface_changes"));
    /** 256 KiB of section keys, well inside the 1 MiB client-bound custom payload ceiling. */
    public static final int MAX_SECTIONS = 32768;
    public static final StreamCodec<RegistryFriendlyByteBuf, SurfaceChangePayload> STREAM_CODEC = new StreamCodec<>() {
        @Override public SurfaceChangePayload decode(RegistryFriendlyByteBuf buffer) {
            ResourceLocation dimension = buffer.readResourceLocation();
            int count = buffer.readVarInt();
            if (count < 0 || count > MAX_SECTIONS) throw new DecoderException("invalid src2mc surface change count " + count);
            long[] sections = new long[count];
            for (int i = 0; i < count; i++) sections[i] = buffer.readLong();
            return new SurfaceChangePayload(dimension, sections);
        }
        @Override public void encode(RegistryFriendlyByteBuf buffer, SurfaceChangePayload payload) {
            buffer.writeResourceLocation(payload.dimension()); buffer.writeVarInt(payload.sections.length);
            for (long section : payload.sections) buffer.writeLong(section);
        }
    };
    public SurfaceChangePayload { sections = sections.clone(); }
    @Override public long[] sections() { return sections.clone(); }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
