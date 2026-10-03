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
