package dev.theredja.src2mc.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.theredja.src2mc.bundle.AtlasIndex;
import dev.theredja.src2mc.bundle.BundleMap;
import dev.theredja.src2mc.bundle.BundleMaterial;
import dev.theredja.src2mc.bundle.SkyboxTable;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.Util;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

/**
 * One map's 3D skybox on the GPU (format.md section 21): its lightmap page as a 16-bit texture,
 * and its triangles cut to the atlas as surfaces are -- per texture repeat and atlas region, so
 * every piece samples one page rectangle -- in raw vertex buffers of ten floats a vertex:
 * position, atlas coordinates, lightmap coordinates, linear light. Built on a worker, uploaded on
 * the render thread.
 */
final class SkyboxScene implements AutoCloseable {
    static final int FLOATS = 10;

    /** One draw: a page, a render class, a side mode, a tint and a lighting. */
    record Key(int page, BundleMaterial.RenderClass renderClass, boolean doubleSided, int tint, int lighting) {}

    record Draw(Key key, int vao, int vbo, int vertices) {}

    final SkyboxTable table;
    final List<Draw> draws;
    final int lightmap;
    final long triangles;

    private SkyboxScene(SkyboxTable table, List<Draw> draws, int lightmap, long triangles) {
        this.table = table;
        this.draws = draws;
        this.lightmap = lightmap;
        this.triangles = triangles;
    }

    /** The cut geometry, before upload. */
    record Prepared(SkyboxTable table, Map<Key, float[]> pieces, long triangles) {}

    static CompletableFuture<Prepared> prepare(BundleMap map) {
        return CompletableFuture.supplyAsync(() -> cut(map), Util.backgroundExecutor());
    }

    private static Prepared cut(BundleMap map) {
        SkyboxTable table = map.skybox();
        AtlasIndex atlas = map.atlas();
        Map<Key, FloatList> pieces = new LinkedHashMap<>();
        long triangles = 0;
        for (SkyboxTable.Batch batch : table.batches()) {
            BundleMaterial material = map.materials().get(batch.material());
            if (material.texture() == null || atlas == null) continue;
            AtlasIndex.Texture texture = atlas.textures().get(material.texture().contentId());
            if (texture == null) continue;
            int tint = batch.red() << 16 | batch.green() << 8 | batch.blue();
            float[] v = batch.vertices();
            for (int t = 0; t < batch.vertexCount(); t += 3) {
                List<double[]> triangle = new ArrayList<>(3);
                for (int corner = 0; corner < 3; corner++) {
                    int at = (t + corner) * SkyboxTable.VERTEX_FLOATS;
                    double[] vertex = new double[SkyboxTable.VERTEX_FLOATS];
                    for (int i = 0; i < vertex.length; i++) vertex[i] = v[at + i];
                    vertex[3] *= material.texture().outputWidth();
                    vertex[4] *= material.texture().outputHeight();
                    triangle.add(vertex);
                }
                triangles += cutTriangle(triangle, texture, atlas.pageSize(),
                    page -> pieces.computeIfAbsent(new Key(page, material.renderClass(), material.doubleSided(), tint, batch.lighting()),
                        ignored -> new FloatList()));
            }
        }
        Map<Key, float[]> arrays = new LinkedHashMap<>();
        pieces.forEach((key, list) -> arrays.put(key, list.toArray()));
        return new Prepared(table, arrays, triangles);
    }

    private static final int MAX_PIECES_PER_TRIANGLE = 4096;
    private static final double EPSILON = 1e-7;

    /** Cuts one triangle per texture repeat and atlas region; returns the triangles written. */
    private static long cutTriangle(List<double[]> triangle, AtlasIndex.Texture texture, int pageSize,
                                    java.util.function.IntFunction<FloatList> out) {
        double minU = Double.POSITIVE_INFINITY, maxU = Double.NEGATIVE_INFINITY, minV = minU, maxV = maxU;
        for (double[] vertex : triangle) {
            if (!Double.isFinite(vertex[3]) || !Double.isFinite(vertex[4])) return 0;
            minU = Math.min(minU, vertex[3]); maxU = Math.max(maxU, vertex[3]);
            minV = Math.min(minV, vertex[4]); maxV = Math.max(maxV, vertex[4]);
        }
        long u0 = (long) Math.floor(minU / texture.width()), u1 = (long) Math.floor((maxU - EPSILON) / texture.width());
        long v0 = (long) Math.floor(minV / texture.height()), v1 = (long) Math.floor((maxV - EPSILON) / texture.height());
        if ((u1 - u0 + 1) * (v1 - v0 + 1) * texture.regions().size() > MAX_PIECES_PER_TRIANGLE) return 0;
        long written = 0;
        for (long rv = v0; rv <= v1; rv++) {
            for (long ru = u0; ru <= u1; ru++) {
                for (AtlasIndex.Region region : texture.regions()) {
                    int[] source = region.source(), allocation = region.allocation();
                    double left = (double) ru * texture.width() + source[0];
                    double top = (double) rv * texture.height() + source[1];
                    List<double[]> piece = clip(triangle, 3, left, true);
                    piece = clip(piece, 3, left + source[2], false);
                    piece = clip(piece, 4, top, true);
                    piece = clip(piece, 4, top + source[3], false);
                    if (piece.size() < 3) continue;
                    FloatList list = out.apply(region.page());
                    for (int i = 1; i + 1 < piece.size(); i++) {
                        for (double[] vertex : List.of(piece.getFirst(), piece.get(i), piece.get(i + 1))) {
                            list.add((float) vertex[0], (float) vertex[1], (float) vertex[2],
                                (float) ((allocation[0] + vertex[3] - left) / pageSize), (float) ((allocation[1] + vertex[4] - top) / pageSize),
                                (float) vertex[5], (float) vertex[6], (float) vertex[7], (float) vertex[8], (float) vertex[9]);
                        }
                        written++;
                    }
                }
            }
        }
        return written;
    }

