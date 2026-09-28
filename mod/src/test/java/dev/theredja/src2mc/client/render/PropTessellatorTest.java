package dev.theredja.src2mc.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.theredja.src2mc.bundle.AtlasIndex;
import dev.theredja.src2mc.bundle.BundleMaterial;
import dev.theredja.src2mc.bundle.BundleProp;
import dev.theredja.src2mc.bundle.RuntimeMesh;
import java.util.List;
import org.junit.jupiter.api.Test;

final class PropTessellatorTest {
    private static final BundleMaterial.TextureReference MATERIAL = new BundleMaterial.TextureReference("a".repeat(64), 64, 64, 64, 64);
    private static final AtlasIndex.Texture TEXTURE = new AtlasIndex.Texture("a".repeat(64), 64, 64,
        List.of(new AtlasIndex.Region(new int[]{0, 0, 64, 64}, 0, new int[]{16, 32, 64, 64})));

    @Test void preservesAuthoredSheetCoordinatesAndModelTransform() {
        RuntimeMesh mesh = new RuntimeMesh(new float[]{0, 0, 0}, new float[]{1, 1, 0},
            new float[]{0, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1},
            new int[]{0, 1, 2}, new RuntimeMesh.Submesh[]{new RuntimeMesh.Submesh(0, 3, 0)});
        BundleProp prop = new BundleProp("b".repeat(64), 0, new int[]{0, 0, 0}, new double[]{16, 2, 3}, new double[]{0, 0, 0, 1}, 2);
        List<PropTessellator.Triangle> triangles = PropTessellator.tessellate(mesh, mesh.submeshes()[0], prop, MATERIAL, TEXTURE, 4096);
        assertEquals(1, triangles.size());
        PropTessellator.Vertex point = triangles.getFirst().a();
        assertEquals(16, point.x()); assertEquals(2, point.y()); assertEquals(3, point.z());
        assertEquals(16.0 / 4096.0, point.u()); assertEquals(32.0 / 4096.0, point.v());
    }

    @Test void clipsAPropTriangleIntoItsDerivedSectionCoverage() {
        PropTessellator.Triangle triangle = new PropTessellator.Triangle(0,
            new PropTessellator.Vertex(15, 1, 1, 0, 1, 0, 0, 0), new PropTessellator.Vertex(17, 1, 1, 0, 1, 0, 1, 0), new PropTessellator.Vertex(15, 2, 1, 0, 1, 0, 0, 1));
        List<PropTessellator.Triangle> left = PropTessellator.clipSection(triangle, 0, 0, 0);
        List<PropTessellator.Triangle> right = PropTessellator.clipSection(triangle, 16, 0, 0);
        assertTrue(!left.isEmpty() && !right.isEmpty());
        assertTrue(left.stream().flatMap(t -> List.of(t.a(), t.b(), t.c()).stream()).allMatch(v -> v.x() <= 16.000001));
        assertTrue(right.stream().flatMap(t -> List.of(t.a(), t.b(), t.c()).stream()).allMatch(v -> v.x() >= 15.999999));
    }

    @Test void preservesGeometryWhoseUvsAreInANegativeRepeat() {
        RuntimeMesh mesh = triangleWithUvs(-1, -1, 0, -1, -1, 0);
        BundleProp prop = new BundleProp("b".repeat(64), 0, new int[]{0, 0, 0}, new double[]{0, 0, 0}, new double[]{0, 0, 0, 1}, 1);
        List<PropTessellator.Triangle> triangles = PropTessellator.tessellate(mesh, mesh.submeshes()[0], prop, MATERIAL, TEXTURE, 4096);
        assertEquals(1, triangles.size());
        assertEquals(0, triangles.getFirst().a().x());
        assertEquals(1, triangles.getFirst().b().x());
        assertEquals(1, triangles.getFirst().c().y());
    }

    @Test void splitsGeometryAcrossEveryTextureRepeatWithoutDroppingIt() {
        RuntimeMesh mesh = triangleWithUvs(0, 0, 2, 0, 0, 1);
        BundleProp prop = new BundleProp("b".repeat(64), 0, new int[]{0, 0, 0}, new double[]{0, 0, 0}, new double[]{0, 0, 0, 1}, 1);
        List<PropTessellator.Triangle> triangles = PropTessellator.tessellate(mesh, mesh.submeshes()[0], prop, MATERIAL, TEXTURE, 4096);
        assertEquals(3, triangles.size());
        double area = triangles.stream().mapToDouble(PropTessellatorTest::area).sum();
        assertEquals(0.5, area, 1.0e-9);
    }

    private static RuntimeMesh triangleWithUvs(float au, float av, float bu, float bv, float cu, float cv) {
        return new RuntimeMesh(new float[]{0, 0, 0}, new float[]{1, 1, 0},
            new float[]{0, 0, 0, 0, 1, 0, au, av, 1, 0, 0, 0, 1, 0, bu, bv, 0, 1, 0, 0, 1, 0, cu, cv},
            new int[]{0, 1, 2}, new RuntimeMesh.Submesh[]{new RuntimeMesh.Submesh(0, 3, 0)});
    }

    /** A $nocull back face: same corners and texture, the other winding, the normal turned round. */
    @Test void aBackFaceMirrorsItsFront() {
        var a = new PropTessellator.Vertex(0, 0, 0, 0, 0, 1, 0.1, 0.2);
        var b = new PropTessellator.Vertex(1, 0, 0, 0, 0, 1, 0.3, 0.2);
        var c = new PropTessellator.Vertex(0, 1, 0, 0, 0, 1, 0.1, 0.4);
        var both = PropTessellator.withBackFaces(List.of(new PropTessellator.Triangle(2, a, b, c)));
        assertEquals(2, both.size());
        var back = both.get(1);
        assertEquals(2, back.page());
        assertEquals(List.of(a.x(), c.x(), b.x()), List.of(back.a().x(), back.b().x(), back.c().x()));
        assertEquals(List.of(a.y(), c.y(), b.y()), List.of(back.a().y(), back.b().y(), back.c().y()));
        assertEquals(-1.0, back.a().nz());
        assertEquals(c.u(), back.b().u());
        // Opposite winding: the geometric normal of the back points the other way.
        double front = (b.x() - a.x()) * (c.y() - a.y()) - (b.y() - a.y()) * (c.x() - a.x());
        double mirrored = (back.b().x() - back.a().x()) * (back.c().y() - back.a().y()) - (back.b().y() - back.a().y()) * (back.c().x() - back.a().x());
        assertTrue(front * mirrored < 0);
    }

    private static double area(PropTessellator.Triangle triangle) {
        double abx = triangle.b().x() - triangle.a().x(), aby = triangle.b().y() - triangle.a().y();
        double acx = triangle.c().x() - triangle.a().x(), acy = triangle.c().y() - triangle.a().y();
        return Math.abs(abx * acy - aby * acx) * 0.5;
    }
}
