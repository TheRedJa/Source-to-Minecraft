package dev.theredja.src2mc.client.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

class DecalBasisTest {
    @Test
    void wallDecalsStandUpright() {
        // A wall facing +X, seen from +X: S runs to the viewer's right (-Z), T straight down.
        double[][] basis = DecalRenderer.basis(1, 0, 0);
        assertArrayEquals(new double[] {0, 0, -1}, basis[0], 1e-9);
        assertArrayEquals(new double[] {0, -1, 0}, basis[1], 1e-9);
    }

    @Test
    void floorDecalsRunAlongX() {
        // A floor: S along +X; T = S x N, Source's -Y, Minecraft's +Z.
        double[][] basis = DecalRenderer.basis(0, 1, 0);
        assertArrayEquals(new double[] {1, 0, 0}, basis[0], 1e-9);
        assertArrayEquals(new double[] {0, 0, 1}, basis[1], 1e-9);
    }
}
