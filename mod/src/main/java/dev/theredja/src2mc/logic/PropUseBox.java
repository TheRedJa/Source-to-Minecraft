package dev.theredja.src2mc.logic;

import dev.theredja.src2mc.bundle.BundleMap;
import dev.theredja.src2mc.bundle.MoverTable;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * What the use key aims at on a prop entity that has no brushes, a {@code prop_door_rotating}:
 * its model's box, turned and placed as the map compiled the prop. Source traces the use ray
 * against the prop's physics hull; the model's box is the nearest the bundle carries.
 * Coordinates are map-local blocks in the frame the entity was compiled in.
 */
public final class PropUseBox {
    private final double ox, oy, oz, scale;
    private final Quaterniond inverse;
    private final float[] bounds;

    private PropUseBox(double ox, double oy, double oz, Quaterniond inverse, double scale, float[] bounds) {
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        this.inverse = inverse;
        this.scale = scale;
        this.bounds = bounds;
    }

    /** The box of the prop that is entity {@code entity} itself, riding its own mover; null when there is none. */
    public static PropUseBox of(BundleMap map, int entity) {
        if (map == null || map.movers() == null) return null;
        int index = map.movers().indexOfEntity(entity);
        if (index < 0) return null;
        MoverTable.Mover mover = map.movers().movers().get(index);
        for (MoverTable.Prop prop : mover.props()) {
            if (prop.entity() != entity || prop.model() < 0 || prop.model() >= map.models().size()) continue;
            float[] bounds = map.models().get(prop.model()).bounds();
            if (bounds == null || !(prop.scale() > 0)) return null;
            double[] t = prop.translation(), q = prop.rotation();
            return new PropUseBox(mover.originX() + t[0], mover.originY() + t[1], mover.originZ() + t[2],
                new Quaterniond(q[0], q[1], q[2], q[3]).normalize().invert(), prop.scale(), bounds);
        }
        return null;
    }

    private Vector3d toModel(double x, double y, double z) {
        return inverse.transform(new Vector3d(x - ox, y - oy, z - oz)).div(scale);
    }

    /**
     * Where the segment from {@code from} along {@code direction} enters the box, as a fraction of
     * {@code direction}'s length within {@code [0, 1]}; -1 when it does not.
     */
    public double clip(double fx, double fy, double fz, double dx, double dy, double dz) {
        Vector3d from = toModel(fx, fy, fz);
        Vector3d direction = inverse.transform(new Vector3d(dx, dy, dz)).div(scale);
        double enter = 0, exit = 1;
        for (int axis = 0; axis < 3; axis++) {
            double start = from.get(axis), along = direction.get(axis), min = bounds[axis], max = bounds[axis + 3];
            if (along == 0) {
                if (start < min || start > max) return -1;
                continue;
            }
            double t0 = (min - start) / along, t1 = (max - start) / along;
            enter = Math.max(enter, Math.min(t0, t1));
            exit = Math.min(exit, Math.max(t0, t1));
            if (enter > exit) return -1;
        }
        return enter;
    }

    /** Distance in blocks from a map-local point to the box; 0 inside. */
    public double distance(double x, double y, double z) {
        Vector3d p = toModel(x, y, z);
        double sum = 0;
        for (int axis = 0; axis < 3; axis++) {
            double v = p.get(axis), outside = Math.max(0, Math.max(bounds[axis] - v, v - bounds[axis + 3]));
            sum += outside * outside;
        }
        return Math.sqrt(sum) * scale;
    }
}
