package dev.theredja.src2mc.bundle;

import java.util.BitSet;
import java.util.List;

/**
 * A map's 3D skybox (format.md section 21): the room its {@code sky_camera} stands in, as
 * triangles in block units relative to that camera, lit by Source's baked light.
 *
 * @param scale the {@code sky_camera}'s scale: the room shows this many times larger
 * @param camera map-local block coordinates of the {@code sky_camera}
 * @param worldOrigin map-local block coordinates of Source's world origin
 * @param fog the room's fog; null for none
 * @param clusterCount PVS clusters the {@code clusters} bits cover; 0 when the map has no PVS
 * @param clusters per cluster, whether its leaves see the 3D sky ({@code LEAF_FLAGS_SKY})
 * @param page the lightmap page, row by row, three {@code u16} per luxel of linear light times
 *     {@link #LIGHT_SCALE}
 */
public record SkyboxTable(float scale, double[] camera, double[] worldOrigin, Fog fog, int clusterCount, BitSet clusters,
                          int pageWidth, int pageHeight, short[] page, List<Batch> batches) {
    public static final float LIGHT_SCALE = 4096f;
    /** Floats per vertex: position, texture coordinates in repeats, lightmap page coordinates, linear light. */
    public static final int VERTEX_FLOATS = 10;
    public static final int LIGHTMAP = 0, VERTEX_LIGHT = 1;

    /** Linear fog in the room's own units: Source divides the distances by {@code scale}. */
    public record Fog(int red, int green, int blue, float start, float end, float maxDensity) {}

    /** Triangles of one material, lighting and tint. */
    public record Batch(int material, int lighting, int red, int green, int blue, float[] vertices) {
        public int vertexCount() { return vertices.length / VERTEX_FLOATS; }
    }

    public SkyboxTable {
        camera = camera.clone();
        worldOrigin = worldOrigin.clone();
        batches = List.copyOf(batches);
    }

    @Override public double[] camera() { return camera.clone(); }
    @Override public double[] worldOrigin() { return worldOrigin.clone(); }

    /** Whether Source draws the room for an eye in {@code cluster}; -1 or no PVS draws it. */
    public boolean visibleFrom(int cluster) {
        return cluster < 0 || clusterCount == 0 || cluster >= clusterCount || clusters.get(cluster);
    }
}