    private static List<double[]> clip(List<double[]> input, int axis, double boundary, boolean greater) {
        if (input.isEmpty()) return input;
        List<double[]> out = new ArrayList<>(input.size() + 2);
        double[] previous = input.getLast();
        boolean previousInside = greater ? previous[axis] >= boundary - EPSILON : previous[axis] <= boundary + EPSILON;
        for (double[] current : input) {
            boolean inside = greater ? current[axis] >= boundary - EPSILON : current[axis] <= boundary + EPSILON;
            if (inside != previousInside) {
                double t = (boundary - previous[axis]) / (current[axis] - previous[axis]);
                double[] mixed = new double[current.length];
                for (int i = 0; i < mixed.length; i++) mixed[i] = previous[i] + (current[i] - previous[i]) * t;
                out.add(mixed);
            }
            if (inside) out.add(current);
            previous = current;
            previousInside = inside;
        }
        return out;
    }

    /** Uploads a prepared scene; render thread only. */
    static SkyboxScene upload(Prepared prepared) {
        RenderSystem.assertOnRenderThread();
        SkyboxTable table = prepared.table();
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int lightmap = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, lightmap);
        ShortBuffer luxels = MemoryUtil.memAllocShort(table.page().length);
        try {
            luxels.put(table.page()).flip();
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 2);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGB16, table.pageWidth(), table.pageHeight(), 0, GL11.GL_RGB,
                GL11.GL_UNSIGNED_SHORT, luxels);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        } finally {
            MemoryUtil.memFree(luxels);
        }
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);

        int previousVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int previousBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        List<Draw> draws = new ArrayList<>(prepared.pieces().size());
        for (Map.Entry<Key, float[]> piece : prepared.pieces().entrySet()) {
            float[] values = piece.getValue();
            if (values.length == 0) continue;
            int vao = GL30.glGenVertexArrays(), vbo = GL15.glGenBuffers();
            GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            FloatBuffer buffer = MemoryUtil.memAllocFloat(values.length);
            try {
                buffer.put(values).flip();
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buffer, GL15.GL_STATIC_DRAW);
            } finally {
                MemoryUtil.memFree(buffer);
            }
            int stride = FLOATS * 4;
            // Locations as the skybox shader's vertex format binds them: Position, UV0, UV1, Normal.
            GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, stride, 0);
            GL20.glEnableVertexAttribArray(1);
            GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, stride, 12);
            GL20.glEnableVertexAttribArray(2);
            GL20.glVertexAttribPointer(2, 2, GL11.GL_FLOAT, false, stride, 20);
            GL20.glEnableVertexAttribArray(3);
            GL20.glVertexAttribPointer(3, 3, GL11.GL_FLOAT, false, stride, 28);
            draws.add(new Draw(piece.getKey(), vao, vbo, values.length / FLOATS));
        }
        GL30.glBindVertexArray(previousVao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousBuffer);
        // Opaque first, translucent last, as they must be drawn.
        draws.sort(java.util.Comparator.comparingInt(draw -> draw.key().renderClass() == BundleMaterial.RenderClass.TRANSLUCENT ? 1 : 0));
        return new SkyboxScene(table, List.copyOf(draws), lightmap, prepared.triangles());
    }

    @Override
    public void close() {
        for (Draw draw : draws) {
            GL30.glDeleteVertexArrays(draw.vao());
            GL15.glDeleteBuffers(draw.vbo());
        }
        GL11.glDeleteTextures(lightmap);
    }

    /** A growing float array. */
    private static final class FloatList {
        private float[] values = new float[1024];
        private int size;

        void add(float... more) {
            if (size + more.length > values.length) values = java.util.Arrays.copyOf(values, Math.max(values.length * 2, size + more.length));
            System.arraycopy(more, 0, values, size, more.length);
            size += more.length;
        }

        float[] toArray() { return java.util.Arrays.copyOf(values, size); }
    }
}
