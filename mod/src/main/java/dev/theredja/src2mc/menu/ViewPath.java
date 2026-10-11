package dev.theredja.src2mc.menu;

import java.util.List;

/**
 * The path a {@code point_viewcontrol} glides along (INFRA's {@code CINFRAViewControl}, see
 * {@code source_refs/data/infra_menu_re.md}): pure maths shared by the server, which times the glide
 * for {@code OnEndFollow}, and the client, which draws each frame from it.
 *
 * <p>Positions are map-local blocks; angles are Source's {@code pitch yaw roll} in degrees and
 * {@code fov} Source's {@code fov} keyvalue, which is horizontal degrees for a 4:3 view.
 */
public final class ViewPath {
    private ViewPath() {}

    /** One camera pose: a viewcontrol's own, or a {@code point_viewcontrol_node}'s. */
    public record Pose(double x, double y, double z, double pitch, double yaw, double roll, double fov) {}

    /** {@code t^3 (6 t^2 - 15 t + 10)}, held to 0..1: how {@code smoothspeed} turns time into progress. */
    public static double smootherStep(double t) {
        double c = Math.max(0, Math.min(1, t));
        return c * c * c * (c * (c * 6 - 15) + 10);
    }

    /** Mathlib's scalar {@code Catmull_Rom_Spline} through {@code p2} (t 0) and {@code p3} (t 1). */
    public static double catmullRom(double p1, double p2, double p3, double p4, double t) {
        double tSqr = t * t * 0.5, tCube = t * tSqr, half = t * 0.5;
        return tCube * (-p1 + 3 * p2 - 3 * p3 + p4) + tSqr * (2 * p1 - 5 * p2 + 4 * p3 - p4) + half * (p3 - p1) + p2;
    }

    /** Source's {@code AngleQuaternion} for {@code pitch yaw roll} in degrees: {@code x y z w}. */
    public static double[] angleQuaternion(double pitch, double yaw, double roll) {
        double sy = Math.sin(Math.toRadians(yaw) * 0.5), cy = Math.cos(Math.toRadians(yaw) * 0.5);
        double sp = Math.sin(Math.toRadians(pitch) * 0.5), cp = Math.cos(Math.toRadians(pitch) * 0.5);
        double sr = Math.sin(Math.toRadians(roll) * 0.5), cr = Math.cos(Math.toRadians(roll) * 0.5);
        double srCp = sr * cp, crSp = cr * sp, crCp = cr * cp, srSp = sr * sp;
        return new double[]{srCp * cy - crSp * sy, crSp * cy + srCp * sy, crCp * sy - srSp * cy, crCp * cy + srSp * sy};
    }

    /** Source's {@code QuaternionAngles} ({@code QuaternionMatrix} then {@code MatrixAngles}): {@code pitch yaw roll} in degrees. */
    public static double[] quaternionAngles(double[] q) {
        double x = q[0], y = q[1], z = q[2], w = q[3];
        double forwardX = 1 - 2 * y * y - 2 * z * z, forwardY = 2 * x * y + 2 * w * z, forwardZ = 2 * x * z - 2 * w * y;
        double leftX = 2 * x * y - 2 * w * z, leftY = 1 - 2 * x * x - 2 * z * z, leftZ = 2 * y * z + 2 * w * x;
        double upZ = 1 - 2 * x * x - 2 * y * y;
        double xyDist = Math.sqrt(forwardX * forwardX + forwardY * forwardY);
        double yaw, pitch = Math.atan2(-forwardZ, xyDist), roll;
        if (xyDist > 0.001) {
            yaw = Math.atan2(forwardY, forwardX);
            roll = Math.atan2(leftZ, upZ);
        } else {
            yaw = Math.atan2(-leftX, leftY);
            roll = 0;
        }
        return new double[]{Math.toDegrees(pitch), Math.toDegrees(yaw), Math.toDegrees(roll)};
    }

    /** {@code QuaternionAlign}: {@code q} or its negation, whichever lies on the same side as {@code p}. */
    public static double[] align(double[] p, double[] q) {
        double a = 0, b = 0;
        for (int i = 0; i < 4; i++) {
            a += (p[i] - q[i]) * (p[i] - q[i]);
            b += (p[i] + q[i]) * (p[i] + q[i]);
        }
        return a > b ? new double[]{-q[0], -q[1], -q[2], -q[3]} : q;
    }

    /**
     * The pose {@code progress} (0..1, already smoothed) of the way along {@code path}: N points
     * make N-1 segments of equal time, each a Catmull-Rom spline whose missing neighbours are the
     * segment's own end points. At the end it is exactly the last point.
     */
    public static Pose at(List<Pose> path, double progress) {
        int n = path.size();
        if (n == 0) throw new IllegalArgumentException("empty path");
        if (n == 1 || progress >= 1) return path.get(n - 1);
        double s = Math.max(0, progress);
        int segments = n - 1;
        int index = Math.min(segments - 1, (int) Math.floor(s * segments));
        double u = Math.min(1, (s - index / (double) segments) * segments);
        Pose p2 = path.get(index), p3 = path.get(index + 1);
        Pose p1 = index > 0 ? path.get(index - 1) : p2, p4 = index + 2 < n ? path.get(index + 2) : p3;
        // Angles: quaternions aligned along the chain, spline per component, normalised.
        double[] q2 = angleQuaternion(p2.pitch, p2.yaw, p2.roll);
        double[] q1 = align(q2, angleQuaternion(p1.pitch, p1.yaw, p1.roll));
        double[] q3 = align(q2, angleQuaternion(p3.pitch, p3.yaw, p3.roll));
        double[] q4 = align(q3, angleQuaternion(p4.pitch, p4.yaw, p4.roll));
        double[] q = new double[4];
        double length = 0;
        for (int i = 0; i < 4; i++) {
            q[i] = catmullRom(q1[i], q2[i], q3[i], q4[i], u);
            length += q[i] * q[i];
        }
        length = Math.sqrt(length);
        if (length > 0) for (int i = 0; i < 4; i++) q[i] /= length;
        double[] angles = quaternionAngles(q);
        return new Pose(catmullRom(p1.x, p2.x, p3.x, p4.x, u), catmullRom(p1.y, p2.y, p3.y, p4.y, u), catmullRom(p1.z, p2.z, p3.z, p4.z, u),
            angles[0], angles[1], angles[2], catmullRom(p1.fov, p2.fov, p3.fov, p4.fov, u));
    }

    /** The pose at glide time {@code t} (0..1): through {@link #smootherStep} when the viewcontrol has {@code smoothspeed}. */
    public static Pose evaluate(List<Pose> path, double t, boolean smooth) {
        double clamped = Math.max(0, Math.min(1, t));
        return at(path, smooth ? smootherStep(clamped) : clamped);
    }

    /**
     * The vertical field of view in degrees for a Source {@code fov}, horizontal for a 4:3 view:
     * {@code ScaleFOVByWidthRatio(fov, aspect / (4/3))} leaves the vertical angle at
     * {@code 2 atan(tan(fov / 2) * 3 / 4)} whatever the window's shape.
     */
    public static double verticalFov(double sourceFov) {
        return Math.toDegrees(2 * Math.atan(Math.tan(Math.toRadians(sourceFov) / 2) * 0.75));
    }

    /** Source's yaw as Minecraft's (Source counter-clockwise from +x with y as -z; Minecraft 0 faces +z): {@code -90 - yaw}. */
    public static float minecraftYaw(double sourceYaw) {
        return (float) net.minecraft.util.Mth.wrapDegrees(-90.0 - sourceYaw);
    }
}
