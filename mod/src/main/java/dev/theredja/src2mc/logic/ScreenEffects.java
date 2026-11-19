package dev.theredja.src2mc.logic;

import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.bundle.LogicTable;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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

    /** {@code HudText}'s slot: a message that takes the first free of sixteen, not one of {@code game_text}'s channels. */
    public static final int CHANNEL_HUDTEXT = 255;

    /**
     * {@code CBasePlayer::SetFOV}: Source's horizontal field of view at 4:3 in degrees, 0 for the
     * player's own, reached over {@code rate} seconds (0 at once).
     */
    public record ZoomPayload(float fov, float rate) implements CustomPacketPayload {
        public static final Type<ZoomPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Src2mc.MOD_ID, "screen_zoom"));
        public static final StreamCodec<FriendlyByteBuf, ZoomPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, p) -> { buffer.writeFloat(p.fov); buffer.writeFloat(p.rate); },
            buffer -> new ZoomPayload(buffer.readFloat(), buffer.readFloat()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** {@code C_EnvStarfield}'s networked state: whether it is on and how many particles it adds a frame. */
    public record StarfieldPayload(boolean on, float density) implements CustomPacketPayload {
        public static final Type<StarfieldPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Src2mc.MOD_ID, "screen_starfield"));
        public static final StreamCodec<FriendlyByteBuf, StarfieldPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, p) -> { buffer.writeBoolean(p.on); buffer.writeFloat(p.density); },
            buffer -> new StarfieldPayload(buffer.readBoolean(), buffer.readFloat()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** The longest logo text the credits message carries. */
    static final int MAX_TEXT = 256;

    /** {@code CreditsParams} of {@code scripts/credits.txt} as {@code CHudCredits::ReadParams} reads them; {@code color} is {@code 0xRRGGBBAA}. */
    public record CreditsParams(float fadeIn, float fadeOut, float fadeHold, float nextFade, float pause, float logoTime, float x, float y,
                                int color, float scrollTime, float separation, String logo, String logo2) {
        static CreditsParams read(Map<String, String> params) {
            return new CreditsParams(real(params, "fadeintime", 1), real(params, "fadeouttime", 2), real(params, "fadeholdtime", 3),
                real(params, "nextfadetime", 2), real(params, "pausebetweenwaves", 2), real(params, "logotime", 2),
                real(params, "posx", 2), real(params, "posy", 2), rgba(params.get("color")), real(params, "scrolltime", 57),
                real(params, "separation", 5), clamp(params.getOrDefault("logo", "HALF-LIFE'")), clamp(params.getOrDefault("logo2", "")));
        }

        /** The logo texts go over the wire with a 256 character limit: a longer one would fail the whole message. */
        private static String clamp(String text) { return text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text; }

        private static float real(Map<String, String> params, String key, float fallback) {
            String value = params.get(key);
            return value == null ? fallback : (float) Variant.number(value);
        }

        void write(FriendlyByteBuf buffer) {
            buffer.writeFloat(fadeIn); buffer.writeFloat(fadeOut); buffer.writeFloat(fadeHold); buffer.writeFloat(nextFade);
            buffer.writeFloat(pause); buffer.writeFloat(logoTime); buffer.writeFloat(x); buffer.writeFloat(y); buffer.writeInt(color);
            buffer.writeFloat(scrollTime); buffer.writeFloat(separation); buffer.writeUtf(logo, MAX_TEXT); buffer.writeUtf(logo2, MAX_TEXT);
        }

        static CreditsParams read(FriendlyByteBuf buffer) {
            return new CreditsParams(buffer.readFloat(), buffer.readFloat(), buffer.readFloat(), buffer.readFloat(), buffer.readFloat(),
                buffer.readFloat(), buffer.readFloat(), buffer.readFloat(), buffer.readInt(), buffer.readFloat(), buffer.readFloat(),
                buffer.readUtf(MAX_TEXT), buffer.readUtf(MAX_TEXT));
        }
    }

    /** One line of the credits and the scheme font it is drawn in. */
    public record CreditsName(String name, String font) {}

    /**
     * {@code CreditsMsg} and {@code LogoTimeMsg}: {@code kind} 1 shows the logo for {@code logoLength} seconds (0: Source's
     * 5), 2 rolls the intro names, 3 the outro. {@code logo} is the logo as a PNG, or empty.
     */
    public record CreditsPayload(int kind, float logoLength, CreditsParams params, List<CreditsName> names, byte[] logo) implements CustomPacketPayload {
        public static final int LOGO = 1, INTRO = 2, OUTRO = 3;
        /**
         * The most the logo image may take. A custom payload is limited to 1 MiB in all, and the names and
         * parameters share it, so the image gets half; a bigger one is left out and the logo drawn as text.
         */
        public static final int MAX_LOGO = 512 * 1024;
        public static final Type<CreditsPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Src2mc.MOD_ID, "screen_credits"));
        public static final StreamCodec<FriendlyByteBuf, CreditsPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, p) -> {
                buffer.writeByte(p.kind); buffer.writeFloat(p.logoLength); p.params.write(buffer);
                buffer.writeVarInt(p.names.size());
                for (CreditsName name : p.names) { buffer.writeUtf(name.name, 256); buffer.writeUtf(name.font, 64); }
                buffer.writeByteArray(p.logo);
            },
            buffer -> {
                int kind = buffer.readUnsignedByte();
                if (kind < LOGO || kind > OUTRO) throw new DecoderException("invalid src2mc credits kind " + kind);
                float logoLength = buffer.readFloat();
                CreditsParams params = CreditsParams.read(buffer);
                int count = buffer.readVarInt();
                if (count < 0 || count > 4096) throw new DecoderException("invalid src2mc credits count " + count);
                List<CreditsName> names = new ArrayList<>(count);
                for (int i = 0; i < count; i++) names.add(new CreditsName(buffer.readUtf(256), buffer.readUtf(64)));
                return new CreditsPayload(kind, logoLength, params, names, buffer.readByteArray(MAX_LOGO));
            });
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** {@code KeyHintText}: the hint's text with its {@code %command%} placeholders, or empty to hide it. */
    public record HudHintPayload(String text) implements CustomPacketPayload {
        public static final Type<HudHintPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Src2mc.MOD_ID, "screen_hudhint"));
        public static final StreamCodec<FriendlyByteBuf, HudHintPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, p) -> buffer.writeUtf(p.text, 2048), buffer -> new HudHintPayload(buffer.readUtf(2048)));
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
        registrar.playToClient(ZoomPayload.TYPE, ZoomPayload.STREAM_CODEC,
            (payload, context) -> dev.theredja.src2mc.client.logic.ZoomClient.zoom(payload));
        registrar.playToClient(StarfieldPayload.TYPE, StarfieldPayload.STREAM_CODEC,
            (payload, context) -> dev.theredja.src2mc.client.logic.Starfield.set(payload));
        registrar.playToClient(CreditsPayload.TYPE, CreditsPayload.STREAM_CODEC,
            (payload, context) -> dev.theredja.src2mc.client.logic.CreditsOverlay.show(payload));
        registrar.playToClient(HudHintPayload.TYPE, HudHintPayload.STREAM_CODEC,
            (payload, context) -> dev.theredja.src2mc.client.logic.HudHint.show(payload));
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

    // ---- The camera and HUD entities of the intro and the hints.

    /** Who {@code UTIL_GetLocalPlayer} is for a map that can have several players inside: the activator if a player, else everyone inside. */
    private static List<ServerPlayer> locals(MapLogic map, Actor activator) {
        ServerPlayer player = player(map, activator);
        return player != null ? List.of(player) : map.inside();
    }

    /** {@code UTIL_ShowMessage}'s player: the activator if it is a player, else Source's only one; with several inside, nobody. */
    private static List<ServerPlayer> onlyOne(MapLogic map, Actor activator) {
        ServerPlayer player = player(map, activator);
        return player != null ? List.of(player) : map.inside().size() == 1 ? map.inside() : List.of();
    }

    /** {@code client text}'s limit: a longer string would not encode. */
    private static String clamp(String text) { return text.length() > 2000 ? text.substring(0, 2000) : text; }

    /** {@code 0xAARRGGBB} as the {@code 0xRRGGBBAA} the text payload carries. */
    private static int rrggbbaa(int argb) { return argb << 8 | argb >>> 24; }

    /**
     * Sends the zoom and starfield as they stand to players who have just come into the map (it
     * is checked every server tick): a player arriving later, logging in, or changing dimension
     * into it was not there when those were sent. A player is new when they were not inside the
     * map's previous tick, so leaving and coming back counts too.
     */
    static void arrivals(MapLogic map, List<ServerPlayer> inside) {
        Set<UUID> seen = map.screenPlayers();
        List<ServerPlayer> arrived = new ArrayList<>();
        Set<UUID> now = new java.util.HashSet<>();
        for (ServerPlayer player : inside) {
            now.add(player.getUUID());
            if (!seen.contains(player.getUUID())) arrived.add(player);
        }
        seen.retainAll(now);
        seen.addAll(now);
        if (arrived.isEmpty()) return;
        // Straight to the players: a map that has not ticked yet has no level of its own to send through.
        for (ServerPlayer player : arrived) {
            for (LogicEntity entity : map.entities()) {
                if (!entity.removed && entity instanceof Starfield field && field.changed) PacketDistributor.sendToPlayer(player, new StarfieldPayload(field.on, field.density));
            }
            Integer owner = Zoom.OWNERS.get(map);
            if (owner != null && map.entity(owner) instanceof Zoom zoom && !zoom.removed) PacketDistributor.sendToPlayer(player, new ZoomPayload(zoom.fov, 0));
        }
    }

    /**
     * {@code CEnvZoom}: {@code Zoom} takes the player's field of view to {@code FOV} over {@code Rate}
     * seconds, {@code UnZoom} puts it back at once. A zoom holds the field of view as its owner,
     * as {@code CBasePlayer::SetFOV} keeps {@code m_hZoomOwner}: another env_zoom's {@code Zoom} takes
     * it over, but an {@code UnZoom} from one that does not own it only works if the owner has the
     * override flag. The field of view is the client's; this tracks only who holds it.
     */
    static final class Zoom extends LogicEntity {
        private static final int ENV_ZOOM_OVERRIDE = 1;
        /** Per map, the entity index of the zoom that holds the field of view. */
        private static final Map<MapLogic, Integer> OWNERS = new java.util.WeakHashMap<>();
        private float rate;
        private int fov;

        Zoom(MapLogic map, int index, LogicTable.Entity entity) { super(map, index, entity); }

        @Override void spawn() {
            rate = (float) number("rate", 0);
            fov = (int) number("fov", 0);
            OWNERS.remove(map);
        }

        @Override boolean accept(String input, String value, Actor activator, LogicEntity caller) {
            switch (input) {
                case "zoom" -> {
                    // The player held by another env_zoom may be taken over: it is cleared first.
                    OWNERS.remove(map);
                    if (fov != 0) OWNERS.put(map, index);
                    send(map, locals(map, activator), new ZoomPayload(fov, rate));
                }
                case "unzoom" -> {
                    Integer owner = OWNERS.get(map);
                    if (owner == null || owner == index) {
                        OWNERS.remove(map);
                        send(map, locals(map, activator), new ZoomPayload(0, 0));
                    } else if (map.entity(owner) instanceof Zoom other && other.hasSpawnFlags(ENV_ZOOM_OVERRIDE)) {
                        // CanOverrideEnvZoomOwner: the field of view changes, the owner stays.
                        send(map, locals(map, activator), new ZoomPayload(0, 0));
                    }
                }
                default -> { return false; }
            }
            return true;
        }

        private boolean holding() { return Integer.valueOf(index).equals(OWNERS.get(map)); }

        @Override void save(net.minecraft.nbt.CompoundTag tag) {
            super.save(tag);
            if (holding()) tag.putBoolean("zoom_owner", true);
        }

        /** A restored map has no zoom on the client; the next think sends it again, as {@code m_iFOV} is part of Source's save. */
        @Override void load(net.minecraft.nbt.CompoundTag tag) {
            super.load(tag);
            if (!tag.getBoolean("zoom_owner")) return;
            OWNERS.put(map, index);
            nextThink = map.time();
        }

        @Override void think() { send(map, map.inside(), new ZoomPayload(fov, 0)); }

        @Override String state() { return "fov " + fov + ", rate " + rate + (holding() ? ", holding the zoom" : ""); }
    }

    /** {@code CEnvStarfield}: {@code TurnOn}, {@code TurnOff}, {@code SetDensity}; the client draws the particles. */
    static final class Starfield extends LogicEntity {
        private boolean on;
        private float density = 1;
        private boolean changed;

        Starfield(MapLogic map, int index, LogicTable.Entity entity) { super(map, index, entity); }

        @Override void spawn() {
            on = false;
            density = 1;
        }

        @Override boolean accept(String input, String value, Actor activator, LogicEntity caller) {
            switch (input) {
                case "turnon" -> on = true;
                case "turnoff" -> on = false;
                case "setdensity" -> density = (float) Variant.number(value);
                default -> { return false; }
            }
            changed = true;
            // The entity is networked to everyone, so everyone in the map sees it.
            send(map, map.inside(), new StarfieldPayload(on, density));
            return true;
        }

        @Override void save(net.minecraft.nbt.CompoundTag tag) {
            super.save(tag);
            if (!changed) return;
            tag.putBoolean("on", on);
            tag.putFloat("density", density);
        }

        @Override void load(net.minecraft.nbt.CompoundTag tag) {
            super.load(tag);
            if (!tag.contains("on")) return;
            on = tag.getBoolean("on");
            density = tag.getFloat("density");
            changed = true;
            if (on) nextThink = map.time();
        }

        /** After a restore: the client has forgotten the field. */
        @Override void think() { send(map, map.inside(), new StarfieldPayload(on, density)); }

        @Override String state() { return (on ? "on" : "off") + ", density " + density; }
    }

    /**
     * {@code CCredits}: {@code ShowLogo}, {@code RollCredits} (the intro names), {@code RollOutroCredits}
     * and {@code SetLogoLength}. The client draws; it also tells Source's server when the outro has
     * scrolled by, which here the server works out itself ({@link #outroSeconds}) and fires
     * {@code OnCreditsDone} then.
     */
    static final class Credits extends LogicEntity {
        private float logoLength;
        private boolean rolledOutro;
        /** After a restore: the outro is rolled again on the next think. */
        private boolean reroll;

        Credits(MapLogic map, int index, LogicTable.Entity entity) { super(map, index, entity); }

        @Override boolean accept(String input, String value, Actor activator, LogicEntity caller) {
            switch (input) {
                case "showlogo" -> roll(CreditsPayload.LOGO);
                case "rollcredits" -> roll(CreditsPayload.INTRO);
                case "rolloutrocredits" -> {
                    roll(CreditsPayload.OUTRO);
                    // In case of a save and restore.
                    rolledOutro = true;
                }
                case "setlogolength" -> logoLength = (float) Variant.number(value);
                default -> { return false; }
            }
            return true;
        }

        private void roll(int kind) {
            LogicTable.Credits credits = map.table.credits();
            // No credits file: Source's HUD loads nothing and draws nothing.
            if (credits == null) return;
            CreditsParams params = CreditsParams.read(credits.params());
            List<CreditsName> names = new ArrayList<>();
            if (kind != CreditsPayload.LOGO) {
                for (LogicTable.CreditName name : kind == CreditsPayload.INTRO ? credits.intro() : credits.outro()) {
                    // The outro looks up a #token; the intro shows its names as written.
                    String text = kind == CreditsPayload.OUTRO && name.name().startsWith("#") ? map.table.localize(name.name()) : name.name();
                    names.add(new CreditsName(text.length() > 250 ? text.substring(0, 250) : text, name.font()));
                }
            }
            byte[] logo = kind == CreditsPayload.INTRO ? new byte[0] : logoImage(credits);
            send(map, map.inside(), new CreditsPayload(kind, kind == CreditsPayload.LOGO ? logoLength : 0, params, names, logo));
            if (kind == CreditsPayload.OUTRO) {
                double seconds = outroSeconds(params, names);
                nextThink = Double.isNaN(seconds) ? NEVER : map.time() + seconds;
            }
        }

        /** The logo's PNG from the map's bundle, or nothing: the client then draws the logo's text. */
        private byte[] logoImage(LogicTable.Credits credits) {
            if (credits.logoImage() == null || map.level() == null) return new byte[0];
            try {
                var located = Src2mc.bundles().active().findLocatedMap(map.placement.campaignId(), map.placement.mapId()).orElse(null);
                if (located == null) return new byte[0];
                byte[] bytes = dev.theredja.src2mc.menu.ScreensData.read(located.bundle(), credits.logoImage());
                return bytes.length > CreditsPayload.MAX_LOGO ? new byte[0] : bytes;
            } catch (java.io.IOException | RuntimeException exception) {
                Src2mc.LOGGER.warn("src2mc credits: cannot read the logo {}", credits.logoImage(), exception);
                return new byte[0];
            }
        }

        /** The client's {@code creditsdone} command: {@code CreditsDone_f} fires the output on the entity, as activator too. */
        @Override void think() {
            if (reroll) {
                // CCredits::OnRestore: the client has forgotten the outro, so it is rolled again, which also
                // sets when OnCreditsDone is due.
                reroll = false;
                roll(CreditsPayload.OUTRO);
                return;
            }
            fire("oncreditsdone", this, null);
        }

        @Override void save(net.minecraft.nbt.CompoundTag tag) {
            super.save(tag);
            tag.putFloat("logo_length", logoLength);
            tag.putBoolean("rolled_outro", rolledOutro);
        }

        @Override void load(net.minecraft.nbt.CompoundTag tag) {
            super.load(tag);
            logoLength = tag.getFloat("logo_length");
            rolledOutro = tag.getBoolean("rolled_outro");
            if (rolledOutro) {
                reroll = true;
                nextThink = map.time();
            }
        }

        @Override String state() { return "logo length " + logoLength + (rolledOutro ? ", outro rolled" : ""); }
    }

    // ---- The credits' layout, which the server and the client both go by.

    /** The virtual screen the credits are laid out on: Source's 640 by 480. */
    public static final float VIRTUAL_HEIGHT = 480;
    /** {@code HudCredits}' {@code TextColor} alpha in HL2's {@code hudlayout.res}: where the outro's last name fades from. */
    public static final int OUTRO_ALPHA = 192;
    /** How long the outro's last name stays before it fades (non-console builds). */
    public static final double OUTRO_HOLD = 10;

    /**
     * {@code surface()->GetFontTall} of a credits font in virtual pixels: the scheme's {@code tall}
     * times its family's line height (ascent plus descent over the em: Trebuchet MS 1.161, Verdana
     * 1.215, whole pixels as {@code GetFontTall} returns; the glyph fonts are taken as their {@code tall}).
     */
    public static float fontTall(String font) {
        return switch (font == null ? "" : font.toLowerCase(java.util.Locale.ROOT)) {
            case "creditstext" -> Math.round(20 * 1.161f);
            case "creditsoutrotext" -> Math.round(16 * 1.215f);
            case "creditslogo", "creditsicons", "creditsoutrologos" -> 34;
            case "creditsoutrovalve" -> 48;
            case "weaponicons", "clienttitlefont" -> 64;
            default -> Math.round(20 * 1.161f);
        };
    }

    /**
     * How long {@code DrawOutroCreditsName} runs before it sends {@code creditsdone}, on the 480-line
     * screen: every name scrolls up at {@code pixelHeight / scrolltime}; the last stops with its
     * middle at the screen's, waits {@link #OUTRO_HOLD}, and fades its alpha at {@code scrolltime * 2}
     * a second. NaN with no names, when Source's panel never starts.
     */
    public static double outroSeconds(CreditsParams params, List<CreditsName> names) {
        if (names.isEmpty() || params.scrollTime <= 0) return Double.NaN;
        double height = VIRTUAL_HEIGHT, lastStart = height, lastTall = 0;
        for (int i = 0; i < names.size(); i++) {
            double tall = fontTall(names.get(i).font());
            if (i == names.size() - 1) { lastStart = height; lastTall = tall; }
            height += tall + params.separation;
        }
        double speed = height / params.scrollTime;
        double scroll = Math.max(0, (lastStart + (int) lastTall / 2 - VIRTUAL_HEIGHT / 2) / speed);
        return scroll + OUTRO_HOLD + OUTRO_ALPHA / (params.scrollTime * 2.0);
    }

    /**
     * {@code CMessage}: {@code ShowMessage} shows a {@code titles.txt} text through {@code HudText}
     * and plays {@code messagesound}. Without the all-players flag only the activator, or Source's
     * single player, sees it; a message the titles lack is silently dropped.
     */
    static final class Message extends LogicEntity {
        private static final int SF_MESSAGE_ONCE = 1, SF_MESSAGE_ALL = 2;
        private float volume = 1;
        private int attenuation;

        Message(MapLogic map, int index, LogicTable.Entity entity) { super(map, index, entity); }

        @Override void spawn() {
            attenuation = (int) number("messageattenuation", 0);
            // Remap volume from [0,10] to [0,1]; none means normal.
            volume = (float) number("messagevolume", 0) * 0.1F;
            if (volume <= 0) volume = 1;
        }

        @Override boolean accept(String input, String value, Actor activator, LogicEntity caller) {
            if (!input.equals("showmessage")) return false;
            LogicTable.Title title = map.table.title(key("message", ""));
            if (title != null) {
                List<ServerPlayer> to = hasSpawnFlags(SF_MESSAGE_ALL) ? map.inside() : onlyOne(map, activator);
                send(map, to, new TextPayload(CHANNEL_HUDTEXT, title.x(), title.y(), rrggbbaa(title.color1()), rrggbbaa(title.color2()),
                    title.effect(), title.fadein(), title.fadeout(), title.holdtime(), title.fxtime(), clamp(title.text())));
            }
            // Attenuation 3 is "everywhere"; the other levels are the sound script's own here.
            String noise = key("messagesound");
            if (noise != null && !noise.isBlank()) map.emitSound(noise, attenuation == 3 ? null : position(), null, null, 0, volume);
            fire("onshowmessage", activator, null);
            if (hasSpawnFlags(SF_MESSAGE_ONCE)) remove();
            return true;
        }

        @Override String state() { return "\"" + key("message", "") + "\"" + (map.table.title(key("message", "")) == null ? " (no such title)" : ""); }
    }

    /**
     * {@code CEnvHudHint}: {@code ShowHudHint} and {@code HideHudHint} send {@code KeyHintText}. The text is
     * the message's localized string; a {@code #} token the strings lack hides, as
     * {@code CHudHintKeyDisplay::SetHintText} refuses to show a placeholder.
     */
    static final class HudHint extends LogicEntity {
        private static final int SF_HUDHINT_ALLPLAYERS = 1;

        HudHint(MapLogic map, int index, LogicTable.Entity entity) { super(map, index, entity); }

        @Override boolean accept(String input, String value, Actor activator, LogicEntity caller) {
            boolean show;
            switch (input) {
                case "showhudhint" -> show = true;
                case "hidehudhint" -> show = false;
                default -> { return false; }
            }
            List<ServerPlayer> to = hasSpawnFlags(SF_HUDHINT_ALLPLAYERS) ? map.inside() : locals(map, activator);
            send(map, to, new HudHintPayload(show ? text() : ""));
            return true;
        }

        /** The text to show: the localized string, the plain text as written, or empty for a token with no string. */
        String text() {
            String raw = key("message", "");
            String token = raw.startsWith("#") ? raw.substring(1) : raw;
            String found = map.table.strings().get(token.toLowerCase(java.util.Locale.ROOT));
            if (found != null && !found.isEmpty()) return clamp(found);
            return raw.startsWith("#") ? "" : clamp(raw);
        }

        @Override String state() { return "\"" + text() + "\""; }
    }

    /** Payloads the last server-less run would have sent, oldest first; drained. */
    static List<Sent> drain(MapLogic map) {
        List<Sent> sent = new ArrayList<>(map.screenSent);
        map.screenSent.clear();
        return sent;
    }
}
