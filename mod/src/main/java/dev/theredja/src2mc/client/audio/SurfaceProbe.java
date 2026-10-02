package dev.theredja.src2mc.client.audio;

import dev.theredja.src2mc.bundle.SurfaceTable;

/**
 * Which map surface a sound comes from, found in the map's own surface table: the world's blocks
 * only say "map block", the faces say which material and so which {@code $surfaceprop}.
 */
final class SurfaceProbe {
    /** How far below the feet a floor still counts as stood on, and how far above. */
    private static final double BELOW = 0.6, ABOVE = 0.1;
    private static final double MIN_UP = 0.7;
    private static final double EPSILON = 1e-6;

    private SurfaceProbe() {}

    /**
     * The material of the floor under map-local feet at {@code (x, y, z)}: of the upward faces
     * whose polygon lies under the point, the highest that is not above the feet. -1 for none.
     */
    static int ground(SurfaceTable surfaces, double x, double y, double z) {
        int cx = (int) Math.floor(x), cz = (int) Math.floor(z);
        int best = -1;
        double bestHeight = Double.NEGATIVE_INFINITY;
        for (int cy = (int) Math.floor(y + ABOVE); cy >= (int) Math.floor(y - BELOW); cy--) {
            for (SurfaceTable.Face face : surfaces.facesAt(cx, cy, cz)) {
                double[] normal = face.normal();
                if (normal[1] < MIN_UP) continue;
                double px = x - cx, pz = z - cz;
                if (!containsXz(face, px, pz)) continue;
                // The face's plane, solved for height at the point.
                double vx = face.coordinate(0, 0), vy = face.coordinate(0, 1), vz = face.coordinate(0, 2);
                double height = cy + vy - (normal[0] * (px - vx) + normal[2] * (pz - vz)) / normal[1];
                if (height > y + ABOVE || height < y - BELOW || height <= bestHeight) continue;
                bestHeight = height;
                best = face.materialId();
            }
        }
        return best;
    }

    /** The material covering most of a cell's faces, for a sound the whole block makes. -1 for none. */
    static int block(SurfaceTable surfaces, int x, int y, int z) {
        int best = -1;
        double bestArea = 0;
        for (SurfaceTable.Face face : surfaces.facesAt(x, y, z)) {
            double area = area(face);
            if (area > bestArea) { bestArea = area; best = face.materialId(); }
        }
        return best;
    }

    /** Whether the face's outline, seen from above, holds the cell-relative point. */
    static boolean containsXz(SurfaceTable.Face face, double px, double pz) {
        int count = face.vertexCount();
        int sign = 0;
        for (int i = 0; i < count; i++) {
            int j = (i + 1) % count;
            double ax = face.coordinate(i, 0), az = face.coordinate(i, 2);
            double bx = face.coordinate(j, 0), bz = face.coordinate(j, 2);
            double cross = (bx - ax) * (pz - az) - (bz - az) * (px - ax);
            if (Math.abs(cross) <= EPSILON) continue;
            int side = cross > 0 ? 1 : -1;
            if (sign == 0) sign = side;
            else if (side != sign) return false;
        }
        return true;
    }

    private static double area(SurfaceTable.Face face) {
        double nx = 0, ny = 0, nz = 0;
        int count = face.vertexCount();
        for (int i = 0; i < count; i++) {
            int j = (i + 1) % count;
            double ay = face.coordinate(i, 1), az = face.coordinate(i, 2), ax = face.coordinate(i, 0);
            double by = face.coordinate(j, 1), bz = face.coordinate(j, 2), bx = face.coordinate(j, 0);
            nx += (ay - by) * (az + bz);
            ny += (az - bz) * (ax + bx);
            nz += (ax - bx) * (ay + by);
        }
        return Math.sqrt(nx * nx + ny * ny + nz * nz) / 2;
    }
}
