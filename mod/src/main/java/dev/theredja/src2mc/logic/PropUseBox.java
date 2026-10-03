package dev.theredja.src2mc.logic;

import dev.theredja.src2mc.bundle.BundleMap;
import dev.theredja.src2mc.bundle.MoverTable;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * What the use key aims at on a prop entity that has no brushes, a {@code prop_door_rotating}:
 * its model, turned and placed as the map compiled the prop. Source traces the use ray against
 * the prop's physics hull; the model's own triangles are the nearest the bundle carries, and its
 * box stands in while the mesh is not loaded and for reach. The box alone was too big: a button
 * on the face of a locked fence door lay inside the door's box, so the door took every use.
 * Coordinates are map-local blocks in the frame the entity was compiled in.
 */
public final class PropUseBox {
    private final double ox, oy, oz, scale;
    private final Quaterniond inverse;
    private final float[] bounds;
    private final int model;

    private PropUseBox(double ox, double oy, double oz, Quaterniond inverse, double scale, float[] bounds, int model) {
        this.model = model;
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
                new Quaterniond(q[0], q[1], q[2], q[3]).normalize().invert(), prop.scale(), bounds, prop.model());
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

    /** The map model reference the prop wears. */
    public int model() { return model; }

    /**
     * Where the segment first meets one of the mesh's triangles, from either side, as a fraction
     * of {@code direction}'s length within {@code [0, 1]}; -1 when it meets none.
     */
    public double clip(dev.theredja.src2mc.bundle.RuntimeMesh mesh, double fx, double fy, double fz, double dx, double dy, double dz) {
        if (clip(fx, fy, fz, dx, dy, dz) < 0) return -1;
        Vector3d from = toModel(fx, fy, fz);
        Vector3d direction = inverse.transform(new Vector3d(dx, dy, dz)).div(scale);
        float[] v = mesh.vertices();
        int[] indices = mesh.indices();
        double best = -1;
        for (int i = 0; i + 2 < indices.length; i += 3) {
            int a = indices[i] * 8, b = indices[i + 1] * 8, c = indices[i + 2] * 8;
            double t = intersect(from, direction, v[a], v[a + 1], v[a + 2], v[b], v[b + 1], v[b + 2], v[c], v[c + 1], v[c + 2]);
            if (t >= 0 && t <= 1 && (best < 0 || t < best)) best = t;
        }
        return best;
    }

    /** Möller-Trumbore: the ray parameter where it crosses the triangle, either side facing; -1 for none. */
    private static double intersect(Vector3d o, Vector3d d, double ax, double ay, double az, double bx, double by, double bz,
                                    double cx, double cy, double cz) {
        double e1x = bx - ax, e1y = by - ay, e1z = bz - az, e2x = cx - ax, e2y = cy - ay, e2z = cz - az;
        double px = d.y * e2z - d.z * e2y, py = d.z * e2x - d.x * e2z, pz = d.x * e2y - d.y * e2x;
        double det = e1x * px + e1y * py + e1z * pz;
        if (Math.abs(det) < 1e-12) return -1;
        double inv = 1 / det;
        double sx = o.x - ax, sy = o.y - ay, sz = o.z - az;
        double u = (sx * px + sy * py + sz * pz) * inv;
        if (u < 0 || u > 1) return -1;
        double qx = sy * e1z - sz * e1y, qy = sz * e1x - sx * e1z, qz = sx * e1y - sy * e1x;
        double w = (d.x * qx + d.y * qy + d.z * qz) * inv;
        if (w < 0 || u + w > 1) return -1;
        return (e2x * qx + e2y * qy + e2z * qz) * inv;
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
