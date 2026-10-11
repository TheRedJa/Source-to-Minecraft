package dev.theredja.src2mc.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Panel::ApplySettings' reading of xpos, ypos, wide and tall. */
final class PanelPositionTest {
    @Test void plainNumbersArePixels() {
        assertEquals(370, PanelPosition.position("370", 0, 150, 890));
        assertEquals(-60, PanelPosition.position("-60", 0, 720, 600));
        assertEquals(7, PanelPosition.position(null, 7, 10, 100), "an absent key keeps the position");
    }

    @Test void centreAndRightCountFromTheParent() {
        assertEquals(890 / 2 - 100, PanelPosition.position("c-100", 0, 50, 890));
        assertEquals(890 / 2 + 30, PanelPosition.position("c30", 0, 50, 890));
        assertEquals(890 - 50, PanelPosition.position("r50", 0, 50, 890));
        assertEquals(890 - 50, PanelPosition.position("R50", 0, 50, 890));
    }

    @Test void proportionalAndTrailingOperators() {
        assertEquals(445, PanelPosition.position("p0.5", 0, 10, 890));
        assertEquals(5, PanelPosition.position("s0.5", 0, 10, 890));
        assertEquals(30, PanelPosition.position("10+20", 0, 10, 890));
        assertEquals(445 - 20, PanelPosition.position("c0-20", 0, 10, 890));
        assertEquals(890 - 10 + 5, PanelPosition.position("r10+5", 0, 10, 890));
    }

    @Test void sizesFillTheParentOrCountPixels() {
        assertEquals(150, PanelPosition.size("150", 64, 890));
        assertEquals(890 - 20, PanelPosition.size("f20", 64, 890));
        assertEquals(64, PanelPosition.size(null, 64, 890));
        assertEquals(32, PanelPosition.size("s0.5", 64, 890));
    }

    @Test void flagsAreNonZeroNumbers() {
        assertEquals(true, PanelPosition.flag("1", false));
        assertEquals(false, PanelPosition.flag("0", true));
        assertEquals(true, PanelPosition.flag(null, true));
    }
}
