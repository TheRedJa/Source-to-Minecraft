package dev.theredja.src2mc.client.particles;

/** Source's {@code AngleVectors}: pitch, yaw and roll in degrees to forward, right and up. */
final class Angles {
    private Angles() {}

    /** Forward, right, up, nine values. */
    static double[] vectors(double pitch, double yaw, double roll) {
        double sp = Math.sin(Math.toRadians(pitch)), cp = Math.cos(Math.toRadians(pitch));
        double sy = Math.sin(Math.toRadians(yaw)), cy = Math.cos(Math.toRadians(yaw));
        double sr = Math.sin(Math.toRadians(roll)), cr = Math.cos(Math.toRadians(roll));
        return new double[]{
            cp * cy, cp * sy, -sp,
            -sr * sp * cy + cr * sy, -sr * sp * sy - cr * cy, -sr * cp,
            cr * sp * cy + sr * sy, cr * sp * sy - sr * cy, cr * cp};
    }

    /** A vector turned by the angles: {@code x forward + y left + z up}. */
    static double[] rotate(double pitch, double yaw, double roll, double x, double y, double z) {
        double[] v = vectors(pitch, yaw, roll);
        return new double[]{
            v[0] * x - v[3] * y + v[6] * z,
            v[1] * x - v[4] * y + v[7] * z,
            v[2] * x - v[5] * y + v[8] * z};
    }
}
