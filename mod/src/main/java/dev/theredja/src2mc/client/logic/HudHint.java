package dev.theredja.src2mc.client.logic;

import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.logic.ScreenEffects;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * {@code CHudHintKeyDisplay} ({@code hud_hintdisplay.cpp}), what {@code env_hudhint} shows. The text is cut
 * at its {@code %} marks into labels, the plain runs in the small font and the {@code %binding%} runs in
 * the large one, with the binding replaced by the key bound to it; labels pair into rows, the text in
 * the left column and the key in the right. HL2's {@code hudlayout.res} puts the panel's right and bottom
 * edges at 20 and 340 of Source's 640 by 480 and has it grow up and to the left; its
 * {@code hudanimations.txt} fades it in over 0.5 s, flashes the text between its colour (255 220 0, alpha
 * 100) and full alpha at 0.5 s and 1.5 s, and fades it out 12 s after it showed, over 1 s
 * ({@code KeyHintMessageShow}); {@code KeyHintMessageHide} fades it out over 0.5 s. The fonts are
 * GorDIN 13 (plain) and 15 (bold), drawn in Minecraft's font at the same capital height.
 */
@EventBusSubscriber(modid = Src2mc.MOD_ID, value = Dist.CLIENT)
public final class HudHint {
    private HudHint() {}

    /** {@code text_xpos}, {@code text_ypos}, {@code text_xgap}, {@code text_ygap}. */
    private static final float TEXT_X = 8, TEXT_Y = 8, GAP_X = 8, GAP_Y = 8;
    /** The panel's size in {@code hudlayout.res}, before it fits itself to its text, and its edges: {@code xpos "r120"}, {@code ypos "r340"}. */
    private static final float OLD_WIDE = 100, OLD_TALL = 200, RIGHT_FROM_EDGE = 120, BOTTOM_FROM_EDGE = 340;
    private static final float SMALL_EM = 13, LARGE_EM = 15;
    /** A line's height over its font's em, as for the credits' fonts. */
    private static final float LINE = 1.161F;
    private static final double SHOW_FADE = 0.5, HIDE_FADE = 0.5, HOLD = 12, FADE_OUT = 1;

    /** One run of the text: a plain piece or a {@code %binding%}, already replaced by its key. */
    private record Label(String text, boolean variable) {}

    private static List<Label> labels = List.of();
    /** Whether the panel is showing (or fading out after a show) and since when; the alpha it started from. */
    private static boolean shown;
    private static double began, from;
    private static boolean hiding;

    // ---- What the server sends.

    /** {@code MsgFunc_KeyHintText}: a text shows the panel, an empty one (or an unresolved {@code #}) hides it. */
    public static void show(ScreenEffects.HudHintPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        double now = ScreenOverlay.clock();
        double current = alpha(now);
        if (payload.text().isEmpty()) {
            if (shown) { from = current; began = now; hiding = true; }
            return;
        }
        labels = parse(payload.text());
        // StopPanelAnimations, then the alpha animates on from where it is.
        from = current;
        began = now;
        hiding = false;
        shown = true;
    }

    public static void clear() {
        labels = List.of();
        shown = false;
        hiding = false;
    }

    // ---- The text.

    /** {@code SetHintText}'s parse: runs between {@code %} marks, a variable between a pair. */
    static List<Label> parse(String text) {
        List<Label> parsed = new ArrayList<>();
        int at = 0;
        while (at < text.length()) {
            boolean variable = false;
            if (text.charAt(at) == '%') { variable = true; at++; }
            int end = text.indexOf('%', at);
            String token = end >= 0 ? text.substring(at, end) : text.substring(at);
            at += token.length();
            if (variable) at++;
            parsed.add(new Label(variable ? keyName(token) : token, variable));
        }
        return parsed;
    }

    /**
     * {@code Key_LookupBinding} for a console command: the key Minecraft has for the action it matches,
     * upper case as Source prints it; a command with no Minecraft action shows as {@code [command]}.
     */
    static String keyName(String binding) {
        String command = binding.startsWith("+") ? binding.substring(1) : binding;
        var options = Minecraft.getInstance().options;
        KeyMapping mapping = switch (command.toLowerCase(Locale.ROOT)) {
            case "jump" -> options.keyJump;
            case "use", "attack2" -> options.keyUse;
            case "attack" -> options.keyAttack;
            case "duck" -> options.keyShift;
            case "speed" -> options.keySprint;
            case "forward" -> options.keyUp;
            case "back" -> options.keyDown;
            case "moveleft" -> options.keyLeft;
            case "moveright" -> options.keyRight;
            default -> null;
        };
        if (mapping == null) return "[" + command + "]";
        if (mapping.isUnbound()) return "< NOT BOUND >";
        return mapping.getTranslatedKeyMessage().getString().toUpperCase(Locale.ROOT);
    }

    // ---- The animations.

    /** The panel's alpha, 0 to 255, at game time {@code now}: {@code KeyHintMessageShow} or {@code KeyHintMessageHide}. */
    private static double alpha(double now) {
        if (!shown) return 0;
        double since = Math.max(0, now - began);
        if (hiding) return from * Math.max(0, 1 - since / HIDE_FADE);
        if (since < SHOW_FADE) return from + (255 - from) * (since / SHOW_FADE);
        if (since < HOLD) return 255;
        return 255 * Math.max(0, 1 - (since - HOLD) / FADE_OUT);
    }

