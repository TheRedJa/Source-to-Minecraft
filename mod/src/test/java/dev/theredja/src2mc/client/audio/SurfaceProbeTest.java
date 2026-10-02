package dev.theredja.src2mc.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.theredja.src2mc.bundle.SurfaceTable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class SurfaceProbeTest {
    private static final int UNIT = SurfaceTable.CELL_UNITS;

    /** A horizontal square over the whole cell at {@code height} blocks into it, facing up or down. */
    private static SurfaceTable.Face floor(int x, int y, int z, double height, int material, boolean up) {
        short h = (short) Math.round(height * UNIT);
        short[] corners = {0, h, 0, 0, h, (short) UNIT, (short) UNIT, h, (short) UNIT, (short) UNIT, h, 0};
        var face = new SurfaceTable.Face(local(x, y, z), false, 0, 0, 0, 0, material, 0, 0, 0, corners);
        if ((face.normal()[1] > 0) == up) return face;
        short[] reversed = new short[corners.length];
        for (int i = 0; i < 4; i++) System.arraycopy(corners, (3 - i) * 3, reversed, i * 3, 3);
        return new SurfaceTable.Face(local(x, y, z), false, 0, 0, 0, 0, material, 0, 0, 0, reversed);
    }

    private static int local(int x, int y, int z) { return (y & 15) << 8 | (z & 15) << 4 | (x & 15); }

    private static SurfaceTable table(SurfaceTable.Face... faces) {
        List<SurfaceTable.Face> sorted = new ArrayList<>(List.of(faces));
        sorted.sort(java.util.Comparator.comparingInt(SurfaceTable.Face::localCell));
        return new SurfaceTable(List.of(), Map.of(new SurfaceTable.SectionPos(0, 0, 0), sorted));
    }

    @Test void findsTheFloorUnderTheFeetInTheCellBelow() {
        SurfaceTable surfaces = table(floor(2, 4, 2, 1.0, 7, true));
        assertEquals(7, SurfaceProbe.ground(surfaces, 2.5, 5.0, 2.5));
        assertEquals(7, SurfaceProbe.ground(surfaces, 2.5, 5.3, 2.5), "slightly above, mid-step");
        assertEquals(-1, SurfaceProbe.ground(surfaces, 2.5, 6.0, 2.5), "a block up is not stood on");
        assertEquals(-1, SurfaceProbe.ground(surfaces, 3.5, 5.0, 2.5), "another column");
    }

    @Test void prefersTheHighestFloorAndIgnoresCeilings() {
        SurfaceTable surfaces = table(floor(2, 4, 2, 0.5, 1, true), floor(2, 4, 2, 0.75, 2, true), floor(2, 5, 2, 0.05, 3, false));
        assertEquals(2, SurfaceProbe.ground(surfaces, 2.5, 4.8, 2.5));
    }

    @Test void aBlockSoundsAsItsLargestFace() {
        short[] sliver = {0, 0, 0, 100, 0, 0, 0, 0, 100};
        var small = new SurfaceTable.Face(local(1, 1, 1), false, 0, 0, 0, 0, 5, 0, 0, 0, sliver);
        SurfaceTable surfaces = table(small, floor(1, 1, 1, 0.5, 9, true));
        assertEquals(9, SurfaceProbe.block(surfaces, 1, 1, 1));
        assertEquals(-1, SurfaceProbe.block(surfaces, 0, 0, 0));
    }

    @Test void containmentWorksForEitherWinding() {
        assertTrue(SurfaceProbe.containsXz(floor(0, 0, 0, 0, 0, true), 0.5, 0.5));
        assertTrue(SurfaceProbe.containsXz(floor(0, 0, 0, 0, 0, false), 0.5, 0.5));
    }
}
