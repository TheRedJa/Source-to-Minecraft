package dev.theredja.src2mc.menu;

import dev.theredja.src2mc.levels.LevelTransition;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** The menu's decisions that do not need the game: what Play and New Game do, which world folders a restart wipes, which controls are off. */
public final class MenuFlow {
    private MenuFlow() {}

    public enum Action {
        /** Show {@code infra_menu_main_game.res} (Continue / Load / New Game). */
        GAME_SCREEN,
        /** Fire {@code menu_chapters_relay}, which glides to the chapters screen. */
        CHAPTERS,
        /** Start the campaign's chapter 1 map. */
        FIRST_MAP
    }

    /** INFRA's {@code play}: a recent save shows the game screen, else more than one chapter unlocked shows the chapters, else the first map. */
    public static Action play(boolean recentWorld, int unlockedChapters) {
        return recentWorld ? Action.GAME_SCREEN : newGame(unlockedChapters);
    }

    /** INFRA's {@code new_game}: chapters when more than the first is unlocked, else the first map. */
    public static Action newGame(int unlockedChapters) {
        return unlockedChapters > 1 ? Action.CHAPTERS : Action.FIRST_MAP;
    }

    /**
     * The world folders a restart of the campaign deletes: those of the campaign's maps other than the menu map's that
     * exist, never the folder of the menu map and never {@code currentFolder}. Other campaigns' folders are not
     * named by any of the campaign's maps and so never listed.
     */
    public static List<String> wipeFolders(Collection<String> existingFolders, String campaign, Collection<String> campaignMaps,
                                           String menuMap, String currentFolder) {
        String menuFolder = LevelTransition.folderName(campaign, menuMap);
        List<String> out = new ArrayList<>();
        for (String map : campaignMaps) {
            String folder = LevelTransition.folderName(campaign, map);
            if (folder.equals(menuFolder) || folder.equals(currentFolder) || !existingFolders.contains(folder) || out.contains(folder)) continue;
            out.add(folder);
        }
        return out;
    }

    /**
     * Whether a control is shown disabled: Load Game (saves are not implemented), a {@code map} the campaign's
     * bundle does not hold, or Continue with no world to continue.
     */
    public static boolean disabled(String command, String labelText, Set<String> campaignMapsLower, boolean canContinue) {
        String lower = command == null ? "" : command.trim().toLowerCase(Locale.ROOT);
        if (lower.isEmpty()) return false;
        if (lower.contains("menu_load_game_relay") || lower.startsWith("load ") || lower.contains("infra_menu_load_game.res")) return true;
        if (labelText != null && labelText.equalsIgnoreCase("#infra_menu_load_game")) return true;
        String map = ChapterMaps.mapOf(command);
        if (map != null) return !campaignMapsLower.contains(map);
        return lower.equals("continue") && !canContinue;
    }
}
