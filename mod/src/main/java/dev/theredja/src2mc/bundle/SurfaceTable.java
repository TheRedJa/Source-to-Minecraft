package dev.theredja.src2mc.bundle;

import java.util.List;
import java.util.Map;

/** Immutable sparse map-local surface lookup retained by a validated generation. */
public record SurfaceTable(List<UvRegion> uvRegions, Map<SectionPos, List<Face>> sections, List<LightRegion> lightRegions,
                           List<double[]> blendRegions) {
    /** Fragment vertex coordinates are in 1/4096 block within the owner cell. */
    public static final int CELL_UNITS = 4096;
    public static final int MAX_FRAGMENT_VERTICES = 64;

    /** A fragment's light-region ID when it has no lightmap. */
    public static final int NO_LIGHT = -1;

    public SurfaceTable(List<UvRegion> uvRegions, Map<SectionPos, List<Face>> sections) {
        this(uvRegions, sections, List.of());
    }

    public SurfaceTable(List<UvRegion> uvRegions, Map<SectionPos, List<Face>> sections, List<LightRegion> lightRegions) {
        this(uvRegions, sections, lightRegions, List.of());
    }

    /** A blended displacement fragment's blend at map-local {@code (x, y, z)}, 0 where it has none. */
    public double blend(Face face, double x, double y, double z) {
        if (face.blendRegionId() < 0 || face.blendRegionId() >= blendRegions.size()) return 0;
        double[] b = blendRegions.get(face.blendRegionId());
        return Math.max(0, Math.min(1, b[0] * x + b[1] * y + b[2] * z + b[3]));
    }

    public SurfaceTable {
        uvRegions = List.copyOf(uvRegions);
        lightRegions = List.copyOf(lightRegions);
        blendRegions = blendRegions.stream().map(double[]::clone).toList();
        sections = sections.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
            Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
    }

    public List<Face> facesAt(int x, int y, int z) {
        List<Face> bucket = sections.get(new SectionPos(x >> 4, y >> 4, z >> 4));
        if (bucket == null) return List.of();
        int local = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
        return bucket.stream().filter(face -> face.localCell() == local).toList();
    }

    public record SectionPos(int x, int y, int z) {}
    /**
     * One exact convex polygon clipped to its owner cell. {@code vertices} holds X, Y, Z per
     * vertex in 1/4096 block relative to the cell, counter-clockwise seen from the front.
     * An owned fragment is drawn only while its owner cell -- {@code localCell} plus the owner
     * offset, each axis -1..1 -- still holds {@code src2mc:surface}. The offset is non-zero when
     * the fragment sits just off the grid in an air cell in front of the block it rests on; an
     * unowned fragment always has a zero offset.
     */
    public record Face(int localCell, boolean owned, int ownerDx, int ownerDy, int ownerDz, int provenance,
                       int materialId, int uvRegionId, long sourcePrimary, long sourceSecondary, short[] vertices,
                       int lightRegionId, int blendRegionId) {
        public Face(int localCell, boolean owned, int ownerDx, int ownerDy, int ownerDz, int provenance,
                    int materialId, int uvRegionId, long sourcePrimary, long sourceSecondary, short[] vertices) {
            this(localCell, owned, ownerDx, ownerDy, ownerDz, provenance, materialId, uvRegionId, sourcePrimary, sourceSecondary,
                vertices, NO_LIGHT, NO_LIGHT);
        }

        public Face(int localCell, boolean owned, int ownerDx, int ownerDy, int ownerDz, int provenance,
                    int materialId, int uvRegionId, long sourcePrimary, long sourceSecondary, short[] vertices, int lightRegionId) {
            this(localCell, owned, ownerDx, ownerDy, ownerDz, provenance, materialId, uvRegionId, sourcePrimary, sourceSecondary,
                vertices, lightRegionId, NO_LIGHT);
        }

        public Face {
            if (Math.abs(ownerDx) > 1 || Math.abs(ownerDy) > 1 || Math.abs(ownerDz) > 1
                || (!owned && (ownerDx | ownerDy | ownerDz) != 0)) {
                throw new IllegalArgumentException("invalid fragment owner offset");
            }
            if (vertices.length < 9 || vertices.length % 3 != 0 || vertices.length > MAX_FRAGMENT_VERTICES * 3) {
                throw new IllegalArgumentException("fragment needs 3 to " + MAX_FRAGMENT_VERTICES + " vertices");
            }
            vertices = vertices.clone();
        }
        @Override public short[] vertices() { return vertices.clone(); }
        public int vertexCount() { return vertices.length / 3; }
        /** Coordinate {@code axis} (0 X, 1 Y, 2 Z) of vertex {@code index}, in blocks within the cell. */
        public double coordinate(int index, int axis) { return vertices[index * 3 + axis] / (double) CELL_UNITS; }
        /**
         * Unit front normal by Newell's method, robust to the collinear leading vertices a
         * clipped polygon can have; {0, 0, 0} for a degenerate polygon.
         */
        public double[] normal() {
            double nx = 0, ny = 0, nz = 0;
            int count = vertexCount();
            for (int i = 0; i < count; i++) {
                int a = i * 3, b = (i + 1) % count * 3;
                nx += (double) (vertices[a + 1] - vertices[b + 1]) * (vertices[a + 2] + vertices[b + 2]);
                ny += (double) (vertices[a + 2] - vertices[b + 2]) * (vertices[a] + vertices[b]);
                nz += (double) (vertices[a] - vertices[b]) * (vertices[a + 1] + vertices[b + 1]);
            }
            double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
            return length == 0 ? new double[3] : new double[] {nx / length, ny / length, nz / length};
        }
        @Override public boolean equals(Object other) {
            return other instanceof Face f && localCell == f.localCell && owned == f.owned && ownerDx == f.ownerDx
                && ownerDy == f.ownerDy && ownerDz == f.ownerDz && provenance == f.provenance
                && materialId == f.materialId && uvRegionId == f.uvRegionId && sourcePrimary == f.sourcePrimary
                && sourceSecondary == f.sourceSecondary && lightRegionId == f.lightRegionId && java.util.Arrays.equals(vertices, f.vertices);
        }
        @Override public int hashCode() {
            return java.util.Objects.hash(localCell, owned, ownerDx, ownerDy, ownerDz, provenance, materialId, uvRegionId, sourcePrimary, sourceSecondary,
                lightRegionId, java.util.Arrays.hashCode(vertices));
        }
    }
    /**
     * Where a fragment's lightmap is (format.md section 5): light page {@code page}, and the affine
     * map from a map-local block position to page coordinates, 0 to 1 across: {@code s} from the
     * first four values as UV regions are, {@code t} from the last four. {@code bump}: for a
     * bump-mapped face, how far right of the flat lightmap each of its three bump lightmaps starts,
     * as a fraction of the page width (bump lightmap k at {@code s + k * bump}); 0 for a flat face.
     */
    public record LightRegion(int page, double[] values, double bump) {
        public LightRegion { values = values.clone(); }
        public LightRegion(int page, double[] values) { this(page, values, 0); }
        @Override public double[] values() { return values.clone(); }
        public double s(double x, double y, double z) { return values[0] * x + values[1] * y + values[2] * z + values[3]; }
        public double t(double x, double y, double z) { return values[4] * x + values[5] * y + values[6] * z + values[7]; }
        @Override public boolean equals(Object other) {
            return other instanceof LightRegion r && page == r.page && java.util.Arrays.equals(values, r.values)
                && Double.compare(bump, r.bump) == 0;
        }
        @Override public int hashCode() { return 31 * (31 * page + java.util.Arrays.hashCode(values)) + Double.hashCode(bump); }
    }

    public record UvRegion(double[] values) {
        public UvRegion { values = values.clone(); }
        @Override public double[] values() { return values.clone(); }
        @Override public boolean equals(Object other) { return other instanceof UvRegion uv && java.util.Arrays.equals(values, uv.values); }
        @Override public int hashCode() { return java.util.Arrays.hashCode(values); }
    }
}
