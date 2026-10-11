package dev.theredja.src2mc.levels;

import dev.theredja.src2mc.Src2mc;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Server-to-client messages of the one-world-per-map level changes. */
public final class LevelNetwork {
    private LevelNetwork() {}

    /** Switch to the world of {@code mapId}; the transition itself waits in {@link LevelTransition}. */
    public record ChangePayload(String campaignId, String mapId) implements CustomPacketPayload {
        public static final Type<ChangePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Src2mc.MOD_ID, "level_change"));
        public static final StreamCodec<FriendlyByteBuf, ChangePayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> { buffer.writeUtf(payload.campaignId); buffer.writeUtf(payload.mapId); },
            buffer -> new ChangePayload(buffer.readUtf(), buffer.readUtf()));

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** The map is placed and the player stands in it ({@code ok}), or loading failed with {@code message}. */
    public record LoadedPayload(boolean ok, String message) implements CustomPacketPayload {
        public static final Type<LoadedPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Src2mc.MOD_ID, "level_loaded"));
        public static final StreamCodec<FriendlyByteBuf, LoadedPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> { buffer.writeBoolean(payload.ok); buffer.writeUtf(payload.message); },
            buffer -> new LoadedPayload(buffer.readBoolean(), buffer.readUtf()));

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Placement progress 0..1 while the map is being placed, for the loading screen's bar. */
    public record ProgressPayload(float progress) implements CustomPacketPayload {
        public static final Type<ProgressPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Src2mc.MOD_ID, "level_progress"));
        public static final StreamCodec<FriendlyByteBuf, ProgressPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> buffer.writeFloat(payload.progress),
            buffer -> new ProgressPayload(buffer.readFloat()));

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Client to server: the loading screen of the level change closed, so the arrival may start the map's clock. */
    public record ReadyPayload() implements CustomPacketPayload {
        public static final Type<ReadyPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Src2mc.MOD_ID, "level_ready"));
        public static final StreamCodec<FriendlyByteBuf, ReadyPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> { }, buffer -> new ReadyPayload());

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void register(PayloadRegistrar registrar) {
        registrar.playToServer(ReadyPayload.TYPE, ReadyPayload.STREAM_CODEC, (payload, context) -> {
            if (context.player() instanceof net.minecraft.server.level.ServerPlayer player)
                dev.theredja.src2mc.logic.LogicSystem.release(player.getUUID());
        });
        registrar.playToClient(ChangePayload.TYPE, ChangePayload.STREAM_CODEC,
            (payload, context) -> dev.theredja.src2mc.client.levels.LevelChangeClient.onChange(payload));
        registrar.playToClient(LoadedPayload.TYPE, LoadedPayload.STREAM_CODEC,
            (payload, context) -> dev.theredja.src2mc.client.levels.LevelChangeClient.onLoaded(payload));
        registrar.playToClient(ProgressPayload.TYPE, ProgressPayload.STREAM_CODEC,
            (payload, context) -> dev.theredja.src2mc.client.levels.LevelChangeClient.onProgress(payload));
    }
}
