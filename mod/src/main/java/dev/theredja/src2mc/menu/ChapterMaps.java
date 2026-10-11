package dev.theredja.src2mc.menu;

import java.util.Locale;
import java.util.TreeMap;

/**
 * Chapters and maps: each chapter's first map from the chapters layout ({@code CINFRAMenuItemChapter} with
 * {@code chapter} and {@code command "map X"}), and the chapter a map unlocks when it loads.
 */
public final class ChapterMaps {
    private ChapterMaps() {}

    /** Chapter number to its first map (lower case), from every chapter control of the screens' layouts. */
    public static TreeMap<Integer, String> firstMaps(ScreensData data) {
        TreeMap<Integer, String> out = new TreeMap<>();
        for (KvTree layout : data.layouts().values()) collect(layout, out);
        return out;
    }

    private static void collect(KvTree tree, TreeMap<Integer, String> out) {
        for (KvTree.Entry entry : tree.entries()) {
            if (!entry.isBlock()) continue;
            KvTree block = entry.block();
            String control = block.get("ControlName");
            if (control != null && control.equalsIgnoreCase("CINFRAMenuItemChapter")) {
                int chapter = number(block.get("chapter"));
                String map = mapOf(block.get("command"));
                if (chapter > 0 && map != null) out.putIfAbsent(chapter, map);
            }
            collect(block, out);
        }
    }

    /** {@code X} of a {@code map X} command, lower case, else null. */
    public static String mapOf(String command) {
        if (command == null) return null;
        String[] parts = command.trim().split("\\s+");
        return parts.length == 2 && parts[0].equalsIgnoreCase("map") ? parts[1].toLowerCase(Locale.ROOT) : null;
    }

    private static int number(String text) {
        try {
            return text == null ? 0 : (int) Double.parseDouble(text.trim());
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    /**
     * The chapter loading {@code map} unlocks, as INFRA's {@code CServerGameDLL::LevelInit} decides it on every map load
     * (server.dylib, see source_refs/data/infra_menu_re.md): the lower-case name read with {@code "infra_c%2i_%*s"},
     * so the one or two digits after {@code infra_c}; 0 (nothing) for any other name, such as the menu map or the
     * {@code infra_ee_*} easter-egg maps. The unlocked count only ever rises to it ({@code SetValue} when not lower).
     */
    public static int unlockChapter(String map) {
        if (map == null) return 0;
        String name = map.toLowerCase(Locale.ROOT);
        if (!name.startsWith("infra_c")) return 0;
        int at = "infra_c".length(), end = at;
        while (end < name.length() && end < at + 2 && Character.isDigit(name.charAt(end))) end++;
        return end == at ? 0 : Integer.parseInt(name.substring(at, end));
    }
}
