package dev.theredja.src2mc.client.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class LogicOverlayTest {
    @Test void aBoxBrushHasTwelveEdges() {
        double[][] box = {{1, 0, 0, 2}, {-1, 0, 0, -1}, {0, 1, 0, 3}, {0, -1, 0, 0}, {0, 0, 1, 1}, {0, 0, -1, 0.5}};
        List<Float> edges = new ArrayList<>();
        LogicOverlay.edges(box, edges);
        assertEquals(12 * 6, edges.size());
    }

    @Test void aWedgeHasNineEdges() {
        // A box cut by a slope: six corners, nine edges.
        double s = Math.sqrt(0.5);
        double[][] wedge = {{-1, 0, 0, 0}, {0, -1, 0, 0}, {0, 0, -1, 0}, {0, 0, 1, 1}, {s, s, 0, s}};
        List<Float> edges = new ArrayList<>();
        LogicOverlay.edges(wedge, edges);
        assertEquals(9 * 6, edges.size());
    }
}
