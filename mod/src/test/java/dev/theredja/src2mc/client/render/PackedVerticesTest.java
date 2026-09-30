package dev.theredja.src2mc.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class PackedVerticesTest {
    private static void corner(PackedVertices out, float x, float z, int light) {
        out.add(x, 0, z, x, z, 0, 1, 0, light);
    }

    @Test
    void aQuadOfTwoTrianglesKeepsFourVertices() {
        PackedVertices out = new PackedVertices();
        corner(out, 0, 0, 7); corner(out, 1, 0, 7); corner(out, 1, 1, 7);
        corner(out, 0, 0, 7); corner(out, 1, 1, 7); corner(out, 0, 1, 7);
        out.index();
        assertEquals(4, out.distinctVertices());
        assertEquals(out.remapped(0), out.remapped(3));
        assertEquals(out.remapped(2), out.remapped(4));
        assertEquals(3, out.remapped(5));
    }

    @Test
    void verticesDifferingOnlyInLightStayApart() {
        PackedVertices out = new PackedVertices();
        corner(out, 0, 0, 7); corner(out, 1, 0, 7); corner(out, 1, 1, 7);
        corner(out, 0, 0, 8); corner(out, 1, 1, 7); corner(out, 0, 1, 7);
        out.index();
        assertEquals(5, out.distinctVertices());
        assertNotEquals(out.remapped(0), out.remapped(3));
    }

    @Test
    void manyVerticesGrowTheTableAndStayConsistent() {
        PackedVertices out = new PackedVertices();
        for (int i = 0; i < 3000; i++) corner(out, i % 50, i / 50, 0);
        for (int i = 0; i < 3000; i++) corner(out, i % 50, i / 50, 0);
        out.index();
        assertEquals(3000, out.distinctVertices());
        for (int i = 0; i < 3000; i++) assertEquals(out.remapped(i), out.remapped(i + 3000));
    }
}
