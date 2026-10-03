package dev.theredja.src2mc.client.logic;

import dev.theredja.src2mc.Src2mc;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * Source's closed captions, in a box above the hotbar like Source's caption panel: each line
 * stays for its sound's length plus Source's {@code cc_linger_time} of one second, and newer
 * lines push older ones up.
 *
 * <p>Caption text carries Source's tags: {@code <clr:r,g,b>} colours what follows, {@code <I>}
 * and {@code <B>} toggle italic and bold, {@code <cr>} breaks the line, and {@code <len:s>} sets
 * how long the caption stays. The others ({@code <low>}, {@code <sfx>}, {@code <norepeat:s>}, ...)
 * steer Source's caption system and draw nothing.
 */
@EventBusSubscriber(modid = Src2mc.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class CaptionOverlay {
    private static final ResourceLocation LAYER = ResourceLocation.fromNamespaceAndPath(Src2mc.MOD_ID, "captions");
    private static final double LINGER_SECONDS = 1.0;
    private static final int MAX_CAPTIONS = 4;
    private static final int DEFAULT_COLOUR = 0xFFFFFF;

    private record Caption(Component text, long until) {}

    private static final List<Caption> CAPTIONS = new ArrayList<>();
    private static boolean enabled = true;

    private CaptionOverlay() {}

    public static boolean enabled() { return enabled; }
    public static void setEnabled(boolean on) { enabled = on; if (!on) CAPTIONS.clear(); }

    /** Shows a caption for its sound's {@code seconds}, unless its own {@code <len>} says otherwise. */
    public static void show(String text, double seconds) {
        if (!enabled || text == null || text.isBlank()) return;
        Parsed parsed = parse(text);
        double length = parsed.length > 0 ? parsed.length : Math.max(seconds, 1.0);
        long until = System.currentTimeMillis() + (long) ((length + LINGER_SECONDS) * 1000);
        CAPTIONS.removeIf(caption -> caption.text.getString().equals(parsed.text.getString()));
        CAPTIONS.add(new Caption(parsed.text, until));
        while (CAPTIONS.size() > MAX_CAPTIONS) CAPTIONS.remove(0);
    }

    public static void clear() { CAPTIONS.clear(); }

    private record Parsed(Component text, double length) {}

    /** Turns tagged caption text into a component. */
    static Parsed parse(String text) {
        MutableComponent out = Component.empty();
        Style style = Style.EMPTY.withColor(DEFAULT_COLOUR);
        double length = -1;
        StringBuilder run = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            int close = c == '<' ? text.indexOf('>', i) : -1;
            if (close < 0) { run.append(c); i++; continue; }
            String tag = text.substring(i + 1, close);
            String name = (tag.contains(":") ? tag.substring(0, tag.indexOf(':')) : tag).trim().toLowerCase(Locale.ROOT);
            String argument = tag.contains(":") ? tag.substring(tag.indexOf(':') + 1).trim() : "";
            if (run.length() > 0) { out.append(Component.literal(run.toString()).withStyle(style)); run.setLength(0); }
            switch (name) {
                case "clr", "playerclr" -> style = style.withColor(colour(argument.split(":")[0]));
                case "i" -> style = style.withItalic(!Boolean.TRUE.equals(style.isItalic()));
                case "b" -> style = style.withBold(!Boolean.TRUE.equals(style.isBold()));
                case "cr" -> run.append('\n');
                case "len" -> length = dev.theredja.src2mc.logic.Variant.number(argument);
                default -> {}
            }
            i = close + 1;
        }
        if (run.length() > 0) out.append(Component.literal(run.toString()).withStyle(style));
        return new Parsed(out, length);
    }

    private static TextColor colour(String rgb) {
        String[] parts = rgb.split(",");
        if (parts.length < 3) return TextColor.fromRgb(DEFAULT_COLOUR);
        int r = clamp(parts[0]), g = clamp(parts[1]), b = clamp(parts[2]);
        return TextColor.fromRgb(r << 16 | g << 8 | b);
    }

    private static int clamp(String value) { return Math.max(0, Math.min(255, dev.theredja.src2mc.logic.Variant.integer(value))); }

    @SubscribeEvent
    public static void register(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.SUBTITLE_OVERLAY, LAYER, (graphics, deltaTracker) -> draw(graphics));
    }

    private static void draw(GuiGraphics graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        CAPTIONS.removeIf(caption -> caption.until < now);
        if (CAPTIONS.isEmpty() || minecraft.options.hideGui) return;
        int maxWidth = Math.min(graphics.guiWidth() - 40, 360);
        List<FormattedCharSequence> lines = new ArrayList<>();
        for (Caption caption : CAPTIONS) lines.addAll(minecraft.font.split(caption.text, maxWidth));
        int lineHeight = minecraft.font.lineHeight + 1;
        int width = 0;
        for (FormattedCharSequence line : lines) width = Math.max(width, minecraft.font.width(line));
        int x = (graphics.guiWidth() - width) / 2;
        int bottom = graphics.guiHeight() - 72;
        int top = bottom - lines.size() * lineHeight;
        graphics.fill(x - 6, top - 4, x + width + 6, bottom + 2, 0x90000000);
        int y = top;
        for (FormattedCharSequence line : lines) {
            graphics.drawString(minecraft.font, line, (graphics.guiWidth() - minecraft.font.width(line)) / 2, y, DEFAULT_COLOUR, true);
            y += lineHeight;
        }
    }
}
