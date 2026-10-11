package dev.theredja.src2mc.menu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** A vgui_screen's rectangle from its keyvalues, checked on INFRA's main_menu screens. */
final class VguiBasisTest {
    private static VguiBasis mainScreen() {
        return VguiBasis.of(new double[]{42.4808, 70.0164, 29.8561}, new double[]{-5, 84, 0}, 14.08, 7.92);
    }

    @Test void mainMenuScreenStandsWhereTheKeyvaluesPutIt() {
        VguiBasis basis = mainScreen();
        double[] upperLeft = basis.upperLeft();
        assertEquals(42.4, upperLeft[0], 0.1);
        assertEquals(69.3, upperLeft[1], 0.1);
        assertEquals(37.7, upperLeft[2], 0.1);
        assertEquals(0.104, basis.normal()[0], 0.001);
        assertEquals(0.991, basis.normal()[1], 0.001);
        assertEquals(0.087, basis.normal()[2], 0.001);
        // x runs to the keyvalue frame's left (-y, mostly: +x... for yaw 84 left is (-sin, cos)(84) = (-0.995, 0.105)).
        assertEquals(-0.995, basis.xAxis()[0], 0.001);
        assertEquals(0.105, basis.xAxis()[1], 0.001);
        double dot = 0;
        for (int i = 0; i < 3; i++) dot += basis.xAxis()[i] * basis.yAxis()[i] + basis.normal()[i] * basis.yAxis()[i];
        assertEquals(0, dot, 1e-9);
    }

    @Test void panelPixelsRunFromTheUpperLeftAcrossAndDown() {
        VguiBasis basis = mainScreen();
        double[] corner = basis.point(0, 0, 890, 490), right = basis.point(890, 0, 890, 490), down = basis.point(0, 490, 890, 490);
        assertArrayEquals(basis.upperLeft(), corner, 1e-9);
        assertArrayEquals(basis.origin(), down, 1e-9, "the bottom of the panel is the origin's edge");
        double across = Math.sqrt(Math.pow(right[0] - corner[0], 2) + Math.pow(right[1] - corner[1], 2) + Math.pow(right[2] - corner[2], 2));
        assertEquals(14.08, across, 1e-9);
    }

    @Test void theMenuCameraSeesTheFrontAndAPointBehindDoesNot() {
        VguiBasis basis = mainScreen();
        assertFalse(basis.backfacing(new double[]{35, 98, 40}), "the menu_viewcontrol's origin looks at the front");
        assertTrue(basis.backfacing(new double[]{42, 40, 30}), "from the other side it is culled");
    }

    @Test void aRayThroughTheMiddleHitsTheMiddleOfThePanel() {
        VguiBasis basis = mainScreen();
        double[] eye = {35, 98, 40};
        double[] middle = basis.point(445, 245, 890, 490);
        double[] direction = {middle[0] - eye[0], middle[1] - eye[1], middle[2] - eye[2]};
        double[] hit = basis.intersect(eye, direction, 890, 490);
        assertNotNull(hit);
        assertEquals(445, hit[0], 1e-6);
        assertEquals(245, hit[1], 1e-6);
        double[] off = basis.point(2000, 245, 890, 490);
        assertNull(basis.intersect(eye, new double[]{off[0] - eye[0], off[1] - eye[1], off[2] - eye[2]}, 890, 490), "beyond the edge");
        assertNull(basis.intersect(eye, new double[]{-direction[0], -direction[1], -direction[2]}, 890, 490), "behind the ray");
    }

    @Test void theOptionsPhoneLiesFlatAndFacesUp() {
        // angles -90 -55 180: "a 6x8 phone lying flat on the desk, facing up".
        VguiBasis phone = VguiBasis.of(new double[]{50.3855, 82.0238, 28.3}, new double[]{-90, -55, 180}, 6, 8);
        assertEquals(1.0, phone.normal()[2], 1e-9);
        assertEquals(0, phone.xAxis()[2], 1e-9);
        assertEquals(0, phone.yAxis()[2], 1e-9);
    }

    @Test void sourceSpaceConvertsToTheMapsBlockFrame() {
        // menu_main_screen: origin 42.4808 70.0164 29.8561 is map-local (4.952525, 1.308003, 5.811988) with source_origin (3.625, 0.375, 8).
        SourceSpace space = new SourceSpace(3.625, 0.375, 8);
        double[] local = space.toLocal(new double[]{42.4808, 70.0164, 29.8561});
        assertEquals(4.952525, local[0], 1e-5);
        assertEquals(1.308003, local[1], 1e-5);
        assertEquals(5.8119875, local[2], 1e-5);
    }
}
