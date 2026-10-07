package dev.theredja.src2mc.bundle;

import java.util.List;

/**
 * A map's baked light (format.md section 22), as vrad compiled it: lightmap pages for the brush
 * faces and displacements, ambient light samples in the BSP leaves with the tree that finds a
 * point's leaf, and the per-vertex light of each static prop. Positions are map-local blocks.
 *
 * <p>Compared and hashed by identity, as {@link BundleMap} is.
 */
public final class LightTable {
    /** Source units per block: the ambient weighting is in Source units, as the engine's is. */
    private static final double UNITS_PER_BLOCK = 32.0;

    /** One page of {@code ColorRGBExp32} luxels, row by row. */
    public record Page(int width, int height, byte[] luxels) {}

    private final List<Page> pages;
    private final int root;
    /** Per node: normal X, Y, Z and distance. */
    private final float[] planes;
    /** Per node: two children, a node index or {@code -1 - leaf}. */
    private final int[] children;
    private final int[] leafFirst, leafCount;
    /** Per sample: X, Y, Z. */
    private final float[] positions;
    /** Per sample: +X, -X, +Y, -Y, +Z, -Z light, RGB each, linear. */
    private final float[] cubes;
    /** Per prop of the placement table: RGB per mesh vertex as the .vhv stores it, or null. */
    private final byte[][] propLight;

    public LightTable(List<Page> pages, int root, float[] planes, int[] children, int[] leafFirst, int[] leafCount,
                      float[] positions, float[] cubes, byte[][] propLight) {
        this.pages = List.copyOf(pages);
        this.root = root;
        this.planes = planes;
        this.children = children;
        this.leafFirst = leafFirst;
        this.leafCount = leafCount;
        this.positions = positions;
        this.cubes = cubes;
        this.propLight = propLight;
    }

    public List<Page> pages() { return pages; }

    public int sampleCount() { return positions.length / 3; }

    /** The vertex light of prop {@code index} of the placement table, RGB per mesh vertex; null for none. */
    public byte[] propLight(int index) {
        return index >= 0 && index < propLight.length ? propLight[index] : null;
    }

    /** The leaf the point is in, or -1 off the tree. */
    public int leafAt(double x, double y, double z) {
        int cursor = root;
        int nodes = children.length / 2;
        for (int steps = 0; steps <= nodes && cursor >= 0; steps++) {
            if (cursor >= nodes) return -1;
            double distance = planes[cursor * 4] * x + planes[cursor * 4 + 1] * y + planes[cursor * 4 + 2] * z - planes[cursor * 4 + 3];
            cursor = children[cursor * 2 + (distance >= 0 ? 0 : 1)];
        }
        if (cursor >= 0) return -1;
        int leaf = -1 - cursor;
        return leaf < leafFirst.length ? leaf : -1;
    }

    /**
     * The ambient light cube at a point, into {@code cube} (18 floats, +X, -X, +Y, -Y, +Z, -Z):
     * the samples of the point's leaf, each weighted by {@code 1 / (d² + 1)} with {@code d} in
     * Source units, as noclip.website's {@code computeAmbientCubeFromLeaf} does. A point in a leaf
     * without samples -- in a wall, or outside the map -- takes the nearest samples anywhere
     * instead. False only when the map has no samples at all.
     */
    public boolean ambientAt(double x, double y, double z, float[] cube) {
        java.util.Arrays.fill(cube, 0f);
        int leaf = leafAt(x, y, z);
        int first, count;
        if (leaf >= 0 && leafCount[leaf] > 0) {
            first = leafFirst[leaf];
            count = leafCount[leaf];
        } else {
            first = 0;
            count = sampleCount();
            if (count == 0) return false;
            // The nearest sample alone: weighting all of them would average the whole map.
            int nearest = 0;
            double best = Double.MAX_VALUE;
            for (int i = 0; i < count; i++) {
                double d = distanceSquared(i, x, y, z);
                if (d < best) { best = d; nearest = i; }
            }
            first = nearest;
            count = 1;
        }
        double total = 0;
        for (int i = first; i < first + count; i++) {
            double weight = 1.0 / (distanceSquared(i, x, y, z) * UNITS_PER_BLOCK * UNITS_PER_BLOCK + 1.0);
            total += weight;
            for (int c = 0; c < 18; c++) cube[c] += (float) (cubes[i * 18 + c] * weight);
        }
        for (int c = 0; c < 18; c++) cube[c] /= (float) total;
        return true;
    }

    private double distanceSquared(int sample, double x, double y, double z) {
        double dx = positions[sample * 3] - x, dy = positions[sample * 3 + 1] - y, dz = positions[sample * 3 + 2] - z;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * The light a surface facing {@code (nx, ny, nz)} gets from an ambient cube: each axis's side
     * weighted by the normal's square along it, as Source's {@code AmbientLight} does.
     */
    public static void evaluate(float[] cube, double nx, double ny, double nz, float[] rgb) {
        double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length > 0) { nx /= length; ny /= length; nz /= length; }
        int sx = nx < 0 ? 3 : 0, sy = ny < 0 ? 9 : 6, sz = nz < 0 ? 15 : 12;
        double wx = nx * nx, wy = ny * ny, wz = nz * nz;
        for (int c = 0; c < 3; c++) {
            rgb[c] = (float) (wx * cube[sx + c] + wy * cube[sy + c] + wz * cube[sz + c]);
        }
    }

    /** A .vhv colour byte to linear light: gamma space, half range, so {@code (c * 2) ^ 2.2}. */
    public static float vertexLight(byte value) {
        return (float) Math.pow((value & 255) / 255.0 * 2.0, 2.2);
    }

    @Override public boolean equals(Object other) { return this == other; }
    @Override public int hashCode() { return System.identityHashCode(this); }
}
