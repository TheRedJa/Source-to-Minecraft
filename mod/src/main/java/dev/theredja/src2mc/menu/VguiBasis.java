package dev.theredja.src2mc.menu;

/**
 * Where a {@code vgui_screen} is, after the SDK's {@code vguiscreen.cpp} and {@code c_vguiscreen.cpp}:
 * the server rewrites the {@code angles} keyvalue to {@code MatrixFromAngles(angles) * RotAboutAxis(Y, 90) * RotateZ(90)},
 * which makes the screen's x axis the keyvalue frame's left, its y axis (up the screen) the frame's up and its
 * normal the frame's forward. The origin is the lower-left corner; panel pixel (0, 0) is the upper-left, x runs
 * along {@link #xAxis} and y down. All in Source units, as the keyvalues are.
 *
 * @param origin lower-left corner
 * @param xAxis  unit vector along the panel's x
 * @param yAxis  unit vector up the screen
 * @param normal unit vector out of the front face
 */
public record VguiBasis(double[] origin, double[] xAxis, double[] yAxis, double[] normal, double width, double height) {
    /** The basis of a screen with the keyvalues {@code origin}, {@code angles} (pitch yaw roll), {@code width} and {@code height}. */
    public static VguiBasis of(double[] origin, double[] angles, double width, double height) {
        double sp = Math.sin(Math.toRadians(angles[0])), cp = Math.cos(Math.toRadians(angles[0]));
        double sy = Math.sin(Math.toRadians(angles[1])), cy = Math.cos(Math.toRadians(angles[1]));
        double sr = Math.sin(Math.toRadians(angles[2])), cr = Math.cos(Math.toRadians(angles[2]));
        // AngleVectors of the keyvalue angles: forward, right, up.
        double[] forward = {cp * cy, cp * sy, -sp};
        double[] right = {-sr * sp * cy + cr * sy, -sr * sp * sy - cr * cy, -sr * cp};
        double[] up = {cr * sp * cy + sr * sy, cr * sp * sy - sr * cy, cr * cp};
        return new VguiBasis(origin.clone(), new double[]{-right[0], -right[1], -right[2]}, up, forward, width, height);
    }

    /** The upper-left corner, where panel pixel (0, 0) is. */
    public double[] upperLeft() {
        return new double[]{origin[0] + height * yAxis[0], origin[1] + height * yAxis[1], origin[2] + height * yAxis[2]};
    }

    /** The Source point of panel pixel ({@code px}, {@code py}) of a panel {@code pixelsWide} by {@code pixelsHigh}. */
    public double[] point(double px, double py, int pixelsWide, int pixelsHigh) {
        double[] ul = upperLeft();
        double u = px / pixelsWide * width, v = py / pixelsHigh * height;
        return new double[]{ul[0] + u * xAxis[0] - v * yAxis[0], ul[1] + u * xAxis[1] - v * yAxis[1], ul[2] + u * xAxis[2] - v * yAxis[2]};
    }

    /** {@code C_VGuiScreen::IsBackfacing}: the screen faces away from an eye when the normal points the way the eye looks at it. */
    public boolean backfacing(double[] eye) {
        return normal[0] * (origin[0] - eye[0]) + normal[1] * (origin[1] - eye[1]) + normal[2] * (origin[2] - eye[2]) > 0;
    }

    /**
     * Where a ray hits the screen, as panel pixels {@code [px, py, distance]}, or null when it misses, runs parallel
     * or hits from behind (hits behind the origin of the ray count as a miss).
     */
    public double[] intersect(double[] rayOrigin, double[] rayDirection, int pixelsWide, int pixelsHigh) {
        double denominator = normal[0] * rayDirection[0] + normal[1] * rayDirection[1] + normal[2] * rayDirection[2];
        if (Math.abs(denominator) < 1e-12) return null;
        double[] ul = upperLeft();
        double t = (normal[0] * (ul[0] - rayOrigin[0]) + normal[1] * (ul[1] - rayOrigin[1]) + normal[2] * (ul[2] - rayOrigin[2])) / denominator;
        if (t <= 0) return null;
        double hx = rayOrigin[0] + t * rayDirection[0] - ul[0], hy = rayOrigin[1] + t * rayDirection[1] - ul[1], hz = rayOrigin[2] + t * rayDirection[2] - ul[2];
        double u = hx * xAxis[0] + hy * xAxis[1] + hz * xAxis[2];
        double v = -(hx * yAxis[0] + hy * yAxis[1] + hz * yAxis[2]);
        if (u < 0 || v < 0 || u > width || v > height) return null;
        return new double[]{u / width * pixelsWide, v / height * pixelsHigh, t};
    }
}
