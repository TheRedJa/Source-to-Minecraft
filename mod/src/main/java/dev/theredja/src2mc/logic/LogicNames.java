package dev.theredja.src2mc.logic;

import java.util.Locale;

/** How the sound table names a sound an entity keyvalue names, shared by server and client. */
public final class LogicNames {
    private LogicNames() {}

    /**
     * A sound keyvalue as the sound table names it: lowercase, forward slashes, without Source's
     * leading sound characters ({@code )}, {@code *}, {@code #} and the like). Null stays null.
     */
    public static String sound(String name) {
        if (name == null) return null;
        String text = name.trim().replace('\\', '/').toLowerCase(Locale.ROOT);
        int start = 0;
        while (start < text.length() && "*#@><^)(}$!?&~`+%".indexOf(text.charAt(start)) >= 0) start++;
        return text.substring(start);
    }
}
