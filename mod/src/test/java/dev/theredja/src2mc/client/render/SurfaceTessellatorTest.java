package dev.theredja.src2mc.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.theredja.src2mc.bundle.AtlasIndex;
import dev.theredja.src2mc.bundle.BundleMaterial;
import dev.theredja.src2mc.bundle.SurfaceTable;
import java.util.List;
import org.junit.jupiter.api.Test;

final class SurfaceTessellatorTest {
    /** The lower-left 0.5x0.5 of a cell's z=0 plane, facing +Z: the old south micro-patch. */
    private static final short[] SOUTH_LOWER_LEFT = {0, 0, 0, 2048, 0, 0, 2048, 2048, 0, 0, 2048, 0};

    @Test
    void placesFragmentVerticesAtTheirExactFractionalPositions() {
        // A pentagon off the half-block grid: 1/4096-block coordinates, in cell (3, -2, 5).
        short[] vertices = {100, 4000, 1234, 4096, 4000, 1234, 4096, 4000, 4096, 2000, 4000, 4096, 100, 4000, 3000};
        var face = new SurfaceTable.Face(0, true, 0, 0, 0, 2, 0, 0, 0, 0, vertices);
        var uv = new SurfaceTable.UvRegion(new double[]{1, 0, 0, 0, 0, 0, 1, 0});
        var material = new BundleMaterial.TextureReference("id", 16, 16, 16, 16);
        var texture = new AtlasIndex.Texture("id", 16, 16,
            List.of(new AtlasIndex.Region(new int[]{0, 0, 16, 16}, 0, new int[]{0, 0, 16, 16})));
        var triangles = SurfaceTessellator.tessellate(3, -2, 5, face, uv, material, texture, 4096);

        assertEquals(3, triangles.size());
        var expected = new java.util.ArrayList<List<Double>>();
        for (int i = 0; i < 5; i++) {
            expected.add(List.of(3 + vertices[i * 3] / 4096.0, -2 + vertices[i * 3 + 1] / 4096.0, 5 + vertices[i * 3 + 2] / 4096.0));
        }
        var produced = triangles.stream().flatMap(t -> List.of(t.a(), t.b(), t.c()).stream())
            .map(vertex -> List.of(vertex.x(), vertex.y(), vertex.z())).distinct().toList();
        assertEquals(new java.util.HashSet<>(expected), new java.util.HashSet<>(produced));
        // Wound counter-clockwise seen from below, so this is a ceiling; the fan must keep that.
        for (var triangle : triangles) {
            double abx = triangle.b().x() - triangle.a().x(), abz = triangle.b().z() - triangle.a().z();
            double acx = triangle.c().x() - triangle.a().x(), acz = triangle.c().z() - triangle.a().z();
            assertTrue(abz * acx - abx * acz < 0);
        }
    }

    @Test
    void clipsOneFaceAcrossLosslessRegionsAndRemapsEachPage() {
        var face = new SurfaceTable.Face(0, false, 0, 0, 0, 0, 0, 0, 0, 0, SOUTH_LOWER_LEFT);
        var uv = new SurfaceTable.UvRegion(new double[]{40, 0, 0, 4054, 0, 40, 0, 0});
        var material = new BundleMaterial.TextureReference("id", 8192, 8192, 8192, 8192);
        var texture = new AtlasIndex.Texture("id", 8192, 8192, List.of(
            new AtlasIndex.Region(new int[]{0, 0, 4064, 8192}, 0, new int[]{16, 16, 4064, 8192}),
            new AtlasIndex.Region(new int[]{4064, 0, 4128, 8192}, 1, new int[]{16, 16, 4128, 8192})
        ));
        var triangles = SurfaceTessellator.tessellate(0, 0, 0, face, uv, material, texture, 16384);
        assertTrue(triangles.stream().anyMatch(triangle -> triangle.page() == 0));
        assertTrue(triangles.stream().anyMatch(triangle -> triangle.page() == 1));
        assertEquals(4, triangles.size());
        assertTrue(triangles.stream().flatMap(t -> List.of(t.a(), t.b(), t.c()).stream())
            .allMatch(vertex -> vertex.u() >= 0 && vertex.u() <= 1 && vertex.v() >= 0 && vertex.v() <= 1));
    }

    @Test
    void preservesNegativeTextureRepeats() {
        var face = new SurfaceTable.Face(0, false, 0, 0, 0, 0, 0, 0, 0, 0, SOUTH_LOWER_LEFT);
        var uv = new SurfaceTable.UvRegion(new double[]{16, 0, 0, -4, 0, 16, 0, -4});
        var material = new BundleMaterial.TextureReference("id", 16, 16, 16, 16);
        var texture = new AtlasIndex.Texture("id", 16, 16,
            List.of(new AtlasIndex.Region(new int[]{0, 0, 16, 16}, 0, new int[]{16, 16, 16, 16})));
        var triangles = SurfaceTessellator.tessellate(0, 0, 0, face, uv, material, texture, 4096);
        assertTrue(triangles.size() >= 2);
        assertTrue(triangles.stream().flatMap(t -> List.of(t.a(), t.b(), t.c()).stream())
            .allMatch(vertex -> vertex.u() >= 16.0 / 4096 && vertex.u() <= 32.0 / 4096));
    }
}
