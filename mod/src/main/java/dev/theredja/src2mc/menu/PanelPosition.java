package dev.theredja.src2mc.menu;

import java.util.Locale;

/**
 * The position and size keys of a vgui panel, as {@code Panel::ApplySettings} reads them ({@code ComputePos},
 * {@code ComputeWide}, {@code ComputeTall} in {@code Panel.cpp}). Screen panels are not proportional, so
 * every number is a pixel count.
 */
public final class PanelPosition {
    private PanelPosition() {}

    /**
     * {@code xpos}/{@code ypos}: {@code r} counts from the far edge ({@code parent - n}), {@code c} from the centre
     * ({@code parent / 2 + n}), {@code s} and {@code p} scale the number by the panel's own size or the parent's, and
     * a trailing {@code +n} or {@code -n} adds or subtracts. {@code current} is kept when the key is absent.
     */
    public static int position(String value, int current, int size, int parentSize) {
        if (value == null) return current;
        return compute(value.trim(), current, size, parentSize, 0);
    }

    private static int compute(String input, int position, int size, int parentSize, int operator) {
        boolean right = false, centre = false, self = false, parent = false;
        String s = input;
        if (!s.isEmpty() && (s.charAt(0) == 'r' || s.charAt(0) == 'R')) { right = true; s = s.substring(1); }
        else if (!s.isEmpty() && (s.charAt(0) == 'c' || s.charAt(0) == 'C')) { centre = true; s = s.substring(1); }
        if (!s.isEmpty() && (s.charAt(0) == 's' || s.charAt(0) == 'S')) { self = true; s = s.substring(1); }
        else if (!s.isEmpty() && (s.charAt(0) == 'p' || s.charAt(0) == 'P')) { parent = true; s = s.substring(1); }
        int length = numberLength(s);
        double number = parse(s.substring(0, length));
        int delta = self ? (int) (size * number) : parent ? (int) (parentSize * number) : (int) number;
        int placed = right ? parentSize - delta : centre ? parentSize / 2 + delta : delta;
        int result = operator == 1 ? position + placed : operator == 2 ? position - placed : placed;
        String rest = s.substring(length);
        if (!rest.isEmpty() && (rest.charAt(0) == '+' || rest.charAt(0) == '-')) {
            return compute(rest.substring(1), result, size, parentSize, rest.charAt(0) == '+' ? 1 : 2);
        }
        return result;
    }

    /** {@code wide}/{@code tall}: {@code f} fills the parent less the number, {@code p} the parent less the number scaled, {@code s} the panel's own size scaled. */
    public static int size(String value, int current, int parentSize) {
        if (value == null) return current;
        String s = value.trim();
        if (s.isEmpty()) return current;
        char first = Character.toLowerCase(s.charAt(0));
        if (first == 'f') return parentSize - (int) parse(s.substring(1));
        if (first == 'p') return (int) ((parentSize - (int) parse(s.substring(1))) * parse(s.substring(1)));
        if (first == 's') return (int) (current * parse(s.substring(1)));
        if (first == 'o') return current;
        return (int) parse(s);
    }

    /** The leading number of a string, {@code atof}: 0 when there is none. */
    public static double parse(String text) {
        int length = numberLength(text);
        if (length == 0) return 0;
        try {
            return Double.parseDouble(text.substring(0, length));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private static int numberLength(String text) {
        int i = 0;
        if (i < text.length() && (text.charAt(i) == '-' || text.charAt(i) == '+')) i++;
        int digits = 0;
        while (i < text.length() && (Character.isDigit(text.charAt(i)) || text.charAt(i) == '.')) { i++; digits++; }
        return digits == 0 ? 0 : i;
    }

    /** The value of a boolean key: a non-zero number. */
    public static boolean flag(String value, boolean fallback) {
        if (value == null || value.isBlank()) return fallback;
        return parse(value.toLowerCase(Locale.ROOT)) != 0;
    }
}
