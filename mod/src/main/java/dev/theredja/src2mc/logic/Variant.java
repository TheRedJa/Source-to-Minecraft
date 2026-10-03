package dev.theredja.src2mc.logic;

import java.util.Locale;

/**
 * Source's {@code variant_t} conversions for input parameters, which always arrive here as text:
 * {@code atof}/{@code atoi} semantics, reading a leading number and ignoring the rest.
 */
public final class Variant {
    private Variant() {}

    private static final java.util.regex.Pattern LEADING_NUMBER =
        java.util.regex.Pattern.compile("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?");

    public static double number(String value) {
        if (value == null) return 0;
        var match = LEADING_NUMBER.matcher(value.trim());
        if (!match.find()) return 0;
        double parsed = Double.parseDouble(match.group());
        return Double.isFinite(parsed) ? parsed : 0;
    }

    public static int integer(String value) { return (int) number(value); }

    /** {@code variant_t::Convert} to a boolean: a number other than zero, or "true". */
    public static boolean bool(String value) {
        if (value == null) return false;
        String text = value.trim().toLowerCase(Locale.ROOT);
        return text.equals("true") || number(text) != 0;
    }

    /** Source's output value format for a float, which a target may read back as text. */
    public static String of(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e9) return Long.toString((long) value);
        return String.format(Locale.ROOT, "%f", value);
    }
}
