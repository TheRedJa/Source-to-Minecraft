package dev.theredja.src2mc.view;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Turning a camera entity's rotation into the angles Minecraft's {@code Camera} is set up with. */
public final class ViewMath {
    private ViewMath() {}

    /** Source's forward and up axes, in Minecraft's axes ({@code MoverPose.angles}: Source x, y, z are Minecraft x, -z, y). */
    private static final Vector3f FORWARD = new Vector3f(1, 0, 0), UP = new Vector3f(0, 1, 0);

    /**
     * Minecraft {@code yRot}, {@code xRot} and roll in degrees whose {@code Camera.setRotation} looks along the
     * entity's forward axis with the entity's up axis as the view's up. {@code Camera} builds its rotation as
     * {@code rotationYXZ(PI - yRot, -xRot, -roll)} applied to a view looking down -z (Camera.java:115-123).
     */
    public static float[] minecraftAngles(Quaternionf rotation) {
        Vector3f forward = rotation.transform(new Vector3f(FORWARD));
        Vector3f up = rotation.transform(new Vector3f(UP));
        float pitch = (float) Math.toDegrees(-Math.asin(Math.max(-1, Math.min(1, forward.y))));
        float yaw = (float) Math.toDegrees(Math.atan2(-forward.x, forward.z));
        Vector3f flatUp = cameraRotation(yaw, pitch, 0).transform(new Vector3f(0, 1, 0));
        float roll = (float) Math.toDegrees(Math.atan2(new Vector3f(flatUp).cross(up).dot(forward), flatUp.dot(up)));
        // Camera turns about its own +z by -roll, which is +roll about the forward axis (-z).
        return new float[]{yaw, pitch, roll};
    }

    /** The rotation {@code Camera.setRotation(yRot, xRot, roll)} builds. */
    public static Quaternionf cameraRotation(float yRot, float xRot, float roll) {
        return new Quaternionf().rotationYXZ((float) Math.PI - (float) Math.toRadians(yRot), (float) -Math.toRadians(xRot),
            (float) -Math.toRadians(roll));
    }
}
