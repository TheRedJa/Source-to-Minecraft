package dev.theredja.src2mc.client.logic;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.logic.ScreenEffects;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * The map's texts, fades and shakes on this player's screen, as Source's client draws them:
 * {@code CHudMessage} (channels, the fade and scan-out effects, positions as screen fractions,
 * Trebuchet 24 standing in as Minecraft's font at 24 pixels, bold), {@code CViewEffects::Fade}
 * (fades summed, the highest alpha winning, modulate multiplying) and {@code CViewEffects::CalcShake}
 * (a random offset and roll, re-rolled at the shake's frequency, settling as a sine of rising
 * frequency over the duration). Time is the level's game time, so all of it stops while paused.
 */
@EventBusSubscriber(modid = Src2mc.MOD_ID, value = Dist.CLIENT)
public final class ScreenOverlay {
    private ScreenOverlay() {}

    /** {@code MAX_NETMESSAGE}: the channels a text can take. */
    private static final int CHANNELS = 6;
    private static final int MAX_SHAKES = 32;
    /** Trebuchet24's height, in screen pixels. */
    private static final float FONT_PIXELS = 24;
    private static final double UNITS_PER_BLOCK = 32;

    private record Message(ScreenEffects.TextPayload text, double start) {}

    private static final class Fade {
        double end, reset, speed;
        int r, g, b, alpha, flags;
    }

    private static final class Shake {
        float amplitude, frequency, duration, angle;
        double endTime, nextShake;
        int command;
        final float[] offset = new float[3];
    }

    private static final Message[] MESSAGES = new Message[CHANNELS];
    private static final List<Fade> FADES = new ArrayList<>();
    private static final List<Shake> SHAKES = new ArrayList<>();
    private static final Random RANDOM = new Random();
    private static Vec3 shakeOffset = Vec3.ZERO;
    private static float shakeRoll;
    private static double lastShakeTime = Double.NaN;
    /** The map's clock less this client's, from the last shake: shakes run on the map's time, as Source's on its curtime. */
    private static double mapClock;

    /** Game seconds, between ticks too. */
    /** The client's clock, in seconds of game time: what map times are synced against. */
    public static double clock() { return now(); }

    private static double now() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return 0;
        return (minecraft.level.getGameTime() + minecraft.getTimer().getGameTimeDeltaPartialTick(false)) / 20.0;
    }

    // ---- What the server sends.

    public static void text(ScreenEffects.TextPayload payload) {
        MESSAGES[Math.floorMod(payload.channel(), CHANNELS)] = new Message(payload, now());
    }

    /** {@code CViewEffects::Fade}. */
    public static void fade(ScreenEffects.FadePayload payload) {
        double time = now();
        Fade fade = new Fade();
        fade.end = payload.duration();
        fade.reset = payload.holdTime();
        fade.r = payload.color() >>> 24;
        fade.g = payload.color() >> 16 & 0xFF;
        fade.b = payload.color() >> 8 & 0xFF;
        fade.alpha = payload.color() & 0xFF;
        fade.flags = payload.flags();
        if (payload.duration() > 0) {
            if ((payload.flags() & ScreenEffects.FFADE_OUT) != 0) {
                if (fade.end != 0) fade.speed = -fade.alpha / fade.end;
                fade.end += time;
                fade.reset += fade.end;
            } else {
                if (fade.end != 0) fade.speed = fade.alpha / fade.end;
                fade.reset += time;
                fade.end += fade.reset;
            }
        }
        if ((payload.flags() & ScreenEffects.FFADE_PURGE) != 0) FADES.clear();
        FADES.add(fade);
    }

    /** {@code CViewEffects::Shake}. */
    public static void shake(ScreenEffects.ShakePayload payload) {
        mapClock = payload.time() - now();
        switch (payload.command()) {
            case ScreenEffects.SHAKE_START, ScreenEffects.SHAKE_START_NORUMBLE -> {
                if (SHAKES.size() >= MAX_SHAKES) return;
                Shake shake = new Shake();
                shake.amplitude = payload.amplitude();
                shake.frequency = payload.frequency();
                shake.duration = payload.duration();
                shake.endTime = now() + mapClock + payload.duration();
                shake.command = payload.command();
                SHAKES.add(shake);
            }
            case ScreenEffects.SHAKE_STOP -> SHAKES.clear();
            case ScreenEffects.SHAKE_AMPLITUDE -> { Shake longest = longest(); if (longest != null) longest.amplitude = payload.amplitude(); }
            case ScreenEffects.SHAKE_FREQUENCY -> { Shake longest = longest(); if (longest != null) longest.frequency = payload.frequency(); }
            default -> {}
        }
    }

    private static Shake longest() {
        Shake found = null;
        for (Shake shake : SHAKES) if (found == null || shake.duration > found.duration) found = shake;
        return found;
    }

    @SubscribeEvent
    static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) { clear(); }

    /** {@code /src2mc_screen status|clear}: what is on screen now, and a way to take it all off. */
    @SubscribeEvent
    static void registerCommand(net.neoforged.neoforge.client.event.RegisterClientCommandsEvent event) {
        event.getDispatcher().register(net.minecraft.commands.Commands.literal("src2mc_screen")
            .then(net.minecraft.commands.Commands.literal("status").executes(context -> reply(context.getSource(), status())))
            .then(net.minecraft.commands.Commands.literal("clear").executes(context -> { clear(); return reply(context.getSource(), status()); })));
    }

    private static int reply(net.minecraft.commands.CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    static String status() {
        int texts = 0;
        for (Message message : MESSAGES) if (message != null) texts++;
        return "src2mc screen: " + texts + " texts, " + FADES.size() + " fades, " + SHAKES.size() + " shakes; offset "
            + String.format(java.util.Locale.ROOT, "%.3f %.3f %.3f, roll %.2f", shakeOffset.x, shakeOffset.y, shakeOffset.z, shakeRoll);
    }

    public static void clear() {
        java.util.Arrays.fill(MESSAGES, null);
        FADES.clear();
        SHAKES.clear();
        shakeOffset = Vec3.ZERO;
        shakeRoll = 0;
    }

    // ---- The shake: worked out once a frame, as the camera is set up.

    @SubscribeEvent
    static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        calcShake();
        if (shakeRoll != 0) event.setRoll(event.getRoll() + shakeRoll);
    }

    /**
     * Fired after the camera has its position and before the world is drawn from it ({@code true}:
     * the frame's own field of view, not the hand's); the shake's offset goes on there, as
     * {@code ApplyShake} moves Source's view origin.
     */
    @SubscribeEvent
    static void onFov(ViewportEvent.ComputeFov event) {
        if (!event.usedConfiguredFov() || shakeOffset == Vec3.ZERO) return;
        event.getCamera().setPosition(event.getCamera().getPosition().add(shakeOffset));
    }

    /** {@code CViewEffects::CalcShake}. */
    private static void calcShake() {
        double time = now() + mapClock;
        // A new shake may move the map clock; a frame is never longer than a quarter second.
        double frameTime = Double.isNaN(lastShakeTime) ? 0 : Math.max(0, Math.min(0.25, time - lastShakeTime));
        lastShakeTime = time;
        double ox = 0, oy = 0, oz = 0, roll = 0;
        for (Iterator<Shake> it = SHAKES.iterator(); it.hasNext(); ) {
            Shake shake = it.next();
            if (time > shake.endTime || shake.duration <= 0 || shake.amplitude <= 0 || shake.frequency <= 0) {
                it.remove();
                continue;
            }
            if (time > shake.nextShake) {
                shake.nextShake = time + 1.0 / shake.frequency;
                for (int i = 0; i < 3; i++) shake.offset[i] = (RANDOM.nextFloat() * 2 - 1) * shake.amplitude;
                shake.angle = (RANDOM.nextFloat() * 2 - 1) * shake.amplitude * 0.25F;
            }
            double fraction = (shake.endTime - time) / shake.duration;
            double frequency = fraction != 0 ? shake.frequency / fraction : 0;
            fraction *= fraction;
            double angle = Math.min(time * frequency, 1e8);
            fraction *= Math.sin(angle);
            // Source's X, Y, Z: forward, left, up; Minecraft's up is Y. The offset is random either way.
            ox += shake.offset[0] * fraction;
            oz += shake.offset[1] * fraction;
            oy += shake.offset[2] * fraction;
            roll += shake.angle * fraction;
            shake.amplitude -= (float) (shake.amplitude * (frameTime / (shake.duration * shake.frequency)));
        }
        shakeOffset = ox == 0 && oy == 0 && oz == 0 ? Vec3.ZERO : new Vec3(ox / UNITS_PER_BLOCK, oy / UNITS_PER_BLOCK, oz / UNITS_PER_BLOCK);
        shakeRoll = (float) roll;
    }

    // ---- Texts and fades, over everything; a fade that leaves the HUD alone goes under it.

    @SubscribeEvent
    static void onGuiPre(RenderGuiEvent.Pre event) {
        int[] fade = fadeColor(true);
        if (fade != null) drawFade(event.getGuiGraphics(), fade);
    }

    @SubscribeEvent
    static void onGuiPost(RenderGuiEvent.Post event) {
        GuiGraphics graphics = event.getGuiGraphics();
        drawMessages(graphics);
        int[] fade = fadeColor(false);
        if (fade != null) drawFade(graphics, fade);
    }

    /**
     * {@code CViewEffects::FadeCalculate} over the fades that cover the HUD ({@code underHud} false)
     * or leave it alone: {r, g, b, alpha, modulate}, or null with nothing to draw.
     */
    private static int[] fadeColor(boolean underHud) {
        double time = now();
        FADES.removeIf(fade -> {
            if ((fade.flags & ScreenEffects.FFADE_STAYOUT) != 0) fade.reset = time + 0.1;
            return time > fade.reset && time > fade.end;
        });
        int r = 0, g = 0, b = 0, alpha = 0, count = 0;
        boolean modulate = false;
        for (Fade fade : FADES) {
            if (((fade.flags & ScreenEffects.FADE_UNDER_HUD) != 0) != underHud) continue;
            count++;
            r += fade.r; g += fade.g; b += fade.b;
            int fadeAlpha;
            if ((fade.flags & (ScreenEffects.FFADE_OUT | ScreenEffects.FFADE_IN)) != 0) {
                fadeAlpha = (int) (fade.speed * (fade.end - time));
                if ((fade.flags & ScreenEffects.FFADE_OUT) != 0) fadeAlpha += fade.alpha;
                fadeAlpha = Math.max(0, Math.min(fadeAlpha, fade.alpha));
            } else {
                fadeAlpha = fade.alpha;
            }
            alpha = Math.max(alpha, fadeAlpha);
            if ((fade.flags & ScreenEffects.FFADE_MODULATE) != 0) modulate = true;
        }
        if (count == 0 || alpha == 0) return null;
        return new int[]{r / count, g / count, b / count, alpha, modulate ? 1 : 0};
    }

    private static void drawFade(GuiGraphics graphics, int[] fade) {
        int width = graphics.guiWidth(), height = graphics.guiHeight();
        if (fade[4] == 0) {
            graphics.fill(0, 0, width, height, fade[3] << 24 | fade[0] << 16 | fade[1] << 8 | fade[2]);
            return;
        }
        // Modulate: the screen times the colour, weighed by the fade's alpha.
        float a = fade[3] / 255f;
        int r = Math.round(255 * (1 - a * (1 - fade[0] / 255f))), g = Math.round(255 * (1 - a * (1 - fade[1] / 255f))),
            b = Math.round(255 * (1 - a * (1 - fade[2] / 255f)));
        graphics.flush();
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.DST_COLOR, GlStateManager.DestFactor.ZERO);
        graphics.fill(0, 0, width, height, 0xFF000000 | r << 16 | g << 8 | b);
        graphics.flush();
        RenderSystem.defaultBlendFunc();
    }

    /** {@code CHudMessage::Paint}. */
    private static void drawMessages(GuiGraphics graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;
        double time = now();
        float scale = (float) (FONT_PIXELS / (font.lineHeight * minecraft.getWindow().getGuiScale()));
        for (int channel = 0; channel < CHANNELS; channel++) {
            Message message = MESSAGES[channel];
            if (message == null) continue;
            ScreenEffects.TextPayload text = message.text();
            String string = text.text();
            double end = switch (text.effect()) {
                case 0, 1 -> message.start() + text.fadein() + text.fadeout() + text.holdtime();
                case 2 -> message.start() + text.fadein() * string.length() + text.fadeout() + text.holdtime();
                default -> 0;
            };
            if (time > end) { MESSAGES[channel] = null; continue; }
            draw(graphics, font, scale, text, string, time - message.start());
        }
    }

    /** {@code CHudMessage::MessageDrawScan}: line by line, character by character, each in its own colour and alpha. */
    private static void draw(GuiGraphics graphics, Font font, float scale, ScreenEffects.TextPayload text, String string, double time) {
        String[] lines = string.split("\n", -1);
        float width = graphics.guiWidth() / scale, height = graphics.guiHeight() / scale;
        float lineHeight = font.lineHeight, totalWidth = 0;
        for (String line : lines) totalWidth = Math.max(totalWidth, lineWidth(font, line));
        float totalHeight = lines.length * lineHeight;
        float y = position(text.y(), totalHeight, totalHeight, height);
        // MessageScanStart.
        double fadeTime;
        double fadeBlend;
        if (text.effect() == 2) {
            fadeTime = text.fadein() * string.length() + text.holdtime();
            fadeBlend = time > fadeTime && text.fadeout() > 0 ? (time - fadeTime) / text.fadeout() * 255 : 0;
        } else {
            fadeTime = text.fadein() + text.holdtime();
            if (time < text.fadein()) fadeBlend = (text.fadein() - time) / text.fadein() * 255;
            else if (time > fadeTime) fadeBlend = text.fadeout() > 0 ? (time - fadeTime) / text.fadeout() * 255 : 255;
            else fadeBlend = 0;
        }
        fadeBlend = Math.min(255, fadeBlend);
        boolean flicker = text.effect() == 1 && RANDOM.nextInt(100) < 10;
        double charTime = flicker ? 1 : 0;
        int alpha = (int) (255 - fadeBlend);
        if (alpha < 4) return;
        graphics.pose().pushPose();
        graphics.pose().scale(scale, scale, 1);
        for (String line : lines) {
            float x = position(text.x(), lineWidth(font, line), totalWidth, width);
            for (int i = 0; i < line.length(); ) {
                int codePoint = line.codePointAt(i);
                String character = new String(Character.toChars(codePoint));
                i += Character.charCount(codePoint);
                // MessageScanNextChar.
                int src = text.color1() >>> 8, dest = 0, blend;
                switch (text.effect()) {
                    case 2 -> {
                        charTime += text.fadein();
                        if (charTime > time) { src = 0; blend = 0; }
                        else {
                            double delta = time - charTime;
                            if (time > fadeTime) blend = (int) fadeBlend;
                            else if (delta > text.fxtime()) blend = 0;
                            else { dest = text.color2() >>> 8; blend = (int) (255 - (delta * (1.0 / text.fxtime()) * 255.0 + 0.5)); }
                        }
                    }
                    default -> blend = (int) fadeBlend;
                }
                blend = Math.max(0, Math.min(255, blend));
                int r = ((src >> 16 & 0xFF) * (255 - blend) + (dest >> 16 & 0xFF) * blend) >> 8;
                int g = ((src >> 8 & 0xFF) * (255 - blend) + (dest >> 8 & 0xFF) * blend) >> 8;
                int b = ((src & 0xFF) * (255 - blend) + (dest & 0xFF) * blend) >> 8;
                Component glyph = Component.literal(character).withStyle(ChatFormatting.BOLD);
                graphics.drawString(font, glyph, Math.round(x), Math.round(y), alpha << 24 | r << 16 | g << 8 | b, false);
                x += font.width(glyph);
            }
            y += lineHeight;
        }
        graphics.pose().popPose();
    }

    private static float lineWidth(Font font, String line) {
        return font.width(Component.literal(line).withStyle(ChatFormatting.BOLD));
    }

    /** {@code CHudMessage::XPosition}/{@code YPosition}: -1 centres, other negatives align to the far edge, then kept on screen. */
    private static float position(float at, float size, float total, float screen) {
        float position;
        if (at == -1) position = (screen - size) / 2;
        else if (at < 0) position = (1 + at) * screen - total;
        else position = at * screen;
        if (position + size > screen) position = screen - size;
        else if (position < 0) position = 0;
        return position;
    }
}
