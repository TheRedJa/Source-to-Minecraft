package dev.theredja.src2mc.client.audio;

import dev.theredja.src2mc.bundle.BundleMap;
import dev.theredja.src2mc.bundle.BundleModel;
import dev.theredja.src2mc.bundle.BundleProp;
import java.util.ArrayList;
import java.util.List;

/**
 * Which prop is stood on, for its footsteps: each prop's box, map-local, with its model's
 * {@code $surfaceprop}. A box is the model's own bounds turned and scaled as the prop is placed,
 * so for a turned prop its top can sit a little above the real surface.
 */
final class PropGround {
    /**
     * A prop counts as stood on only when its top is this much above the map's floor there, so a
     * sheet of paper lying flat on concrete still sounds like concrete -- Source walks on solid
     * props only, and how solid a prop is is not in the bundle.
     */
    static final double ABOVE_FLOOR = 1.0 / 16.0;

    private record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, String surfaceProp) {}

    private final List<Box> boxes;

    private PropGround(List<Box> boxes) { this.boxes = boxes; }

    static PropGround of(BundleMap map) {
        List<Box> boxes = new ArrayList<>(map.props().size());
        for (BundleProp prop : map.props()) {
            BundleModel model = map.models().get(prop.modelIndex());
            float[] bounds = model.bounds();
            if (bounds == null) continue;
            double[] q = prop.rotation(), t = prop.translation();
            double scale = prop.scale();
            double[] min = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
            double[] max = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
            for (int corner = 0; corner < 8; corner++) {
                double[] p = rotate(q, bounds[(corner & 1) == 0 ? 0 : 3] * scale,
                    bounds[(corner & 2) == 0 ? 1 : 4] * scale, bounds[(corner & 4) == 0 ? 2 : 5] * scale);
                for (int axis = 0; axis < 3; axis++) {
                    min[axis] = Math.min(min[axis], p[axis] + t[axis]);
                    max[axis] = Math.max(max[axis], p[axis] + t[axis]);
                }
            }
            boxes.add(new Box(min[0], min[1], min[2], max[0], max[1], max[2], model.surfaceProp()));
        }
        return new PropGround(List.copyOf(boxes));
    }

    /** A prop stood on at map-local feet {@code (x, y, z)}: its top, and its surface property. */
    record Standing(double top, String surfaceProp) {}

    /** The highest prop top under the feet within the probe's window, or null. */
    Standing under(double x, double y, double z) {
        Box best = null;
        for (Box box : boxes) {
            if (x < box.minX || x > box.maxX || z < box.minZ || z > box.maxZ) continue;
            if (box.maxY > y + SurfaceProbe.ABOVE || box.maxY < y - SurfaceProbe.BELOW) continue;
            if (best == null || box.maxY > best.maxY) best = box;
        }
        return best == null ? null : new Standing(best.maxY, best.surfaceProp);
    }

    /** q * vector * inverse(q), with bundle quaternions stored as XYZW, as the prop renderer turns them. */
    private static double[] rotate(double[] q, double x, double y, double z) {
        double qx = q[0], qy = q[1], qz = q[2], qw = q[3];
        double tx = 2.0 * (qy * z - qz * y), ty = 2.0 * (qz * x - qx * z), tz = 2.0 * (qx * y - qy * x);
        return new double[]{x + qw * tx + qy * tz - qz * ty, y + qw * ty + qz * tx - qx * tz, z + qw * tz + qx * ty - qy * tx};
    }
}
