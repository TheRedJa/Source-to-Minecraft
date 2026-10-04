package dev.theredja.src2mc.logic;

import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * A rigid move of map-local points, {@code p -> rotation * p + translation}: what a
 * {@link MoverPose} is once its origin is folded in, so that moves compose, invert and change
 * hands between parents. Immutable; every operation makes a new one.
 */
record Rigid(double qx, double qy, double qz, double qw, double x, double y, double z) {
    static final Rigid IDENTITY = new Rigid(0, 0, 0, 1, 0, 0, 0);

    static Rigid of(Quaterniond rotation, Vector3d translation) {
        return new Rigid(rotation.x, rotation.y, rotation.z, rotation.w, translation.x, translation.y, translation.z);
    }

    /** A pose turning about {@code origin}: {@code p -> origin + offset + R (p - origin)}. */
    static Rigid of(MoverPose pose, double[] origin) {
        Quaterniond rotation = pose.rotation();
        Vector3d turned = rotation.transform(new Vector3d(origin[0], origin[1], origin[2]));
        return of(rotation, new Vector3d(origin[0] + pose.x() - turned.x, origin[1] + pose.y() - turned.y, origin[2] + pose.z() - turned.z));
    }

    Quaterniond rotation() { return new Quaterniond(qx, qy, qz, qw); }

    Vector3d translation() { return new Vector3d(x, y, z); }

    /** The same move as a pose turning about {@code origin}. */
    MoverPose about(double[] origin) {
        Vector3d turned = rotation().transform(new Vector3d(origin[0], origin[1], origin[2]));
        return new MoverPose(x + turned.x - origin[0], y + turned.y - origin[1], z + turned.z - origin[2], qx, qy, qz, qw);
    }

    /** This move after {@code inner}: {@code p -> this(inner(p))}. */
    Rigid after(Rigid inner) {
        Quaterniond rotation = rotation();
        Vector3d moved = rotation.transform(inner.translation()).add(x, y, z);
        return of(rotation.mul(inner.rotation(), new Quaterniond()), moved);
    }

    Rigid inverse() {
        Quaterniond back = rotation().conjugate();
        Vector3d moved = back.transform(new Vector3d(-x, -y, -z));
        return of(back, moved);
    }

    /** Moves a map-local point. */
    Vector3d apply(Vector3d point) { return rotation().transform(point).add(x, y, z); }

    /** Whether the two moves differ by less than {@code epsilon} in every component, either sign of the rotation. */
    boolean near(Rigid other, double epsilon) {
        double sign = qx * other.qx + qy * other.qy + qz * other.qz + qw * other.qw < 0 ? -1 : 1;
        return Math.abs(qx - sign * other.qx) < epsilon && Math.abs(qy - sign * other.qy) < epsilon && Math.abs(qz - sign * other.qz) < epsilon
            && Math.abs(qw - sign * other.qw) < epsilon && Math.abs(x - other.x) < epsilon && Math.abs(y - other.y) < epsilon
            && Math.abs(z - other.z) < epsilon;
    }
}