    /** The text colour's alpha: its resting 100, flashing to 255 at 0.5 s and 1.5 s over 0.2 s up and 0.2 s down. */
    private static double textAlpha(double now) {
        if (hiding) return 100;
        double since = Math.max(0, now - began);
        for (double start : new double[]{0.5, 1.5}) {
            if (since >= start && since < start + 0.2) return 100 + 155 * (since - start) / 0.2;
            if (since >= start + 0.2 && since < start + 0.4) return 255 - 155 * (since - start - 0.2) / 0.2;
        }
        return 100;
    }

    // ---- The frame.

    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onGuiPost(RenderGuiEvent.Post event) {
        if (!shown) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) { clear(); return; }
        double now = ScreenOverlay.clock();
        double panel = alpha(now);
        if (panel <= 0 && (hiding || now - began >= HOLD)) {
            // Faded out; nothing is kept but the labels, as Source keeps them.
            shown = false;
            return;
        }
        if (panel < 1) return;
        draw(event.getGuiGraphics(), panel / 255, textAlpha(now) / 255);
    }

    private static void draw(GuiGraphics graphics, double panelAlpha, double textAlpha) {
        // Everything in Source's units until the end: the screen is guiHeight / 480 of a unit.
        float k = graphics.guiHeight() / ScreenEffects.VIRTUAL_HEIGHT;
        int count = labels.size();
        float[] wide = new float[count], tall = new float[count];
        float widest1 = 0, widest2 = 0;
        for (int i = 0; i < count; i++) {
            Label label = labels.get(i);
            float em = label.variable() ? LARGE_EM : SMALL_EM;
            String[] lines = label.text().split("\n", -1);
            for (String line : lines) wide[i] = Math.max(wide[i], CreditsOverlay.width(graphics, line, em, label.variable()) / k);
            tall[i] = lines.length * em * LINE;
            if ((i & 1) == 1) widest2 = Math.max(widest2, wide[i]);
            else widest1 = Math.max(widest1, wide[i]);
        }
        float col1 = TEXT_X, col2 = TEXT_X + widest1 + GAP_X, colY = TEXT_Y;
        float[] x = new float[count], y = new float[count];
        for (int i = 0; i < count; i += 2) {
            float row = tall[i];
            if (i + 1 < count) {
                row = Math.max(tall[i], tall[i + 1]);
                x[i + 1] = col2;
                y[i + 1] = colY + (row - tall[i + 1]) / 2;
            }
            x[i] = col1;
            y[i] = colY + (row - tall[i]) / 2;
            colY += row + GAP_Y;
        }
        float newWide = TEXT_X + col2 + widest2, newTall = colY;
        // The panel grows up and to the left from where HudLayout.res puts its right and bottom edges.
        float screenWide = graphics.guiWidth() / k;
        float left = screenWide - RIGHT_FROM_EDGE - (newWide - OLD_WIDE);
        float top = ScreenEffects.VIRTUAL_HEIGHT - BOTTOM_FROM_EDGE - (newTall - OLD_TALL);
        fillRounded(graphics, left * k, top * k, (left + newWide) * k, (top + newTall) * k, 8 * k, (int) Math.round(76 * panelAlpha));
        int argb = (int) Math.round(255 * Math.min(1, panelAlpha * textAlpha)) << 24 | 255 << 16 | 220 << 8;
        if ((argb >>> 24) < 4) return;
        for (int i = 0; i < count; i++) {
            Label label = labels.get(i);
            float em = label.variable() ? LARGE_EM : SMALL_EM;
            float lineY = top + y[i];
            for (String line : label.text().split("\n", -1)) {
                CreditsOverlay.drawText(graphics, line, (left + x[i]) * k, lineY * k, em, argb, label.variable());
                lineY += em * LINE;
            }
        }
    }

    /** {@code PaintBackgroundType 2}: a box with rounded corners, as stepped rows. */
    private static void fillRounded(GuiGraphics graphics, float x0, float y0, float x1, float y1, float radius, int alpha) {
        if (alpha < 4) return;
        int color = alpha << 24;
        int steps = Math.max(1, Math.round(radius / 2));
        float step = radius / steps;
        for (int i = 0; i < steps; i++) {
            // Row bands from the corner in: each a little wider than the one before.
            float inset = radius - (float) Math.sqrt(Math.max(0, radius * radius - (radius - i * step - step / 2) * (radius - i * step - step / 2)));
            graphics.fill(Math.round(x0 + inset), Math.round(y0 + i * step), Math.round(x1 - inset), Math.round(y0 + (i + 1) * step), color);
            graphics.fill(Math.round(x0 + inset), Math.round(y1 - (i + 1) * step), Math.round(x1 - inset), Math.round(y1 - i * step), color);
        }
        graphics.fill(Math.round(x0), Math.round(y0 + radius), Math.round(x1), Math.round(y1 - radius), color);
    }
}
