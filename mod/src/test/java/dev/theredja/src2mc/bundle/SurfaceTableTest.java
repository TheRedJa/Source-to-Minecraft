package dev.theredja.src2mc.bundle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class SurfaceTableTest {
    @Test void resolvesSignedSectionAndPackedLocalCell() {
        var face = new SurfaceTable.Face((2 << 8) | (3 << 4) | 4, true, 0, 0, 0, 0, 7, 9, 11, 13, new short[]{0, 0, 0, 4096, 0, 0, 0, 4096, 0});
        var table = new SurfaceTable(List.of(), Map.of(new SurfaceTable.SectionPos(-1, 1, -2), List.of(face)));
        assertEquals(List.of(face), table.facesAt(-12, 18, -29));
        assertEquals(List.of(), table.facesAt(-11, 18, -29));
    }

    @Test void copiesVerticesAndComputesTheFrontNormal() {
        short[] vertices = {0, 0, 0, 4096, 0, 0, 0, 4096, 0};
        var face = new SurfaceTable.Face(0, false, 0, 0, 0, 2, 0, 0, 0, 0, vertices);
        vertices[0] = 99;
        assertEquals(0, face.vertices()[0]);
        org.junit.jupiter.api.Assertions.assertArrayEquals(new double[]{0, 0, 1}, face.normal(), 1e-12);
        // A sloped fragment: its normal is the polygon's own, not an axis.
        var slope = new SurfaceTable.Face(0, false, 0, 0, 0, 0, 0, 0, 0, 0, new short[]{0, 0, 0, 4096, 0, 0, 4096, 4096, 4096, 0, 4096, 4096});
        double r = Math.sqrt(0.5);
        org.junit.jupiter.api.Assertions.assertArrayEquals(new double[]{0, -r, r}, slope.normal(), 1e-12);
    }

    @Test void rejectsOffsetsThatAreOutOfRangeOrOnAnUnownedFragment() {
        short[] triangle = {0, 0, 0, 4096, 0, 0, 0, 4096, 0};
        var owned = new SurfaceTable.Face(0, true, 1, -1, 0, 0, 0, 0, 0, 0, triangle);
        assertEquals(-1, owned.ownerDy());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> new SurfaceTable.Face(0, true, 2, 0, 0, 0, 0, 0, 0, 0, triangle));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> new SurfaceTable.Face(0, false, 0, -1, 0, 0, 0, 0, 0, 0, triangle));
    }
}
