package dev.theredja.src2mc.client.logic;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.logic.ScreenEffects;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * {@code CHudCredits} ({@code hud_credits.cpp}): the logo that fades in over 5 s, holds and fades
 * out over 2 s; the intro names, three at a time in three rows, each fading in and out and the next
 * starting in its row as the one before it ends; and the outro, every name scrolling up the screen
 * until the last holds in the middle and fades. Laid out on Source's 640 by 480: x by
 * {@code XRES} (the screen's width over 640), y by {@code YRES} (its height over 480), fonts by the
 * height. The text is Minecraft's font, bold, sized so its capitals are as tall as the scheme font's
 * (Trebuchet MS 20, Verdana 16); the scheme fonts are additive and this is plain alpha, and the glyph
 * fonts ({@code WeaponIcons}, {@code CreditsOutroLogos}) cannot be drawn except for the logo, which
 * the bundle carries as an image. The server works out when the outro is over
 * ({@link ScreenEffects#outroSeconds}), from the same layout.
 */
@EventBusSubscriber(modid = Src2mc.MOD_ID, value = Dist.CLIENT)
public final class CreditsOverlay {
    private CreditsOverlay() {}

    private static final int LOGO_FADEIN = 0, LOGO_FADEHOLD = 1, LOGO_FADEOUT = 2, LOGO_FADEOFF = 3;
    /** The cap height of the scheme fonts over their em: Trebuchet MS 0.715 (Verdana 0.727, taken the same). */
    private static final float CAP_HEIGHT = 0.715F;
    /** Where a font's capitals start below the top of its line, over the em: the ascent less the cap height. */
    private static final float CAP_TOP = 0.224F;
    /** Minecraft's font: the height of its capitals in its own pixels. */
    private static final float MINECRAFT_CAP = 7;

    /** {@code creditname_t}. */
    private static final class Name {
        String name, font;
        float yPos, xPos;
        boolean active;
        double time, timeAdd, timeStart;
        int slot;
    }

    private static boolean running;
    private static int kind;
    private static ScreenEffects.CreditsParams params;
    private static final List<Name> NAMES = new ArrayList<>();
    private static int logoState = LOGO_FADEOFF;
    private static double fadeTime, logoDesiredLength, logoTime;
    private static double alpha;
    private static boolean lastInPlace, outroDone;
    private static double lastClock = Double.NaN;
    private static ResourceLocation logoTexture;
    private static int logoWidth, logoHeight;

    // ---- What the server sends.

    /** {@code MsgFunc_CreditsMsg} and {@code MsgFunc_LogoTimeMsg}. */
    public static void show(ScreenEffects.CreditsPayload payload) {
        double now = ScreenOverlay.clock();
        clearState();
        params = payload.params();
        kind = payload.kind();
        loadLogo(payload.logo());
        lastClock = now;
        switch (kind) {
            case ScreenEffects.CreditsPayload.LOGO -> {
                // PrepareLogo: Source's own 5 s unless the map set one.
                alpha = 0;
                logoDesiredLength = payload.logoLength() != 0 ? payload.logoLength() : 5;
                fadeTime = now + 5;
                logoState = LOGO_FADEIN;
                running = true;
            }
            case ScreenEffects.CreditsPayload.INTRO -> prepareIntro(payload.names(), now);
            default -> prepareOutro(payload.names());
        }
    }

    /** {@code PrepareIntroCredits}. */
    private static void prepareIntro(List<ScreenEffects.CreditsName> names, double now) {
        int slot = 0;
        for (int i = 0; i < names.size(); i++) {
            Name credit = new Name();
            credit.name = names.get(i).name();
            credit.font = names.get(i).font();
            credit.yPos = params.y() + slot * ScreenEffects.fontTall(credit.font);
            credit.xPos = params.x();
            if (i < 3) {
                credit.active = true;
                credit.slot = slot;
                credit.time = now + params.fadeIn() + params.fadeOut() + params.fadeHold();
                credit.timeStart = now;
                logoTime = credit.time + params.logoTime();
            }
            slot = (slot + 1) % 3;
            NAMES.add(credit);
        }
        running = true;
    }

    /** {@code PrepareOutroCredits}: nothing to roll leaves the panel off, as Source does. */
    private static void prepareOutro(List<ScreenEffects.CreditsName> names) {
        if (names.isEmpty()) return;
        float height = ScreenEffects.VIRTUAL_HEIGHT;
        for (ScreenEffects.CreditsName entry : names) {
            Name credit = new Name();
            credit.name = entry.name();
            credit.font = entry.font();
            credit.yPos = height;
            height += ScreenEffects.fontTall(credit.font) + params.separation();
            NAMES.add(credit);
        }
        pixelHeight = height;
        alpha = ScreenEffects.OUTRO_ALPHA;
        running = true;
    }

    /** {@code g_iCreditsPixelHeight}. */
    private static float pixelHeight;

    private static void clearState() {
        running = false;
        NAMES.clear();
        lastInPlace = false;
        outroDone = false;
        logoTime = 0;
        alpha = ScreenEffects.OUTRO_ALPHA;
        logoState = LOGO_FADEOFF;
    }

    /** Takes the credits off the screen and forgets the logo. */
    public static void clear() {
        clearState();
        releaseLogo();
        lastClock = Double.NaN;
    }

    // ---- The logo image.

    private static void releaseLogo() {
        if (logoTexture != null) Minecraft.getInstance().getTextureManager().release(logoTexture);
        logoTexture = null;
    }

    private static void loadLogo(byte[] png) {
        releaseLogo();
        if (png.length == 0) return;
        try {
            NativeImage image = NativeImage.read(new ByteArrayInputStream(png));
            logoWidth = image.getWidth();
            logoHeight = image.getHeight();
            // The texture owns the pixels; linear, no mipmaps, as it is a flat quad near its own size.
            DynamicTexture texture = new DynamicTexture(image);
            texture.setFilter(true, false);
            ResourceLocation location = ResourceLocation.fromNamespaceAndPath(Src2mc.MOD_ID, "credits_logo");
            Minecraft.getInstance().getTextureManager().register(location, texture);
            logoTexture = location;
        } catch (IOException | RuntimeException exception) {
            Src2mc.LOGGER.warn("src2mc credits: cannot read the logo image", exception);
        }
    }

    // ---- The frame.

    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onGuiPost(RenderGuiEvent.Post event) {
        if (!running) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) { clear(); return; }
        double now = ScreenOverlay.clock();
        double frame = Double.isNaN(lastClock) ? 0 : Math.max(0, Math.min(0.25, now - lastClock));
        lastClock = now;
        GuiGraphics graphics = event.getGuiGraphics();
        switch (kind) {
            case ScreenEffects.CreditsPayload.LOGO -> drawLogo(graphics, now);
            case ScreenEffects.CreditsPayload.INTRO -> drawIntro(graphics, now);
            default -> drawOutro(graphics, now, frame);
        }
    }

    /** {@code DrawLogo}. */
    private static void drawLogo(GuiGraphics graphics, double now) {
        switch (logoState) {
            case LOGO_FADEIN -> {
                double delta = fadeTime - now;
                // RemapValClamped(delta, 5, 0, -128, 255), at least 0.
                double fraction = Math.max(0, Math.min(1, (delta - 5) / (0 - 5)));
                alpha = Math.max(0, -128 + (255 + 128) * fraction);
                if (delta <= 0) {
                    logoState = LOGO_FADEHOLD;
                    fadeTime = now + logoDesiredLength;
                }
            }
            case LOGO_FADEHOLD -> {
                if (fadeTime <= now) {
                    logoState = LOGO_FADEOUT;
                    fadeTime = now + 2;
                }
            }
            case LOGO_FADEOUT -> {
                double delta = fadeTime - now;
                alpha = 255 * Math.max(0, Math.min(1, delta / 2));
                if (delta <= 0) {
                    logoState = LOGO_FADEOFF;
                    running = false;
                }
            }
            default -> { running = false; return; }
        }
        int a = (int) alpha;
        if (a < 4 || running && logoState == LOGO_FADEOFF) return;
        float guiW = graphics.guiWidth(), guiH = graphics.guiHeight();
        String second = params.logo2();
        if (logoTexture != null) {
            // The image holds the logo line and a second line under it, each as tall as the font; the first is centred on the screen.
            // DrawLogo takes the font without the proportional flag (hud_credits.cpp:435), so it is drawn in screen pixels at
            // any resolution, not scaled by the height over 480 as the names are.
            float k = (float) (1 / Minecraft.getInstance().getWindow().getGuiScale());
            int lines = second.isEmpty() ? 1 : 2;
            float lineHeight = (float) logoHeight / lines;
            drawImage(graphics, (guiW - logoWidth * k) / 2, guiH / 2 - lineHeight * k / 2, k, 255, 255, 255, a);
            return;
        }
        // No image: the text in Minecraft's font, as tall as the scheme's logo font (WeaponIcons, 64 screen pixels).
        float tall = (float) (ScreenEffects.fontTall("WeaponIcons") / Minecraft.getInstance().getWindow().getGuiScale());
        // The em on the 480-line layout that comes out as the font's screen pixels.
        float em = tall * ScreenEffects.VIRTUAL_HEIGHT / guiH;
        drawCentredEm(graphics, params.logo(), guiH / 2 - tall / 2, em, a << 24 | 0xFFFFFF);
        if (!second.isEmpty()) drawCentredEm(graphics, second, guiH / 2 + tall / 2, em, a << 24 | 0xFFFFFF);
    }

    /** {@code DrawIntroCreditsName}. */
    private static void drawIntro(GuiGraphics graphics, double now) {
        float guiW = graphics.guiWidth(), guiH = graphics.guiHeight();
        int rgb = params.color() >>> 8;
        for (int i = 0; i < NAMES.size(); i++) {
            Name credit = NAMES.get(i);
            if (!credit.active) continue;
            double local = now - credit.timeStart;
            double blend = fadeBlend(params.fadeIn(), params.fadeOut(), params.fadeHold() + credit.timeAdd, local);
            int a = (int) (blend * (params.color() & 0xFF));
            if (a >= 4 && !glyphFont(credit.font)) {
                drawText(graphics, credit.name, credit.xPos * guiW / 640, credit.yPos * guiH / ScreenEffects.VIRTUAL_HEIGHT,
                    em(credit.font), a << 24 | rgb);
            }
            if (logoTime > now) continue;
            if (credit.time - params.nextFade() <= now) {
                if (i + 3 < NAMES.size()) {
                    Name next = NAMES.get(i + 3);
                    if (next.time == 0) {
                        next.active = true;
                        next.timeAdd = i < 3 ? i + 1.0 : params.pause();
                        next.time = now + params.fadeIn() + params.fadeOut() + params.fadeHold() + next.timeAdd;
                        next.timeStart = now;
                        next.slot = credit.slot;
                    }
                }
            }
            if (credit.time <= now) {
                credit.active = false;
                if (i == NAMES.size() - 1) {
                    // The last name is done: Source clears the whole panel.
                    clearState();
                    return;
                }
            }
        }
    }

    /** {@code DrawOutroCreditsName}. */
    private static void drawOutro(GuiGraphics graphics, double now, double frame) {
        if (NAMES.isEmpty() || outroDone) return;
        float guiW = graphics.guiWidth(), guiH = graphics.guiHeight();
        float height = ScreenEffects.VIRTUAL_HEIGHT;
        float scroll = (float) (frame * pixelHeight / params.scrollTime());
        for (int i = 0; i < NAMES.size(); i++) {
            Name credit = NAMES.get(i);
            int tall = (int) ScreenEffects.fontTall(credit.font);
            credit.active = !(credit.yPos < -tall || credit.yPos > height);
            double a = ScreenEffects.OUTRO_ALPHA;
            if (i == NAMES.size() - 1) {
                // The last one stays on screen and fades out.
                if (!lastInPlace) {
                    credit.yPos -= scroll;
                    if ((int) credit.yPos + tall / 2 <= (int) height / 2) {
                        lastInPlace = true;
                        fadeTime = now + ScreenEffects.OUTRO_HOLD;
                    }
                } else {
                    if (fadeTime <= now && alpha > 0) {
                        alpha -= frame * params.scrollTime() * 2;
                        if (alpha <= 0) {
                            credit.active = false;
                            outroDone = true;
                        }
                    }
                    a = Math.max(0, alpha);
                }
            } else {
                credit.yPos -= scroll;
            }
            if (!credit.active) continue;
            int alphaByte = (int) a;
            if (alphaByte < 4) continue;
            if (glyphFont(credit.font)) {
                // The logo's own row: its image, scaled to the row's font size against the one it was drawn at.
                if (logoTexture != null && credit.name.equals(params.logo()) && params.logo2().isEmpty()) {
                    float k = guiH / ScreenEffects.VIRTUAL_HEIGHT * ScreenEffects.fontTall(credit.font) / ScreenEffects.fontTall("WeaponIcons");
                    drawImage(graphics, (guiW - logoWidth * k) / 2, credit.yPos * guiH / ScreenEffects.VIRTUAL_HEIGHT, k, 255, 255, 255, alphaByte);
                }
                continue;
            }
            drawCentred(graphics, credit.name, credit.yPos * guiH / ScreenEffects.VIRTUAL_HEIGHT, credit.font, alphaByte << 24 | 0xFFFFFF);
        }
    }

    /** {@code FadeBlend}. */
    private static double fadeBlend(double fadein, double fadeout, double hold, double localTime) {
        double fadeTime = fadein + hold;
        double blend;
        if (localTime < 0) return 0;
        if (localTime < fadein) blend = 1 - (fadein - localTime) / fadein;
        else if (localTime > fadeTime) blend = fadeout > 0 ? 1 - (localTime - fadeTime) / fadeout : 0;
        else blend = 1;
        return Math.max(0, blend);
    }

    /** The scheme's glyph fonts, which draw pictures from letters and so cannot be drawn in Minecraft's font. */
    private static boolean glyphFont(String font) {
        String lower = font == null ? "" : font.toLowerCase(java.util.Locale.ROOT);
        return lower.equals("weaponicons") || lower.equals("creditsoutrologos") || lower.equals("creditsoutrovalve")
            || lower.equals("creditslogo") || lower.equals("creditsicons") || lower.equals("clienttitlefont");
    }

    // ---- Drawing, in GUI units (the screen's pixels over the GUI scale).

    private static void drawImage(GuiGraphics graphics, float x, float y, float scale, int r, int g, int b, int a) {
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0);
        graphics.pose().scale(scale, scale, 1);
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(r / 255f, g / 255f, b / 255f, a / 255f);
        graphics.blit(logoTexture, 0, 0, logoWidth, logoHeight, 0, 0, logoWidth, logoHeight, logoWidth, logoHeight);
        RenderSystem.setShaderColor(1, 1, 1, 1);
        graphics.pose().popPose();
    }

    /** A line centred on the screen's width, its box starting at {@code y}. */
    private static void drawCentred(GuiGraphics graphics, String text, float y, String font, int argb) {
        drawCentredEm(graphics, text, y, em(font), argb);
    }

    private static void drawCentredEm(GuiGraphics graphics, String text, float y, float em, int argb) {
        drawText(graphics, text, (graphics.guiWidth() - width(graphics, text, em)) / 2, y, em, argb);
    }

    /** The scheme's {@code tall}, the em, of a credits font. */
    static float em(String font) {
        return switch (font == null ? "" : font.toLowerCase(java.util.Locale.ROOT)) {
            case "creditsoutrotext" -> 16;
            case "weaponicons", "clienttitlefont" -> 64;
            default -> 20;
        };
    }

    /** One Minecraft pixel in GUI units, for text with the given em height on the 480-line screen. */
    private static float scale(GuiGraphics graphics, float em) {
        return em * CAP_HEIGHT / MINECRAFT_CAP * graphics.guiHeight() / ScreenEffects.VIRTUAL_HEIGHT;
    }

    /** What Source's {@code GetStringPixelWidth} would be, for text drawn at {@code em}. */
    static float width(GuiGraphics graphics, String text, float em) { return width(graphics, text, em, true); }

    static float width(GuiGraphics graphics, String text, float em, boolean bold) {
        return Minecraft.getInstance().font.width(styled(text, bold)) * scale(graphics, em);
    }

    /** Draws {@code text} with the top of its line box at {@code y} and its left at {@code x}, bold, at the scheme font's {@code em}. */
    static void drawText(GuiGraphics graphics, String text, float x, float y, float em, int argb) { drawText(graphics, text, x, y, em, argb, true); }

    static void drawText(GuiGraphics graphics, String text, float x, float y, float em, int argb, boolean bold) {
        float scale = scale(graphics, em);
        Font minecraft = Minecraft.getInstance().font;
        // The scheme font's capitals start CAP_TOP of an em below its line's top; Minecraft's start a pixel below its own.
        float top = y + CAP_TOP * em * graphics.guiHeight() / ScreenEffects.VIRTUAL_HEIGHT - scale;
        graphics.pose().pushPose();
        graphics.pose().translate(x, top, 0);
        graphics.pose().scale(scale, scale, 1);
        graphics.drawString(minecraft, styled(text, bold), 0, 0, argb, false);
        graphics.pose().popPose();
    }

    private static Component styled(String text, boolean bold) {
        return bold ? Component.literal(text).withStyle(ChatFormatting.BOLD) : Component.literal(text);
    }
}
