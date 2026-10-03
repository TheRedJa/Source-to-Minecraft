package dev.theredja.src2mc.logic;

import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.bundle.LogicTable;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * What the map puts on the players' screens, after the SDK's {@code maprules.cpp} ({@code game_text}),
 * {@code EnvFade.cpp} and {@code EnvShake.cpp} with {@code UTIL_HudMessage}, {@code UTIL_ScreenFade}
 * and {@code UTIL_ScreenShake}: the server decides who sees what, as Source does, and sends each
 * player the user message Source would; the client draws it as {@code CHudMessage} and
 * {@code CViewEffects} do. "Every player" is every player inside the map.
 */
public final class ScreenEffects {
    private ScreenEffects() {}

    /** {@code MAX_SHAKE_AMPLITUDE}. */
    static final float MAX_SHAKE_AMPLITUDE = 16;
    /** Source units per block, for the shake radius. */
    private static final double UNITS_PER_BLOCK = 32;

    // ---- The user messages.

    /** {@code HudMsg}: one text on one channel, {@code color1}/{@code color2} as {@code 0xRRGGBBAA}. */
    public record TextPayload(int channel, float x, float y, int color1, int color2, int effect, float fadein, float fadeout,
                              float holdtime, float fxtime, String text) implements CustomPacketPayload {
        public static final Type<TextPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Src2mc.MOD_ID, "hud_text"));
        public static final StreamCodec<FriendlyByteBuf, TextPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, p) -> {
                buffer.writeByte(p.channel); buffer.writeFloat(p.x); buffer.writeFloat(p.y);
                buffer.writeInt(p.color1); buffer.writeInt(p.color2); buffer.writeByte(p.effect);
                buffer.writeFloat(p.fadein); buffer.writeFloat(p.fadeout); buffer.writeFloat(p.holdtime); buffer.writeFloat(p.fxtime);
                buffer.writeUtf(p.text, 2048);
            },
            buffer -> new TextPayload(buffer.readUnsignedByte(), buffer.readFloat(), buffer.readFloat(), buffer.readInt(), buffer.readInt(),
                buffer.readUnsignedByte(), buffer.readFloat(), buffer.readFloat(), buffer.readFloat(), buffer.readFloat(), buffer.readUtf(2048)));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** {@code Fade}: {@code FFADE_*} flags, colour {@code 0xRRGGBBAA}, times already in Source's 7.9 fixed point. */
    public record FadePayload(float duration, float holdTime, int flags, int color) implements CustomPacketPayload {
        public static final Type<FadePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Src2mc.MOD_ID, "screen_fade"));
        public static final StreamCodec<FriendlyByteBuf, FadePayload> STREAM_CODEC = StreamCodec.of(
            (buffer, p) -> { buffer.writeFloat(p.duration); buffer.writeFloat(p.holdTime); buffer.writeShort(p.flags); buffer.writeInt(p.color); },
            buffer -> new FadePayload(buffer.readFloat(), buffer.readFloat(), buffer.readShort(), buffer.readInt()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * {@code Shake}: one of the {@code SHAKE_*} commands, with the map's clock ({@code time}, seconds
     * since its logic spawned). Source settles a shake as {@code sin(curtime * freq)} with
     * {@code curtime} the map's own time, so its speed depends on that clock, and the client runs
     * its shakes on it.
     */
    public record ShakePayload(int command, float amplitude, float frequency, float duration, double time) implements CustomPacketPayload {
        public static final Type<ShakePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Src2mc.MOD_ID, "screen_shake"));
        public static final StreamCodec<FriendlyByteBuf, ShakePayload> STREAM_CODEC = StreamCodec.of(
            (buffer, p) -> { buffer.writeByte(p.command); buffer.writeFloat(p.amplitude); buffer.writeFloat(p.frequency); buffer.writeFloat(p.duration); buffer.writeDouble(p.time); },
            buffer -> {
                int command = buffer.readUnsignedByte();
                if (command > SHAKE_START_NORUMBLE) throw new DecoderException("invalid src2mc shake command " + command);
                return new ShakePayload(command, buffer.readFloat(), buffer.readFloat(), buffer.readFloat(), buffer.readDouble());
            });
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Takes every text, fade and shake off the screen: a map's logic started afresh or stopped, as a level load clears Source's view effects. */
    public record ClearPayload() implements CustomPacketPayload {
        public static final Type<ClearPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Src2mc.MOD_ID, "screen_clear"));
        public static final StreamCodec<FriendlyByteBuf, ClearPayload> STREAM_CODEC = StreamCodec.unit(new ClearPayload());
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static final int SHAKE_START = 0, SHAKE_STOP = 1, SHAKE_AMPLITUDE = 2, SHAKE_FREQUENCY = 3, SHAKE_START_RUMBLEONLY = 4,
        SHAKE_START_NORUMBLE = 5;
    public static final int FFADE_IN = 1, FFADE_OUT = 2, FFADE_MODULATE = 4, FFADE_STAYOUT = 8, FFADE_PURGE = 16;
    /** Portal 2's env_fade flag 16: the fade leaves the HUD alone. Carried to the client beside Source's flags. */
    public static final int FADE_UNDER_HUD = 0x100;

    public static void register(PayloadRegistrar registrar) {
        registrar.playToClient(TextPayload.TYPE, TextPayload.STREAM_CODEC,
            (payload, context) -> dev.theredja.src2mc.client.logic.ScreenOverlay.text(payload));
        registrar.playToClient(FadePayload.TYPE, FadePayload.STREAM_CODEC,
            (payload, context) -> dev.theredja.src2mc.client.logic.ScreenOverlay.fade(payload));
        registrar.playToClient(ClearPayload.TYPE, ClearPayload.STREAM_CODEC,
            (payload, context) -> dev.theredja.src2mc.client.logic.ScreenOverlay.clear());
        registrar.playToClient(ShakePayload.TYPE, ShakePayload.STREAM_CODEC,
            (payload, context) -> dev.theredja.src2mc.client.logic.ScreenOverlay.shake(payload));
    }

    /** What went to whom, for tests: a server-less run records instead of sending. */
    record Sent(ServerPlayer player, CustomPacketPayload payload) {}

    private static void send(MapLogic map, List<ServerPlayer> players, CustomPacketPayload payload) {
        for (ServerPlayer player : players) {
            if (map.level() == null) map.screenSent.add(new Sent(player, payload));
            else PacketDistributor.sendToPlayer(player, payload);
        }
        if (map.level() == null && players.isEmpty()) map.screenSent.add(new Sent(null, payload));
    }

    private static ServerPlayer player(MapLogic map, Actor activator) {
        if (!(activator instanceof PlayerActor actor) || map.level() == null) return null;
        return map.level().getPlayerByUUID(actor.id()) instanceof ServerPlayer player ? player : null;
    }

    /** {@code UTIL_StringToIntArray} of four: missing values are 0. As {@code 0xRRGGBBAA}. */
    static int rgba(String value) {
        String[] parts = value == null ? new String[0] : value.trim().split("\\s+");
        int color = 0;
        for (int i = 0; i < 4; i++) color = color << 8 | (i < parts.length ? Variant.integer(parts[i]) & 0xFF : 0);
        return color;
    }

    /** {@code FixedUnsigned16(value, 1 << 9)}: a fade time as the 7.9 fixed-point message carries it. */
    static float fixed(double seconds) {
        long fixed = Math.round(seconds * 512);
        return Math.max(0, Math.min(65535, fixed)) / 512f;
    }

    // ---- The entities.

    /**
     * {@code CGameText}, with the inputs INFRA's and Portal 2's branch adds ({@code SetText},
     * {@code SetPosX}, {@code SetPosY}, {@code SetTextColor}, {@code SetTextColor2}). A text naming
     * a {@code #} token shows its localized text.
     */
    static final class GameText extends LogicEntity {
        private static final int ALL_PLAYERS = 1;
        private String message;
        private float x, y, fadein, fadeout, holdtime, fxtime;
        private int channel, effect, color1, color2;
        private boolean changed;

        GameText(MapLogic map, int index, LogicTable.Entity entity) { super(map, index, entity); }

        @Override void spawn() {
            message = key("message", "");
            x = (float) number("x", 0);
            y = (float) number("y", 0);
            effect = (int) number("effect", 0);
            fadein = (float) number("fadein", 0);
            fadeout = (float) number("fadeout", 0);
            holdtime = (float) number("holdtime", 0);
            fxtime = (float) number("fxtime", 0);
            channel = (int) number("channel", 0);
            color1 = rgba(key("color"));
            color2 = rgba(key("color2"));
        }

        @Override boolean accept(String input, String value, Actor activator, LogicEntity caller) {
            switch (input) {
                case "display" -> display(activator);
                case "settext" -> message = value == null ? "" : value;
                case "setposx" -> x = (float) Variant.number(value);
                case "setposy" -> y = (float) Variant.number(value);
                case "settextcolor" -> color1 = rgba(value);
                case "settextcolor2" -> color2 = rgba(value);
                default -> { return false; }
            }
            if (!input.equals("display")) changed = true;
            return true;
        }

        /** {@code CGameText::Display}: everyone, or the activator; Source's single player shows it to the one player. */
        private void display(Actor activator) {
            List<ServerPlayer> to;
            if (hasSpawnFlags(ALL_PLAYERS)) to = map.inside();
            else {
                ServerPlayer player = player(map, activator);
                to = player != null ? List.of(player) : map.inside().size() == 1 ? map.inside() : List.of();
            }
            send(map, to, payload());
        }

        TextPayload payload() {
            return new TextPayload(channel, x, y, color1, color2, effect, fadein, fadeout, holdtime, fxtime, map.table.localize(message));
        }

        @Override void save(net.minecraft.nbt.CompoundTag tag) {
            super.save(tag);
            if (!changed) return;
            tag.putString("message", message);
            tag.putFloat("x", x);
            tag.putFloat("y", y);
            tag.putInt("color1", color1);
            tag.putInt("color2", color2);
        }

        @Override void load(net.minecraft.nbt.CompoundTag tag) {
            super.load(tag);
            if (!tag.contains("message")) return;
            message = tag.getString("message");
            x = tag.getFloat("x");
            y = tag.getFloat("y");
            color1 = tag.getInt("color1");
            color2 = tag.getInt("color2");
            changed = true;
        }

        @Override String state() { return "\"" + map.table.localize(message) + "\" channel " + channel; }
    }

    /** {@code CEnvFade}: fades every player in the map, or only the activator. */
    static final class Fade extends LogicEntity {
        private static final int SF_FADE_IN = 1, SF_FADE_MODULATE = 2, SF_FADE_ONLYONE = 4, SF_FADE_STAYOUT = 8, SF_FADE_UNDER_HUD = 16;

        Fade(MapLogic map, int index, LogicTable.Entity entity) { super(map, index, entity); }

        @Override boolean accept(String input, String value, Actor activator, LogicEntity caller) {
            if (!input.equals("fade")) return false;
            int flags = hasSpawnFlags(SF_FADE_IN) ? FFADE_IN : FFADE_OUT;
            if (hasSpawnFlags(SF_FADE_MODULATE)) flags |= FFADE_MODULATE;
            if (hasSpawnFlags(SF_FADE_STAYOUT)) flags |= FFADE_STAYOUT;
            if (hasSpawnFlags(SF_FADE_UNDER_HUD)) flags |= FADE_UNDER_HUD;
            int color = renderColor << 8 | ((int) number("renderamt", 255) & 0xFF);
            float duration = fixed(number("duration", 0)), hold = fixed(number("holdtime", 0));
            if (hasSpawnFlags(SF_FADE_ONLYONE)) {
                ServerPlayer player = player(map, activator);
                if (player != null || map.level() == null) send(map, player == null ? List.of() : List.of(player), new FadePayload(duration, hold, flags, color));
            } else {
                send(map, map.inside(), new FadePayload(duration, hold, flags | FFADE_PURGE, color));
            }
            fire("onbeginfade", activator, null);
            return true;
        }
    }

    /** {@code CEnvShake}: shakes the players near it, weaker further away, on the ground unless it shakes the air too. */
    static final class Shake extends LogicEntity {
        private static final int SF_SHAKE_EVERYONE = 1, SF_SHAKE_INAIR = 4, SF_SHAKE_NO_VIEW = 32, SF_SHAKE_NO_RUMBLE = 64;
        private float amplitude, frequency, duration, radius;
        /** Who the last command reached, for {@code /src2mc logic list}. */
        private String reach = "never fired";

        Shake(MapLogic map, int index, LogicTable.Entity entity) { super(map, index, entity); }

        @Override void spawn() {
            amplitude = (float) number("amplitude", 0);
            frequency = (float) number("frequency", 0);
            duration = (float) number("duration", 0);
            radius = (float) number("radius", 0);
        }

        @Override boolean accept(String input, String value, Actor activator, LogicEntity caller) {
            switch (input) {
                case "startshake" -> apply(hasSpawnFlags(SF_SHAKE_NO_RUMBLE) ? SHAKE_START_NORUMBLE
                    : hasSpawnFlags(SF_SHAKE_NO_VIEW) ? SHAKE_START_RUMBLEONLY : SHAKE_START);
                case "stopshake" -> apply(SHAKE_STOP);
                case "amplitude" -> { amplitude = (float) Variant.number(value); apply(SHAKE_AMPLITUDE); }
                case "frequency" -> { frequency = (float) Variant.number(value); apply(SHAKE_FREQUENCY); }
                default -> { return false; }
            }
            return true;
        }

        /** {@code CEnvShake::ApplyShake} into {@code UTIL_ScreenShake}; only the view part, the rest is Source's physics and ropes. */
        private void apply(int command) {
            if (hasSpawnFlags(SF_SHAKE_NO_VIEW) && hasSpawnFlags(SF_SHAKE_NO_RUMBLE)) return;
            // A rumble-only shake moves no view, and there is no controller to rumble.
            if (command == SHAKE_START_RUMBLEONLY) return;
            float amp = Math.min(amplitude, MAX_SHAKE_AMPLITUDE);
            double range = hasSpawnFlags(SF_SHAKE_EVERYONE) ? 0 : radius;
            double[] at = position();
            boolean start = command == SHAKE_START || command == SHAKE_START_NORUMBLE;
            List<ServerPlayer> players = map.inside();
            if (map.level() == null) {
                // No server: the one shake a player at the centre would get.
                send(map, List.of(), new ShakePayload(command, command == SHAKE_STOP ? 0 : amp, frequency, duration, map.time()));
                return;
            }
            int reached = 0, inAir = 0, outside = 0;
            for (ServerPlayer player : players) {
                if (start && !hasSpawnFlags(SF_SHAKE_INAIR) && !player.onGround()) { inAir++; continue; }
                float local = at == null ? amp : localAmplitude(amp, range, at, player);
                if (local < 0) { outside++; continue; }
                if (local > 0 || command == SHAKE_STOP) {
                    send(map, List.of(player), new ShakePayload(command, command == SHAKE_STOP ? 0 : local, frequency, duration, map.time()));
                    reached++;
                }
            }
            reach = "last command reached " + reached + " of " + players.size() + " players inside (" + inAir + " in the air, "
                + outside + " beyond the radius of " + (range <= 0 ? "everywhere" : Math.round(range / UNITS_PER_BLOCK * 10) / 10.0 + " blocks") + ")";
        }

        /** {@code ComputeShakeAmplitude}: falls off linearly to the radius, -1 beyond it; a radius of 0 reaches everyone. */
        private float localAmplitude(float amp, double range, double[] at, ServerPlayer player) {
            if (range <= 0) return amp;
            Vec3 centre = player.getBoundingBox().getCenter();
            Vec3 shaker = map.world(at);
            double distance = centre.distanceTo(shaker) * UNITS_PER_BLOCK;
            return distance <= range ? (float) (amp * (1 - distance / range)) : -1;
        }

        @Override String state() { return "amplitude " + amplitude + ", frequency " + frequency + ", " + duration + " s, radius " + radius + "; " + reach; }
    }

    /** Payloads the last server-less run would have sent, oldest first; drained. */
    static List<Sent> drain(MapLogic map) {
        List<Sent> sent = new ArrayList<>(map.screenSent);
        map.screenSent.clear();
        return sent;
    }
}
