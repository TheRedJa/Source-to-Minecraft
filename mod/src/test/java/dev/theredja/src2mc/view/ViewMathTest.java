package dev.theredja.src2mc.view;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class ViewMathTest {
    /** {@code MoverPose.angles}: yaw about y, then pitch as a turn about -z, then roll about x. */
    private static Quaternionf sourceAngles(double pitch, double yaw, double roll) {
        return new Quaternionf().rotateY((float) Math.toRadians(yaw)).rotateZ((float) -Math.toRadians(pitch))
            .rotateX((float) Math.toRadians(roll));
    }

    private static void assertVector(Vector3f expected, Vector3f actual) {
        assertEquals(expected.x, actual.x, 1e-4, "x of " + actual);
        assertEquals(expected.y, actual.y, 1e-4, "y of " + actual);
        assertEquals(expected.z, actual.z, 1e-4, "z of " + actual);
    }

    /** The Minecraft camera looks along the entity's forward axis with the entity's up as its up, for any angles. */
    @Test
    void cameraMatchesEntityAxes() {
        double[][] cases = {{0, 0, 0}, {0, 90, 0}, {30, 15, 0}, {-45, 200, 0}, {10, 30, 20}, {-20, 300, -35}, {80, 45, 90}};
        for (double[] c : cases) {
            Quaternionf entity = sourceAngles(c[0], c[1], c[2]);
            float[] angles = ViewMath.minecraftAngles(entity);
            Quaternionf camera = ViewMath.cameraRotation(angles[0], angles[1], angles[2]);
            assertVector(entity.transform(new Vector3f(1, 0, 0)), camera.transform(new Vector3f(0, 0, -1)));
            assertVector(entity.transform(new Vector3f(0, 1, 0)), camera.transform(new Vector3f(0, 1, 0)));
        }
    }

    /** Source yaw 0 looks along +x, which is Minecraft yaw -90; Source pitch down positive is Minecraft's too. */
    @Test
    void plainAnglesMatchMinecraftConvention() {
        float[] angles = ViewMath.minecraftAngles(sourceAngles(30, 0, 0));
        assertEquals(-90, angles[0], 1e-3);
        assertEquals(30, angles[1], 1e-3);
        assertEquals(0, angles[2], 1e-3);
    }
}
