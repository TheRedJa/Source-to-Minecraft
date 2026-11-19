package dev.theredja.src2mc.view;

import dev.theredja.src2mc.Src2mc;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class ViewNetwork {
    private ViewNetwork() {}

    /** The camera entities of the map at {@code anchor} in {@code dimension}; {@code state} null when its logic stopped. */
    public record StatePayload(ResourceLocation dimension, long anchor, long tick, ViewState state) implements CustomPacketPayload {
        public static final Type<StatePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Src2mc.MOD_ID, "view_state"));
        public static final StreamCodec<FriendlyByteBuf, StatePayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeResourceLocation(payload.dimension);
                buffer.writeLong(payload.anchor);
                buffer.writeLong(payload.tick);
                buffer.writeBoolean(payload.state != null);
                if (payload.state != null) ViewState.write(buffer, payload.state);
            },
            buffer -> new StatePayload(buffer.readResourceLocation(), buffer.readLong(), buffer.readLong(),
                buffer.readBoolean() ? ViewState.read(buffer) : null));

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void register(PayloadRegistrar registrar) {
        registrar.playToClient(StatePayload.TYPE, StatePayload.STREAM_CODEC,
            (payload, context) -> dev.theredja.src2mc.client.view.ViewClient.receive(payload));
    }
}
