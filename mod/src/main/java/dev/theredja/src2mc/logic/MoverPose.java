package dev.theredja.src2mc.logic;

import org.joml.Quaterniond;

/**
 * Where a moving entity is, relative to where the map compiled it: an offset in map-local blocks,
 * and its rotation about its origin. A compiled point {@code p} is drawn at
 * {@code origin + offset + rotation * (p - origin)}. A brush entity's geometry is compiled at
 * angles 0, so the rotation is the entity's absolute angles, as the engine draws it.
 */
public record MoverPose(double x, double y, double z, double qx, double qy, double qz, double qw) {
    public static final MoverPose IDENTITY = new MoverPose(0, 0, 0, 0, 0, 0, 1);

    public Quaterniond rotation() { return new Quaterniond(qx, qy, qz, qw); }

    static MoverPose of(double[] offset, Quaterniond rotation) {
        return new MoverPose(offset[0], offset[1], offset[2], rotation.x, rotation.y, rotation.z, rotation.w);
    }

    /**
     * Source's {@code AngleMatrix} of pitch, yaw and roll in degrees, in Minecraft's axes. Source's
     * x, y, z are Minecraft's x, -z, y; the change of basis keeps handedness, so a turn about
     * Source's up axis is the same turn about Minecraft's y, about Source's y the opposite turn
     * about Minecraft's z.
     */
    static Quaterniond angles(double pitch, double yaw, double roll) {
        return new Quaterniond()
            .rotateY(Math.toRadians(yaw))
            .rotateZ(-Math.toRadians(pitch))
            .rotateX(Math.toRadians(roll));
    }

    /**
     * A child's pose with its parent's applied: Source moves a child in its parent's frame, so a
     * compiled point goes through the child's own move and then the parent's. Each pose turns
     * about its own entity's origin; the result turns about the child's.
     */
    static MoverPose compose(MoverPose parent, double[] parentOrigin, MoverPose child, double[] childOrigin) {
        Quaterniond parentRotation = parent.rotation();
        // Where the child's origin ends up: its own move, then the parent's.
        org.joml.Vector3d moved = new org.joml.Vector3d(childOrigin[0] + child.x - parentOrigin[0],
            childOrigin[1] + child.y - parentOrigin[1], childOrigin[2] + child.z - parentOrigin[2]);
        parentRotation.transform(moved);
        double[] offset = {moved.x + parentOrigin[0] + parent.x - childOrigin[0], moved.y + parentOrigin[1] + parent.y - childOrigin[1],
            moved.z + parentOrigin[2] + parent.z - childOrigin[2]};
        return of(offset, parentRotation.mul(child.rotation()));
    }

    /** Source's {@code VectorAngles} of a map-local direction: pitch (down positive) and yaw, in degrees; roll 0. */
    static double[] vectorAngles(double x, double y, double z) {
        double sx = x, sy = -z, sz = y;
        if (sx == 0 && sy == 0) return new double[]{sz > 0 ? 270 : 90, 0, 0};
        double yaw = Math.toDegrees(Math.atan2(sy, sx));
        if (yaw < 0) yaw += 360;
        double pitch = Math.toDegrees(Math.atan2(-sz, Math.sqrt(sx * sx + sy * sy)));
        if (pitch < 0) pitch += 360;
        return new double[]{pitch, yaw, 0};
    }

    /** A Source direction or offset, in Source units, as map-local blocks. */
    static double[] sourceToBlocks(double[] source) {
        return new double[]{source[0] / 32.0, source[2] / 32.0, -source[1] / 32.0};
    }

    /** Parses a {@code pitch yaw roll} keyvalue; missing parts are 0. */
    static double[] parseAngles(String value) {
        double[] out = new double[3];
        if (value == null) return out;
        String[] parts = value.trim().split("\\s+");
        for (int i = 0; i < 3 && i < parts.length; i++) out[i] = Variant.number(parts[i]);
        return out;
    }
}
