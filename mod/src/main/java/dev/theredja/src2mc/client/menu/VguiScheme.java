package dev.theredja.src2mc.client.menu;

import dev.theredja.src2mc.menu.KvTree;
import java.util.Locale;

/**
 * One vgui colour scheme ({@code resource/*scheme.res}): its named {@code Colors}, the {@code BaseSettings} the
 * controls read their colours and metrics from, and the {@code Fonts}.
 */
final class VguiScheme {
    /** An ARGB colour as four 0..255 components. */
    record Color(int r, int g, int b, int a) {
        static final Color BLANK = new Color(0, 0, 0, 0);

        Color withAlpha(int alpha) { return new Color(r, g, b, alpha); }

        int argb() { return a << 24 | r << 16 | g << 8 | b; }
    }

    /** A scheme font: the family file it names and its {@code tall}, the height of its line in panel pixels. */
    record Font(String family, int tall) {}

    private final KvTree colors, base, fonts;

    VguiScheme(KvTree scheme) {
        // The file is {@code Scheme { Colors {...} BaseSettings {...} Fonts {...} }}.
        KvTree root = scheme != null && scheme.block("Scheme") != null ? scheme.block("Scheme") : scheme == null ? KvTree.EMPTY : scheme;
        colors = orEmpty(root.block("Colors"));
        base = orEmpty(root.block("BaseSettings"));
        fonts = orEmpty(root.block("Fonts"));
    }

    private static KvTree orEmpty(KvTree tree) { return tree == null ? KvTree.EMPTY : tree; }

    /**
     * A colour written as {@code "r g b a"} or as the name of a {@code Colors} entry (which may itself be written
     * either way). Null for something that is neither.
     */
    Color color(String text) { return color(text, 0); }

    private Color color(String text, int depth) {
        if (text == null || depth > 4) return null;
        String[] parts = text.trim().split("\\s+");
        if (parts.length >= 3 && parts[0].matches("-?\\d+")) {
            int[] v = new int[]{0, 0, 0, 255};
            for (int i = 0; i < Math.min(4, parts.length); i++) v[i] = clamp(parseInt(parts[i]));
            return new Color(v[0], v[1], v[2], v[3]);
        }
        String named = colors.get(text.trim());
        return named == null ? null : color(named, depth + 1);
    }

    /** A {@code BaseSettings} colour, e.g. {@code Button.TextColor}; null when absent. */
    Color setting(String key) {
        String value = base.get(key);
        return value == null ? null : color(value);
    }

    Color setting(String key, Color fallback) {
        Color color = setting(key);
        return color == null ? fallback : color;
    }

    /** A numeric {@code BaseSettings} value such as {@code INFRAMenuItemList.ItemHeight}. */
    int number(String key, int fallback) {
        String value = base.get(key);
        return value == null ? fallback : parseInt(value);
    }

    /** The font a scheme name such as {@code MenuLarge} means; null when the scheme has no such font. */
    Font font(String name) {
        if (name == null) return null;
        KvTree font = fonts.block(name);
        if (font == null) return null;
        for (KvTree.Entry entry : font.entries()) {
            // The numbered blocks ("1") are the font's variants; the first is the one in use.
            if (entry.isBlock() && entry.key().matches("\\d+")) {
                String family = entry.block().get("name");
                int tall = parseInt(entry.block().get("tall", "0"));
                if (family != null) return new Font(family.toLowerCase(Locale.ROOT), tall);
            }
        }
        return null;
    }

    private static int parseInt(String text) {
        try {
            return (int) Double.parseDouble(text.trim());
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private static int clamp(int value) { return Math.max(0, Math.min(255, value)); }
}
