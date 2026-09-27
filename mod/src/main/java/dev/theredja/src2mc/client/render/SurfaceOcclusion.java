package dev.theredja.src2mc.client.render;

import dev.theredja.src2mc.bundle.SurfaceTable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tells smooth light which of a vertex's eight neighbouring cells it can actually see, using the
 * map's own exact surfaces.
 *
 * Smooth light averages the cells around a point half a block in front of the vertex, and drops
 * solid blocks from the average. That was enough while surfaces sat on block planes: whatever
 * lay behind a wall was the wall's own block. Exact surfaces sit anywhere inside a cell, and thin
 * brushes hold no block at all, so a floor vertex where it meets a wall reaches through the wall
 * into the air on the other side -- and daylight or the next room's torches bleed into the
 * corner. A cell counts here only if the straight line from just in front of the vertex to the
 * cell's centre crosses no surface from its front to its back.
 *
 * One instance serves one region build. Map-local coordinates throughout; the world position a
 * query arrives in is shifted back by the placement's translation.
 */
final class SurfaceOcclusion implements LightSampler.CornerVisibility {
    /** How far in front of its own surface the line starts, in blocks. */
    static final double START_OFFSET = 1.0e-3;
    private static final double PLANE_EPSILON = 1.0e-6;
    private static final double EDGE_EPSILON = 1.0e-9;

    /** Whether the owner block of a fragment still stands, in map-local cell coordinates. */
    interface Presence { boolean present(int x, int y, int z); }

    private record Polygon(double[] points, double nx, double ny, double nz, double d) {}

    private final SurfaceTable table;
    private final int translationX, translationY, translationZ;
    private final Presence presence;
    private final Map<SurfaceTable.SectionPos, Map<Integer, List<Polygon>>> sections = new HashMap<>();
    long tested;
    long blocked;

    SurfaceOcclusion(SurfaceTable table, int translationX, int translationY, int translationZ, Presence presence) {
        this.table = table;
        this.translationX = translationX; this.translationY = translationY; this.translationZ = translationZ;
        this.presence = presence;
    }

    @Override
    public boolean visible(double fromX, double fromY, double fromZ, int cellX, int cellY, int cellZ) {
        tested++;
        double ax = fromX - translationX, ay = fromY - translationY, az = fromZ - translationZ;
        double bx = cellX + 0.5 - translationX, by = cellY + 0.5 - translationY, bz = cellZ + 0.5 - translationZ;
        // A fragment never leaves its cell, so only the cells the line's box covers can hold one
        // it crosses: at most two per axis.
        int minX = (int) Math.floor(Math.min(ax, bx)), maxX = (int) Math.floor(Math.max(ax, bx));
        int minY = (int) Math.floor(Math.min(ay, by)), maxY = (int) Math.floor(Math.max(ay, by));
        int minZ = (int) Math.floor(Math.min(az, bz)), maxZ = (int) Math.floor(Math.max(az, bz));
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    for (Polygon polygon : polygonsAt(x, y, z)) {
                        if (crosses(polygon, ax, ay, az, bx, by, bz)) {
                            blocked++;
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    /**
     * Whether the line from a to b passes through the polygon from its front to its back. A start
     * lying on the polygon's plane counts as in front: the vertex that starts the line sits on the
     * wall it meets, and that wall is exactly what has to stop it. Leaving a surface from behind
     * never counts; the line went in somewhere else first.
     */
    static boolean crosses(double[] points, double nx, double ny, double nz, double d,
                           double ax, double ay, double az, double bx, double by, double bz) {
        double da = nx * ax + ny * ay + nz * az - d;
        double db = nx * bx + ny * by + nz * bz - d;
        if (da < -PLANE_EPSILON || db >= -PLANE_EPSILON) return false;
        double t = da <= 0 ? 0 : da / (da - db);
        double px = ax + (bx - ax) * t, py = ay + (by - ay) * t, pz = az + (bz - az) * t;
        int count = points.length / 3;
        for (int i = 0; i < count; i++) {
            int a = i * 3, b = (i + 1) % count * 3;
            double ex = points[b] - points[a], ey = points[b + 1] - points[a + 1], ez = points[b + 2] - points[a + 2];
            double wx = px - points[a], wy = py - points[a + 1], wz = pz - points[a + 2];
            double cx = ey * wz - ez * wy, cy = ez * wx - ex * wz, cz = ex * wy - ey * wx;
            if (cx * nx + cy * ny + cz * nz < -EDGE_EPSILON) return false;
        }
        return true;
    }

    private static boolean crosses(Polygon polygon, double ax, double ay, double az, double bx, double by, double bz) {
        return crosses(polygon.points, polygon.nx, polygon.ny, polygon.nz, polygon.d, ax, ay, az, bx, by, bz);
    }

    private List<Polygon> polygonsAt(int x, int y, int z) {
        SurfaceTable.SectionPos section = new SurfaceTable.SectionPos(x >> 4, y >> 4, z >> 4);
        Map<Integer, List<Polygon>> cells = sections.computeIfAbsent(section, this::index);
        List<Polygon> polygons = cells.get(((y & 15) << 8) | ((z & 15) << 4) | (x & 15));
        return polygons == null ? List.of() : polygons;
    }

    /** Every drawn fragment of one section, by cell, as absolute map-local polygons. */
    private Map<Integer, List<Polygon>> index(SurfaceTable.SectionPos section) {
        List<SurfaceTable.Face> faces = table.sections().get(section);
        if (faces == null) return Map.of();
        Map<Integer, List<Polygon>> cells = new HashMap<>();
        int baseX = section.x() << 4, baseY = section.y() << 4, baseZ = section.z() << 4;
        for (SurfaceTable.Face face : faces) {
            int local = face.localCell();
            int x = baseX + (local & 15), y = baseY + (local >> 8 & 15), z = baseZ + (local >> 4 & 15);
            // A surface whose block was broken is gone, and no longer shades anything.
            if (face.owned() && !presence.present(x + face.ownerDx(), y + face.ownerDy(), z + face.ownerDz())) continue;
            double[] normal = face.normal();
            if (normal[0] == 0 && normal[1] == 0 && normal[2] == 0) continue;
            int count = face.vertexCount();
            double[] points = new double[count * 3];
            for (int i = 0; i < count; i++) {
                points[i * 3] = x + face.coordinate(i, 0);
                points[i * 3 + 1] = y + face.coordinate(i, 1);
                points[i * 3 + 2] = z + face.coordinate(i, 2);
            }
            double d = normal[0] * points[0] + normal[1] * points[1] + normal[2] * points[2];
            cells.computeIfAbsent(local, ignored -> new ArrayList<>())
                .add(new Polygon(points, normal[0], normal[1], normal[2], d));
        }
        return cells;
    }
}
