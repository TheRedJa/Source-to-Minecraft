package dev.theredja.src2mc.client.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.Arrays;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.lwjgl.system.MemoryUtil;

/**
 * Finished, lit triangles as plain arrays: what a worker hands back to the render thread. Only
 * {@link #upload} touches a {@code BufferBuilder} or GL, and it must run on the render thread.
 * Positions are relative to the mesh origin the caller chose.
 */
final class PackedVertices {
    private static final int FLOATS = 8;
    private float[] data = new float[FLOATS * 96];
    private int[] light = new int[96];
    private int vertices;
    /** From {@link #index}: each vertex's slot among the distinct ones, and each distinct one's
     * first vertex. Null until indexed. */
    private int[] remap, firstOf;
    /** Vertices written by triangle and vertices actually uploaded, render thread only; for status. */
    static long soupVertices, uploadedVertices;
    private float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
    private float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;

    void add(float x, float y, float z, float u, float v, float nx, float ny, float nz, int packedLight) {
        if (vertices == light.length) {
            light = Arrays.copyOf(light, vertices * 2);
            data = Arrays.copyOf(data, vertices * 2 * FLOATS);
        }
        int at = vertices * FLOATS;
        data[at] = x; data[at + 1] = y; data[at + 2] = z; data[at + 3] = u; data[at + 4] = v;
        data[at + 5] = nx; data[at + 6] = ny; data[at + 7] = nz;
        light[vertices++] = packedLight;
        minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
        maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
    }

    int vertices() { return vertices; }
    int triangles() { return vertices / 3; }
    boolean isEmpty() { return vertices == 0; }

    /** The box around every vertex, relative to the origin; null when there are none. */
    double[] bounds() {
        return vertices == 0 ? null : new double[] {minX, minY, minZ, maxX, maxY, maxZ};
    }

    record Uploaded(VertexBuffer buffer, int vertexSize, long bytes) {}

    /**
     * Finds the distinct vertices, on a worker. Triangles are written three fresh vertices each,
     * so a vertex shared by six triangles is transformed six times -- with a shaderpack, twice a
     * frame counting the shadow pass. Identical position, UV, normal and light make one vertex.
     */
    void index() {
        int capacity = Integer.highestOneBit(Math.max(4, vertices * 2 - 1)) << 1;
        int[] table = new int[capacity];
        Arrays.fill(table, -1);
        remap = new int[vertices];
        int[] first = new int[vertices];
        int distinct = 0;
        for (int i = 0; i < vertices; i++) {
            int hash = hash(i);
            int slot = hash & (capacity - 1);
            while (true) {
                int at = table[slot];
                if (at < 0) { table[slot] = distinct; first[distinct] = i; remap[i] = distinct++; break; }
                if (same(first[at], i)) { remap[i] = at; break; }
                slot = (slot + 1) & (capacity - 1);
            }
        }
        firstOf = Arrays.copyOf(first, distinct);
    }

    /** Distinct vertex count after {@link #index}, or -1 before. */
    int distinctVertices() { return firstOf == null ? -1 : firstOf.length; }

    /** Which distinct vertex vertex {@code i} became, after {@link #index}. */
    int remapped(int i) { return remap[i]; }

    private int hash(int vertex) {
        int at = vertex * FLOATS, result = light[vertex];
        for (int i = 0; i < FLOATS; i++) result = result * 31 + Float.floatToIntBits(data[at + i]);
        return result ^ (result >>> 16);
    }

    private boolean same(int left, int right) {
        if (light[left] != light[right]) return false;
        int a = left * FLOATS, b = right * FLOATS;
        for (int i = 0; i < FLOATS; i++) if (Float.floatToIntBits(data[a + i]) != Float.floatToIntBits(data[b + i])) return false;
        return true;
    }

    /**
     * Fills a buffer and uploads it, render thread only. With {@code neutralIds}, Iris's captured
     * entity ids are zeroed for the whole fill; see {@link IrisCompat#setCapturedIds}.
     */
    Uploaded upload(boolean neutralIds) { return upload(neutralIds, false); }

    /**
     * As {@link #upload(boolean)}; with {@code indexed} and after {@link #index}, only the
     * distinct vertices go up with an index buffer. They are still written by triangle first,
     * because Iris fills its extra attributes per triangle as vertices are added; each distinct
     * vertex then keeps the bytes of its first triangle.
     */
    Uploaded upload(boolean neutralIds, boolean indexed) {
        int capacity = (int) Math.min(Integer.MAX_VALUE, Math.max(4096L, (long) vertices * 36));
        try (var bytes = new ByteBufferBuilder(capacity)) {
            int[] previousIds = neutralIds ? IrisCompat.setCapturedIds(0, 0, 0) : null;
            try {
                var builder = new BufferBuilder(bytes, VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.NEW_ENTITY);
                for (int i = 0; i < vertices; i++) {
                    int at = i * FLOATS;
                    builder.addVertex(data[at], data[at + 1], data[at + 2])
                        .setColor(255, 255, 255, 255).setUv(data[at + 3], data[at + 4]).setOverlay(OverlayTexture.NO_OVERLAY)
                        .setLight(light[i]).setNormal(data[at + 5], data[at + 6], data[at + 7]);
                }
                try (var mesh = builder.buildOrThrow()) {
                    var format = mesh.drawState().format();
                    int stride = format.getVertexSize();
                    soupVertices += vertices;
                    var buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                    if (!indexed || firstOf == null) {
                        buffer.bind(); buffer.upload(mesh); VertexBuffer.unbind();
                        uploadedVertices += vertices;
                        return new Uploaded(buffer, stride, (long) vertices * stride);
                    }
                    int distinct = firstOf.length;
                    VertexFormat.IndexType indexType = VertexFormat.IndexType.least(distinct);
                    try (var compact = new ByteBufferBuilder(distinct * stride); var indices = new ByteBufferBuilder(vertices * indexType.bytes)) {
                        long source = MemoryUtil.memAddress(mesh.vertexBuffer());
                        long target = compact.reserve(distinct * stride);
                        for (int i = 0; i < distinct; i++) MemoryUtil.memCopy(source + (long) firstOf[i] * stride, target + (long) i * stride, stride);
                        long index = indices.reserve(vertices * indexType.bytes);
                        for (int i = 0; i < vertices; i++) {
                            if (indexType == VertexFormat.IndexType.SHORT) MemoryUtil.memPutShort(index + 2L * i, (short) remap[i]);
                            else MemoryUtil.memPutInt(index + 4L * i, remap[i]);
                        }
                        // upload() binds a sequential index buffer for vertexCount indices; the real
                        // one replaces it while this buffer's vertex array is still bound.
                        buffer.bind();
                        buffer.upload(new MeshData(compact.build(), new MeshData.DrawState(format, distinct, vertices, VertexFormat.Mode.TRIANGLES, indexType)));
                        buffer.uploadIndexBuffer(indices.build());
                        VertexBuffer.unbind();
                    }
                    uploadedVertices += distinct;
                    return new Uploaded(buffer, stride, (long) distinct * stride + (long) vertices * indexType.bytes);
                }
            } finally {
                IrisCompat.restoreCapturedIds(previousIds);
            }
        }
    }
}
