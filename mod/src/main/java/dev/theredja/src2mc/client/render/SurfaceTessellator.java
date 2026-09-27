package dev.theredja.src2mc.client.render;

import dev.theredja.src2mc.bundle.AtlasIndex;
import dev.theredja.src2mc.bundle.BundleMaterial;
import dev.theredja.src2mc.bundle.SurfaceTable;
import java.util.ArrayList;
import java.util.List;

/** Converts one exact surface fragment into page-contained atlas triangles. */
final class SurfaceTessellator {
    private static final double EPSILON = 1.0e-9;
    private static final int MAX_POLYGONS_PER_FACE = 4096;
    /** Repeat-index clamp; far above any real tiling, far below where long arithmetic overflows. */
    private static final long MAX_REPEAT_INDEX = 1L << 40;

    private SurfaceTessellator() {}

    static List<Triangle> tessellate(int cellX, int cellY, int cellZ, SurfaceTable.Face face,
                                     SurfaceTable.UvRegion transform,
                                     BundleMaterial.TextureReference material,
                                     AtlasIndex.Texture texture, int pageSize) {
        Vertex[] corners = corners(cellX, cellY, cellZ, face);
        double[] uv = transform.values();
        double scaleU = (double) material.outputWidth() / material.originalWidth();
        double scaleV = (double) material.outputHeight() / material.originalHeight();
        for (int i = 0; i < corners.length; i++) {
            Vertex p = corners[i];
            corners[i] = p.withUv(
                (p.x * uv[0] + p.y * uv[1] + p.z * uv[2] + uv[3]) * scaleU,
                (p.x * uv[4] + p.y * uv[5] + p.z * uv[6] + uv[7]) * scaleV
            );
        }
        double minU = min(corners, true), maxU = max(corners, true);
        double minV = min(corners, false), maxV = max(corners, false);
        long repeatUMin = floorDiv(minU, texture.width()), repeatUMax = floorDiv(maxU - EPSILON, texture.width());
        long repeatVMin = floorDiv(minV, texture.height()), repeatVMax = floorDiv(maxV - EPSILON, texture.height());
        long spanU = repeatUMax - repeatUMin + 1, spanV = repeatVMax - repeatVMin + 1;
        // A face whose UV transform tiles absurdly (or is non-finite) would otherwise expand into
        // billions of clip iterations on the render thread. Real map faces stay in the low tens.
        if (spanU > MAX_POLYGONS_PER_FACE || spanV > MAX_POLYGONS_PER_FACE
            || spanU * spanV * texture.regions().size() > MAX_POLYGONS_PER_FACE) return List.of();
        List<Triangle> result = new ArrayList<>();
        for (long repeatV = repeatVMin; repeatV <= repeatVMax; repeatV++) {
            for (long repeatU = repeatUMin; repeatU <= repeatUMax; repeatU++) {
                for (AtlasIndex.Region region : texture.regions()) {
                    int[] source = region.source(), allocation = region.allocation();
                    double left = (double) repeatU * texture.width() + source[0];
                    double top = (double) repeatV * texture.height() + source[1];
                    List<Vertex> polygon = new ArrayList<>(List.of(corners));
                    polygon = clip(polygon, true, left, true);
                    polygon = clip(polygon, true, left + source[2], false);
                    polygon = clip(polygon, false, top, true);
                    polygon = clip(polygon, false, top + source[3], false);
                    if (polygon.size() < 3) continue;
                    List<Vertex> mapped = polygon.stream().map(vertex -> vertex.withUv(
                        (allocation[0] + vertex.u - left) / pageSize,
                        (allocation[1] + vertex.v - top) / pageSize
                    )).toList();
                    for (int i = 1; i + 1 < mapped.size(); i++) {
                        result.add(new Triangle(region.page(), mapped.getFirst(), mapped.get(i), mapped.get(i + 1)));
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    private static List<Vertex> clip(List<Vertex> input, boolean uAxis, double boundary, boolean keepGreater) {
        if (input.isEmpty()) return input;
        List<Vertex> out = new ArrayList<>();
        Vertex previous = input.getLast();
        boolean previousInside = inside(previous, uAxis, boundary, keepGreater);
        for (Vertex current : input) {
            boolean currentInside = inside(current, uAxis, boundary, keepGreater);
            if (currentInside != previousInside) {
                double a = uAxis ? previous.u : previous.v;
                double b = uAxis ? current.u : current.v;
                double t = (boundary - a) / (b - a);
                out.add(previous.interpolate(current, t));
            }
            if (currentInside) out.add(current);
            previous = current;
            previousInside = currentInside;
        }
        return out;
    }

    private static boolean inside(Vertex vertex, boolean uAxis, double boundary, boolean keepGreater) {
        double value = uAxis ? vertex.u : vertex.v;
        return keepGreater ? value >= boundary - EPSILON : value <= boundary + EPSILON;
    }

    /** Absolute map-local vertices: owner cell plus the fragment's 1/4096-block offsets. */
    private static Vertex[] corners(int x, int y, int z, SurfaceTable.Face face) {
        Vertex[] result = new Vertex[face.vertexCount()];
        for (int i = 0; i < result.length; i++) {
            result[i] = new Vertex(x + face.coordinate(i, 0), y + face.coordinate(i, 1), z + face.coordinate(i, 2), 0, 0);
        }
        return result;
    }

    private static double min(Vertex[] vertices, boolean u) { double value = Double.POSITIVE_INFINITY; for (Vertex v : vertices) value = Math.min(value, u ? v.u : v.v); return value; }
    private static double max(Vertex[] vertices, boolean u) { double value = Double.NEGATIVE_INFINITY; for (Vertex v : vertices) value = Math.max(value, u ? v.u : v.v); return value; }
    /** Clamped floor division; see {@link PropTessellator} for why saturation here is unsafe. */
    private static long floorDiv(double value, int divisor) {
        double result = Math.floor(value / divisor);
        if (!Double.isFinite(result)) return result > 0 ? MAX_REPEAT_INDEX : -MAX_REPEAT_INDEX;
        return (long) Math.max(-MAX_REPEAT_INDEX, Math.min(MAX_REPEAT_INDEX, result));
    }

    record Triangle(int page, Vertex a, Vertex b, Vertex c) {}
    record Vertex(double x, double y, double z, double u, double v) {
        Vertex withUv(double nextU, double nextV) { return new Vertex(x, y, z, nextU, nextV); }
        Vertex interpolate(Vertex other, double t) {
            return new Vertex(x + (other.x - x) * t, y + (other.y - y) * t, z + (other.z - z) * t,
                u + (other.u - u) * t, v + (other.v - v) * t);
        }
    }
}
