package dev.theredja.src2mc.menu;

import dev.theredja.src2mc.Src2mc;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** The menu's messages: a map's menu state to its players, and a panel's {@code engine} command and a game start back. */
public final class MenuNetwork {
    private MenuNetwork() {}

    /** Longest {@code engine} command a client may send. */
    public static final int MAX_COMMAND = 256;

    /** The menu entities of the map at {@code anchor} in {@code dimension}; {@code state} null when its logic stopped. */
    public record StatePayload(ResourceLocation dimension, long anchor, MenuState state) implements CustomPacketPayload {
        public static final Type<StatePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Src2mc.MOD_ID, "menu_state"));
        public static final StreamCodec<FriendlyByteBuf, StatePayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeResourceLocation(payload.dimension);
                buffer.writeLong(payload.anchor);
                buffer.writeBoolean(payload.state != null);
                if (payload.state != null) MenuState.write(buffer, payload.state);
            },
            buffer -> new StatePayload(buffer.readResourceLocation(), buffer.readLong(), buffer.readBoolean() ? MenuState.read(buffer) : null));

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** A panel's {@code engine <command>}: {@code ent_fire <target> <input> [parameter]}, fired in the sender's map. */
    public record FirePayload(String command) implements CustomPacketPayload {
        public static final Type<FirePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Src2mc.MOD_ID, "menu_fire"));
        public static final StreamCodec<FriendlyByteBuf, FirePayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> buffer.writeUtf(payload.command, MAX_COMMAND),
            buffer -> new FirePayload(buffer.readUtf(MAX_COMMAND)));

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * The menu's Continue ({@code resume}: rejoin the map's existing world as saved) or a chapter start (a fresh
     * player at {@code info_player_start}), sent from the menu map; the server hands the player over as a level change.
     */
    public record StartPayload(boolean resume, String campaignId, String mapId) implements CustomPacketPayload {
        public static final Type<StartPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Src2mc.MOD_ID, "menu_start"));
        public static final StreamCodec<FriendlyByteBuf, StartPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> { buffer.writeBoolean(payload.resume); buffer.writeUtf(payload.campaignId, 128); buffer.writeUtf(payload.mapId, 128); },
            buffer -> new StartPayload(buffer.readBoolean(), buffer.readUtf(128), buffer.readUtf(128)));

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void register(PayloadRegistrar registrar) {
        registrar.playToClient(StatePayload.TYPE, StatePayload.STREAM_CODEC,
            (payload, context) -> dev.theredja.src2mc.client.menu.MenuClient.receive(payload));
        registrar.playToServer(FirePayload.TYPE, FirePayload.STREAM_CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) dev.theredja.src2mc.logic.MenuSync.fire(player, payload.command());
        });
        registrar.playToServer(StartPayload.TYPE, StartPayload.STREAM_CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) dev.theredja.src2mc.levels.MenuStart.start(player, payload);
        });
    }
}
