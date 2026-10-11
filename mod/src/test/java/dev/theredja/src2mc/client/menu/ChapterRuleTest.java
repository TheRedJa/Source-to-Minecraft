package dev.theredja.src2mc.client.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.theredja.src2mc.bundle.LogicTable;
import dev.theredja.src2mc.menu.KvTree;
import dev.theredja.src2mc.menu.ScreensData;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** INFRA's chapter unlock rule, and how the chapters list lays out what it leaves visible. */
final class ChapterRuleTest {
    @Test void aFreshGameShowsOnlyTheFirstChapter() {
        assertFalse(ChapterRule.hidden(1, 1, 1));
        for (int chapter = 2; chapter <= 10; chapter++) assertTrue(ChapterRule.hidden(1, chapter, chapter <= 6 ? 1 : chapter <= 8 ? 2 : 3), "chapter " + chapter);
    }

    @Test void unlockedChaptersAreVisibleUpToTheNumber() {
        assertFalse(ChapterRule.hidden(7, 7, 2));
        assertTrue(ChapterRule.hidden(7, 8, 2));
        assertFalse(ChapterRule.hidden(4, 3, 1));
    }

    @Test void aMultipleOfFifteenUnlocksWholeParts() {
        assertFalse(ChapterRule.hidden(15, 6, 1), "part 1 is open");
        assertFalse(ChapterRule.hidden(15, 1, 1));
        assertTrue(ChapterRule.hidden(15, 7, 2), "part 2 is not");
        assertFalse(ChapterRule.hidden(30, 9, 2));
        assertTrue(ChapterRule.hidden(30, 10, 3));
        assertFalse(ChapterRule.hidden(45, 10, 3));
    }

    private static KvTree.Entry leaf(String key, String value) { return new KvTree.Entry(key, value, null); }

    private static KvTree.Entry block(String key, KvTree.Entry... entries) { return new KvTree.Entry(key, null, new KvTree(List.of(entries))); }

    private static KvTree.Entry button(String name, String control, int position, int chapter) {
        return chapter > 0
            ? block(name, leaf("ControlName", control), leaf("labelText", name), leaf("command", "map " + name), leaf("listposition", "" + position),
                leaf("chapter", "" + chapter), leaf("part", "1"))
            : block(name, leaf("ControlName", control), leaf("labelText", name), leaf("command", "back"), leaf("listposition", "" + position));
    }

    @Test void theListPlacesVisibleItemsInRowsAndKeepsAnEmptyPositionBetweenThemBlank() {
        // Positions 0..4, nothing at 5, then 6; a playtest item at 7; the back button at 8.
        KvTree.Entry list = block("list", leaf("ControlName", "CINFRAMenuItemList"), leaf("xpos", "210"), leaf("ypos", "40"), leaf("wide", "300"),
            leaf("tall", "480"),
            button("c1", "CINFRAMenuItemChapter", 0, 1), button("c2", "CINFRAMenuItemChapter", 1, 2), button("c3", "CINFRAMenuItemChapter", 2, 3),
            button("c4", "CINFRAMenuItemChapter", 3, 4), button("c5", "CINFRAMenuItemChapter", 4, 5), button("c6", "CINFRAMenuItemChapter", 6, 6),
            block("office", leaf("ControlName", "CINFRAMenuItemChapter"), leaf("labelText", "office"), leaf("command", "map x"), leaf("listposition", "7"),
                leaf("visibleinplaytest", "1")),
            button("back", "CINFRAMenuItemButton", 8, 0));
        KvTree layout = new KvTree(List.of(block("file.res", list)));
        ScreensData data = new ScreensData(java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), List.of(), List.of(), java.util.Map.of());
        VguiScheme scheme = new VguiScheme(null);

        VguiLayout.Result fresh = VguiLayout.build(data, scheme, layout, 700, 600, 1);
        assertEquals("list c1 back", names(fresh), "only chapter 1 and the back button");
        VguiLayout.Widget c1 = find(fresh, "c1"), back = find(fresh, "back");
        assertEquals(210, c1.x);
        assertEquals(300, c1.w);
        assertEquals(40, c1.h);
        assertEquals(40, c1.y);
        assertEquals(40 + 2 * 40, back.y, "position 5 holds no item, so it stays a blank row; the locked chapters and the playtest item take none");

        VguiLayout.Result all = VguiLayout.build(data, scheme, layout, 700, 600, 10);
        assertEquals("list c1 c2 c3 c4 c5 c6 back", names(all));
        assertEquals(40 + 5 * 40 + 40, find(all, "c6").y, "position 5 is empty: a blank row between chapters 5 and 6");
        assertEquals(40 + 7 * 40, find(all, "back").y, "the playtest item at 7 takes no row");
    }

    private static String names(VguiLayout.Result result) { return result.order().stream().map(widget -> widget.name).collect(Collectors.joining(" ")); }

    private static VguiLayout.Widget find(VguiLayout.Result result, String name) {
        return result.order().stream().filter(widget -> widget.name.equals(name)).findFirst().orElseThrow();
    }
}
