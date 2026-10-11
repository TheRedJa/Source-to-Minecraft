package dev.theredja.src2mc.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The chapter a map unlocks (INFRA's LevelInit rule) and the chapters layout's first maps. */
final class ChapterMapsTest {
    @Test void aChapterMapUnlocksTheDigitsAfterItsPrefix() {
        assertEquals(1, ChapterMaps.unlockChapter("infra_c1_m1_office"));
        assertEquals(4, ChapterMaps.unlockChapter("INFRA_C4_M2_FURNACE"));
        assertEquals(10, ChapterMaps.unlockChapter("infra_c10_m3_roof"));
        assertEquals(11, ChapterMaps.unlockChapter("infra_c11_ending_1"));
    }

    @Test void otherMapsUnlockNothing() {
        assertEquals(0, ChapterMaps.unlockChapter("infra_ee_hallway"));
        assertEquals(0, ChapterMaps.unlockChapter("main_menu"));
        assertEquals(0, ChapterMaps.unlockChapter("infra_c"));
        assertEquals(0, ChapterMaps.unlockChapter(null));
    }

    private static KvTree.Entry leaf(String key, String value) { return new KvTree.Entry(key, value, null); }

    private static KvTree.Entry block(String key, KvTree.Entry... entries) { return new KvTree.Entry(key, null, new KvTree(List.of(entries))); }

    @Test void firstMapsComeFromTheChapterControlsOnly() {
        KvTree layout = new KvTree(List.of(block("file.res", block("list",
            block("c1", leaf("ControlName", "CINFRAMenuItemChapter"), leaf("command", "map infra_c1_m1_office"), leaf("chapter", "1")),
            block("c2", leaf("ControlName", "CINFRAMenuItemChapter"), leaf("command", "map INFRA_c2_m1_reserve1"), leaf("chapter", "2")),
            block("debug", leaf("ControlName", "CINFRAMenuItemChapter"), leaf("command", "map infra_c2_m2_reserve2")),
            block("back", leaf("ControlName", "CINFRAMenuItemButton"), leaf("command", "map nowhere"), leaf("chapter", "3"))))));
        ScreensData data = new ScreensData(Map.of(), Map.of("x.res", layout), Map.of(), List.of(), List.of(), Map.of());
        assertEquals(Map.of(1, "infra_c1_m1_office", 2, "infra_c2_m1_reserve1"), ChapterMaps.firstMaps(data));
    }
}
