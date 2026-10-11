package dev.theredja.src2mc.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.theredja.src2mc.menu.MenuFlow.Action;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class MenuFlowTest {
    @Test void playDecisionTable() {
        assertEquals(Action.GAME_SCREEN, MenuFlow.play(true, 1), "a recent world wins");
        assertEquals(Action.GAME_SCREEN, MenuFlow.play(true, 7));
        assertEquals(Action.CHAPTERS, MenuFlow.play(false, 2));
        assertEquals(Action.CHAPTERS, MenuFlow.play(false, 10));
        assertEquals(Action.FIRST_MAP, MenuFlow.play(false, 1));
        assertEquals(Action.FIRST_MAP, MenuFlow.play(false, 0));
    }

    @Test void newGameDecisionTable() {
        assertEquals(Action.FIRST_MAP, MenuFlow.newGame(1));
        assertEquals(Action.CHAPTERS, MenuFlow.newGame(2));
        assertEquals(Action.CHAPTERS, MenuFlow.newGame(45));
    }

    private static final List<String> MAPS = List.of("infra_menu", "infra_c1_m1_office", "infra_c2_m1_reserve1", "infra_c3_m1_tunnel");

    @Test void aRestartDeletesTheCampaignsMapWorldsOnly() {
        Set<String> existing = Set.of("src2mc infra infra_menu", "src2mc infra infra_c1_m1_office", "src2mc infra infra_c3_m1_tunnel",
            "src2mc portal2 sp_a1_wakeup", "my own world", "src2mc infra not_a_map_of_the_bundle");
        List<String> wipe = MenuFlow.wipeFolders(existing, "infra", MAPS, "infra_menu", "src2mc infra infra_menu");
        assertEquals(List.of("src2mc infra infra_c1_m1_office", "src2mc infra infra_c3_m1_tunnel"), wipe);
    }

    @Test void theMenuWorldAndTheCurrentWorldAreNeverListed() {
        Set<String> existing = Set.of("src2mc infra infra_menu", "src2mc infra infra_c1_m1_office");
        assertEquals(List.of(), MenuFlow.wipeFolders(existing, "infra", MAPS, "infra_menu", "src2mc infra infra_c1_m1_office").stream()
            .filter(folder -> folder.contains("menu") || folder.contains("c1_m1")).toList());
        assertEquals(List.of(), MenuFlow.wipeFolders(Set.of("src2mc infra infra_menu"), "infra", MAPS, "infra_menu", null));
    }

    @Test void aCampaignNamedLikeItsMapUsesTheShortFolder() {
        assertEquals(List.of("src2mc escape_02"), MenuFlow.wipeFolders(Set.of("src2mc escape_02", "src2mc infra escape_02"), "escape_02",
            List.of("escape_02", "other"), "menu", null));
    }

    @Test void disabledControls() {
        Set<String> maps = Set.of("infra_c1_m1_office");
        assertTrue(MenuFlow.disabled("engine ent_fire menu_load_game_relay trigger", "#infra_menu_load_game", maps, true), "Load Game");
        assertTrue(MenuFlow.disabled("map infra_c9_m1_rails", "x", maps, true), "chapter whose map is not in the bundle");
        assertFalse(MenuFlow.disabled("map INFRA_c1_m1_office", "x", maps, true));
        assertTrue(MenuFlow.disabled("continue", "", maps, false));
        assertFalse(MenuFlow.disabled("continue", "", maps, true));
        assertFalse(MenuFlow.disabled("switchscreen scripts/screens/infra_menu_main.res", "#GameUI_Back", maps, false));
        assertFalse(MenuFlow.disabled("", "", maps, false));
    }
}
