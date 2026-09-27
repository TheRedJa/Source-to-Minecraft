package dev.theredja.src2mc.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.theredja.src2mc.bundle.SurfaceTable;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class SurfaceOcclusionTest {
    /**
     * A room over a floor at y = 1, closed on its west side by a wall 8 units thick between
     * x = 1.0 and x = 1.25 -- thin enough to hold no block. West of it, cell (0, 1, 0), is open air.
     */
    private static SurfaceTable room() {
        // The wall's room side, facing +X at x = 1.25 inside cell (1, 1, 0).
        var wall = new SurfaceTable.Face(local(1, 1, 0), false, 0, 0, 0, 2, 0, 0, 1, 0,
            new short[]{1024, 0, 0, 1024, 4096, 0, 1024, 4096, 4096, 1024, 0, 4096});
        // Its outside, facing -X at x = 1.0, which belongs to the same cell behind it.
        var back = new SurfaceTable.Face(local(1, 1, 0), false, 0, 0, 0, 2, 0, 0, 2, 0,
            new short[]{0, 0, 0, 0, 0, 4096, 0, 4096, 4096, 0, 4096, 0});
        // The floor under the room, the top of cell (1, 0, 0).
        var floor = new SurfaceTable.Face(local(1, 0, 0), true, 0, 0, 0, 2, 0, 0, 3, 0,
            new short[]{0, 4096, 0, 0, 4096, 4096, 4096, 4096, 4096, 4096, 4096, 0});
        var faces = new java.util.ArrayList<>(List.of(floor, wall, back));
        faces.sort(java.util.Comparator.comparingInt(SurfaceTable.Face::localCell));
        return new SurfaceTable(List.of(new SurfaceTable.UvRegion(new double[8])),
            Map.of(new SurfaceTable.SectionPos(0, 0, 0), faces));
    }

    private static int local(int x, int y, int z) { return y << 8 | z << 4 | x; }

    @Test
    void aCornerVertexCannotSeeThroughTheWallItMeets() {
        var occlusion = new SurfaceOcclusion(room(), 0, 0, 0, (x, y, z) -> true);
        // Just above the floor, where it meets the wall.
        double x = 1.25, y = 1.0 + SurfaceOcclusion.START_OFFSET, z = 0.5;
        assertFalse(occlusion.visible(x, y, z, 0, 1, 0), "the air behind the wall");
        assertTrue(occlusion.visible(x, y, z, 1, 1, 0), "the room the vertex faces");
        assertTrue(occlusion.visible(x, y, z, 1, 2, 0), "the room above it");
        assertFalse(occlusion.visible(x, y, z, 1, 0, 0), "the floor's own block below");
        assertEquals(4, occlusion.tested);
        assertEquals(2, occlusion.blocked);
    }

    @Test
    void aBrokenBlockNoLongerShadesAnything() {
        // The floor's block is gone, so its fragment is not drawn and hides nothing.
        var occlusion = new SurfaceOcclusion(room(), 0, 0, 0, (x, y, z) -> !(x == 1 && y == 0 && z == 0));
        assertTrue(occlusion.visible(1.5, 1.0 + SurfaceOcclusion.START_OFFSET, 0.5, 1, 0, 0));
    }

    @Test
    void worksInWorldSpaceForATranslatedPlacement() {
        var occlusion = new SurfaceOcclusion(room(), 100, 64, -30, (x, y, z) -> true);
        assertFalse(occlusion.visible(101.25, 65.0 + SurfaceOcclusion.START_OFFSET, -29.5, 100, 65, -30));
        assertTrue(occlusion.visible(101.25, 65.0 + SurfaceOcclusion.START_OFFSET, -29.5, 101, 65, -30));
    }

    @Test
    void onlyAFrontToBackPassBlocks() {
        double[] square = {0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0};
        // Facing +Z at z = 0.
        assertTrue(SurfaceOcclusion.crosses(square, 0, 0, 1, 0, 0.5, 0.5, 1, 0.5, 0.5, -1));
        assertFalse(SurfaceOcclusion.crosses(square, 0, 0, 1, 0, 0.5, 0.5, -1, 0.5, 0.5, 1), "leaving from behind");
        assertFalse(SurfaceOcclusion.crosses(square, 0, 0, 1, 0, 2.5, 0.5, 1, 2.5, 0.5, -1), "passing beside it");
        assertTrue(SurfaceOcclusion.crosses(square, 0, 0, 1, 0, 0.5, 0.5, 0, 0.5, 0.5, -1), "starting on it");
    }
}
