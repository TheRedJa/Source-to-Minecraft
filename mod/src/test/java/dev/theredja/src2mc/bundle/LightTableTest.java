package dev.theredja.src2mc.bundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class LightTableTest {
    /** One plane x = 0: leaf 0 in front (x >= 0), leaf 1 behind; one sample in each. */
    private static LightTable twoLeaves(float[] front, float[] back) {
        float[] cubes = new float[36];
        System.arraycopy(front, 0, cubes, 0, 18);
        System.arraycopy(back, 0, cubes, 18, 18);
        return new LightTable(List.of(), 0, new float[] {1, 0, 0, 0}, new int[] {-1, -2},
            new int[] {0, 1}, new int[] {1, 1}, new float[] {5, 0, 0, -5, 0, 0}, cubes, new byte[0][]);
    }

    private static float[] uniform(float value) {
        float[] cube = new float[18];
        java.util.Arrays.fill(cube, value);
        return cube;
    }

    @Test
    void aPointTakesTheSamplesOfItsOwnLeaf() {
        LightTable table = twoLeaves(uniform(2), uniform(0.5f));
        assertEquals(0, table.leafAt(3, 0, 0));
        assertEquals(1, table.leafAt(-3, 0, 0));
        float[] cube = new float[18];
        assertTrue(table.ambientAt(3, 1, 0, cube));
        assertEquals(2f, cube[0], 1e-6);
        assertTrue(table.ambientAt(-3, 1, 0, cube));
        assertEquals(0.5f, cube[4], 1e-6);
    }

    @Test
    void aCubeLightsANormalByItsSquaredComponents() {
        float[] cube = new float[18];
        // +X red, -Y green.
        cube[0] = 1;
        cube[9 + 1] = 1;
        float[] rgb = new float[3];
        LightTable.evaluate(cube, 1, 0, 0, rgb);
        assertEquals(1f, rgb[0], 1e-6);
        assertEquals(0f, rgb[1], 1e-6);
        LightTable.evaluate(cube, 1, -1, 0, rgb);
        assertEquals(0.5f, rgb[0], 1e-6);
        assertEquals(0.5f, rgb[1], 1e-6);
    }

    @Test
    void vertexLightIsGammaSpaceAtHalfRange() {
        assertEquals(1f, LightTable.vertexLight((byte) 127), 0.02);
        assertEquals(0f, LightTable.vertexLight((byte) 0), 1e-6);
    }
}
