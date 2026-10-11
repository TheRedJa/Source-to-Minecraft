package dev.theredja.src2mc.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The viewcontrol's glide maths against INFRA's (infra_menu_re.md): smootherstep, Catmull-Rom, quaternion angles. */
final class ViewPathTest {
    @Test void smootherStepIsFlatAtTheEndsAndHalfwayInTheMiddle() {
        assertEquals(0, ViewPath.smootherStep(0), 1e-12);
        assertEquals(1, ViewPath.smootherStep(1), 1e-12);
        assertEquals(0.5, ViewPath.smootherStep(0.5), 1e-12);
        assertEquals(0, ViewPath.smootherStep(-3), 1e-12, "held to 0..1");
        assertEquals(1, ViewPath.smootherStep(7), 1e-12);
        // 6t^5 - 15t^4 + 10t^3 at 0.25.
        assertEquals(6 * Math.pow(0.25, 5) - 15 * Math.pow(0.25, 4) + 10 * Math.pow(0.25, 3), ViewPath.smootherStep(0.25), 1e-12);
        assertEquals(0.0, (ViewPath.smootherStep(1e-4) - ViewPath.smootherStep(0)) / 1e-4, 1e-6, "no speed at the start");
    }

    @Test void twoPointCatmullRomIsTheSegmentsOwnCurve() {
        double d = 10;
        for (double u : new double[]{0, 0.1, 0.5, 0.9, 1}) {
            // Both missing neighbours are the segment's end points: x(u) = d (0.5 u + 1.5 u^2 - u^3).
            assertEquals(d * (0.5 * u + 1.5 * u * u - u * u * u), ViewPath.catmullRom(0, 0, d, d, u), 1e-12);
        }
        assertEquals(0, ViewPath.catmullRom(1, 2, 3, 4, 0) - 2, 1e-12, "passes through p2 at 0");
        assertEquals(3, ViewPath.catmullRom(1, 2, 3, 4, 1), 1e-12, "and p3 at 1");
    }

    private static ViewPath.Pose pose(double x, double yaw, double fov) { return new ViewPath.Pose(x, 0, 0, 0, yaw, 0, fov); }

    @Test void aPathStartsAndEndsOnItsPointsAndPassesTheMiddleOfTwo() {
        List<ViewPath.Pose> two = List.of(pose(0, 270, 42), pose(10, 270, 39));
        assertEquals(0, ViewPath.at(two, 0).x(), 1e-9);
        assertEquals(10, ViewPath.at(two, 1).x(), 1e-9);
        // d (0.25 + 0.375 - 0.125) = d / 2.
        assertEquals(5, ViewPath.at(two, 0.5).x(), 1e-9);
        assertEquals(40.5, ViewPath.at(two, 0.5).fov(), 1e-9);
        assertEquals(270, Math.floorMod(Math.round(ViewPath.at(two, 0.5).yaw()), 360), "yaw is the same angle, however it is wrapped");
        // Three points are two segments of equal time: halfway is the middle point.
        List<ViewPath.Pose> three = List.of(pose(0, 0, 40), pose(4, 0, 40), pose(10, 0, 40));
        assertEquals(4, ViewPath.at(three, 0.5).x(), 1e-9);
        assertEquals(10, ViewPath.evaluate(three, 5, true).x(), 1e-9, "t is held to 0..1");
    }

    @Test void smoothSpeedGoesThroughSmootherStep() {
        List<ViewPath.Pose> two = List.of(pose(0, 0, 40), pose(10, 0, 40));
        assertEquals(ViewPath.at(two, ViewPath.smootherStep(0.3)).x(), ViewPath.evaluate(two, 0.3, true).x(), 1e-12);
        assertEquals(ViewPath.at(two, 0.3).x(), ViewPath.evaluate(two, 0.3, false).x(), 1e-12);
    }

    @Test void quaternionsRoundTripAnglesAndTakeTheShortWay() {
        for (double[] angles : new double[][]{{13, 270, 0}, {-5, 84, 0}, {68, 311, 0}, {355 - 360, 90, 20}, {0, -170, -35}}) {
            double[] back = ViewPath.quaternionAngles(ViewPath.angleQuaternion(angles[0], angles[1], angles[2]));
            assertEquals(Math.floorMod(Math.round(angles[1]), 360), Math.floorMod(Math.round(back[1]), 360), 1e-6);
            assertEquals(angles[0], back[0], 1e-6);
            assertEquals(angles[2], back[2], 1e-6);
        }
        // Yaw 350 to 10 passes 0, not 180: the aligned quaternions interpolate the short way.
        List<ViewPath.Pose> turn = List.of(pose(0, 350, 40), pose(0, 10, 40));
        assertEquals(0, Math.floorMod(Math.round(ViewPath.at(turn, 0.5).yaw()) + 180, 360) - 180, 1e-6);
    }

    @Test void sourceFovIsHorizontalForFourByThree() {
        assertEquals(2 * Math.toDegrees(Math.atan(Math.tan(Math.toRadians(45)) * 0.75)), ViewPath.verticalFov(90), 1e-9);
        assertEquals(73.74, ViewPath.verticalFov(90), 0.01);
        assertEquals(32.12, ViewPath.verticalFov(42), 0.01);
    }

    @Test void sourceYawBecomesMinecraftYaw() {
        // Source yaw 270 faces -y, which is Minecraft's +z (yaw 0); Source yaw 0 faces +x, Minecraft's -90.
        assertEquals(0f, ViewPath.minecraftYaw(270), 1e-4f);
        assertEquals(-90f, ViewPath.minecraftYaw(0), 1e-4f);
        assertEquals(90f, ViewPath.minecraftYaw(180), 1e-4f);
    }
}
