package dev.theredja.src2mc.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class OcclusionCullerTest {
    @Test
    void cameraViewOnlyReusesACompletedQueryInsideItsConservativeWindow() {
        var queried = new OcclusionCuller.CameraView(new Vec3(10, 70, -3), 12.0f, -40.0f);
        assertTrue(queried.compatibleWith(new OcclusionCuller.CameraView(new Vec3(10.09, 70, -3), 13.4f, -38.6f), true));
        assertFalse(queried.compatibleWith(new OcclusionCuller.CameraView(new Vec3(10.11, 70, -3), 12.0f, -40.0f), true));
        assertFalse(queried.compatibleWith(new OcclusionCuller.CameraView(new Vec3(10, 70, -3), 13.6f, -40.0f), true));
    }

    @Test
    void cameraViewNeedsAnUnchangedAngleForABoxPartlyOffScreen() {
        var queried = new OcclusionCuller.CameraView(Vec3.ZERO, 12.0f, -40.0f);
        assertTrue(queried.compatibleWith(new OcclusionCuller.CameraView(Vec3.ZERO, 12.04f, -40.0f), false));
        assertFalse(queried.compatibleWith(new OcclusionCuller.CameraView(Vec3.ZERO, 12.2f, -40.0f), false));
        assertTrue(queried.compatibleWith(new OcclusionCuller.CameraView(Vec3.ZERO, 12.2f, -40.0f), true));
    }

    @Test
    void cameraViewHandlesRotationAcrossThePlusMinus180Boundary() {
        var queried = new OcclusionCuller.CameraView(Vec3.ZERO, 0.0f, 179.9f);
        assertTrue(queried.compatibleWith(new OcclusionCuller.CameraView(Vec3.ZERO, 0.0f, -179.95f), true));
    }

    @Test
    void queryPaddingGrowsWithTheFarthestCornerDistance() {
        var bounds = new AABB(0, 0, 0, 16, 16, 16);
        // Farthest corner from the origin is (16,16,16), sqrt(768) away.
        double expected = OcclusionCuller.BOUNDS_PADDING + OcclusionCuller.MAX_CAMERA_DELTA * Math.sqrt(768) / OcclusionCuller.MIN_OCCLUDER_DISTANCE;
        var query = OcclusionCuller.queryBounds(bounds, Vec3.ZERO);
        assertEquals(-expected, query.minX, 1.0e-9);
        assertEquals(16 + expected, query.maxZ, 1.0e-9);
        var far = OcclusionCuller.queryBounds(bounds, new Vec3(200, 8, 8));
        assertTrue(far.minX < query.minX);
    }

    @Test
    void cameraInsideCoversThePaddedBoxPlusNearMargin() {
        var bounds = new AABB(0, 0, 0, 16, 16, 16);
        var inRoom = new Vec3(8, 2, 8);
        assertTrue(OcclusionCuller.cameraInside(OcclusionCuller.queryBounds(bounds, inRoom), inRoom));
        // Just outside the box but within padding + NEAR_MARGIN.
        var nearby = new Vec3(-1.5, 8, 8);
        assertTrue(OcclusionCuller.cameraInside(OcclusionCuller.queryBounds(bounds, nearby), nearby));
        var outside = new Vec3(-10, 8, 8);
        assertFalse(OcclusionCuller.cameraInside(OcclusionCuller.queryBounds(bounds, outside), outside));
    }
}
