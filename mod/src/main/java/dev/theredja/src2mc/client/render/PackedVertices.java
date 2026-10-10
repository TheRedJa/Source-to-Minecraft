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
    /** Position, atlas UV, normal, then the surface attribute: texture UV and blend (see {@link BakedLighting#SURFACE}). */
    private static final int FLOATS = 11;
    private float[] data = new float[FLOATS * 96];
    private int[] light = new int[96];
    /** Each vertex's tint, {@code 0xRRGGBB}; white leaves the texture as it is. */
    private int[] color = new int[96];
    /** Each vertex's baked light, four floats: see {@link BakedLighting}. */
    private float[] baked = new float[4 * 96];
    private static final float[] FULL_BRIGHT = {1, 1, 1, BakedLighting.VERTEX};
    private int vertices;
    /**
     * Each vertex's material row ({@link SurfaceEffects#row}), kept in sky light's place through
     * every relight; null while no vertex has one. {@link #row} sets it for the vertices to come.
     */
    private int[] rows;
    private int row;
    /** From {@link #index}: each vertex's slot among the distinct ones, and each distinct one's
     * first vertex. Null until indexed. */
    private int[] remap, firstOf;
    /** Vertices written by triangle and vertices actually uploaded, render thread only; for status. */
    static long soupVertices, uploadedVertices;
    private float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
    private float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;

    void add(float x, float y, float z, float u, float v, float nx, float ny, float nz, int packedLight) {
        add(x, y, z, u, v, nx, ny, nz, packedLight, 0xFFFFFF);
    }

    void add(float x, float y, float z, float u, float v, float nx, float ny, float nz, int packedLight, int tint) {
        add(x, y, z, u, v, nx, ny, nz, packedLight, tint, FULL_BRIGHT);
    }

    /** As above, with the vertex's baked light: four floats, see {@link BakedLighting}. */
    void add(float x, float y, float z, float u, float v, float nx, float ny, float nz, int packedLight, int tint, float[] light4) {
        add(x, y, z, u, v, nx, ny, nz, packedLight, tint, light4, 0, 0, 0);
    }

    /**
     * As above, with the surface attribute: the base texture's own coordinates ({@code textureU},
     * {@code textureV}, unbounded, one per repeat) and a blended displacement's blend.
     */
    void add(float x, float y, float z, float u, float v, float nx, float ny, float nz, int packedLight, int tint, float[] light4,
             float textureU, float textureV, float blend) {
        if (vertices == light.length) {
            light = Arrays.copyOf(light, vertices * 2);
            color = Arrays.copyOf(color, vertices * 2);
            data = Arrays.copyOf(data, vertices * 2 * FLOATS);
            baked = Arrays.copyOf(baked, vertices * 2 * 4);
            if (rows != null) rows = Arrays.copyOf(rows, vertices * 2);
        }
        if (row != 0 && rows == null) rows = new int[light.length];
        if (rows != null) {
            rows[vertices] = row;
            packedLight = withRow(packedLight, row);
        }
        System.arraycopy(light4, 0, baked, vertices * 4, 4);
        color[vertices] = tint;
        int at = vertices * FLOATS;
        data[at] = x; data[at + 1] = y; data[at + 2] = z; data[at + 3] = u; data[at + 4] = v;
        data[at + 5] = nx; data[at + 6] = ny; data[at + 7] = nz;
        data[at + 8] = textureU; data[at + 9] = textureV; data[at + 10] = blend;
        light[vertices++] = packedLight;
        minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
        maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
    }

    int vertices() { return vertices; }

    /** The material row of the vertices added from now on, {@link SurfaceEffects#row}; 0 for none. */
    void row(int row) { this.row = row; }

    /** An independent copy, lights and all; indexing is not copied. */
    PackedVertices copy() {
        PackedVertices copy = new PackedVertices();
        copy.data = Arrays.copyOf(data, vertices * FLOATS);
        copy.light = Arrays.copyOf(light, vertices);
        copy.color = Arrays.copyOf(color, vertices);
        copy.baked = Arrays.copyOf(baked, vertices * 4);
        copy.vertices = vertices;
        copy.minX = minX; copy.minY = minY; copy.minZ = minZ;
        copy.maxX = maxX; copy.maxY = maxY; copy.maxZ = maxZ;
        return copy;
    }

    float x(int vertex) { return data[vertex * FLOATS]; }
    float y(int vertex) { return data[vertex * FLOATS + 1]; }
    float z(int vertex) { return data[vertex * FLOATS + 2]; }
    float nx(int vertex) { return data[vertex * FLOATS + 5]; }
    float ny(int vertex) { return data[vertex * FLOATS + 6]; }
    float nz(int vertex) { return data[vertex * FLOATS + 7]; }

    int light(int vertex) { return light[vertex]; }

    float u(int vertex) { return data[vertex * FLOATS + 3]; }
    float v(int vertex) { return data[vertex * FLOATS + 4]; }

    /** Moves one vertex and turns its normal, for a mesh posed again; uploaded unindexed afterwards. */
    void set(int vertex, float x, float y, float z, float nx, float ny, float nz) {
        int at = vertex * FLOATS;
        data[at] = x; data[at + 1] = y; data[at + 2] = z;
        data[at + 5] = nx; data[at + 6] = ny; data[at + 7] = nz;
    }

    /** Replaces one vertex's light; {@link #index} again before uploading indexed. */
    void setLight(int vertex, int packedLight) { light[vertex] = rows == null ? packedLight : withRow(packedLight, rows[vertex]); }

    private static int withRow(int packedLight, int row) { return row == 0 ? packedLight : (packedLight & 0xFFFF) | row << 16; }
    int triangles() { return vertices / 3; }
    boolean isEmpty() { return vertices == 0; }

    /** The box around every vertex, relative to the origin; null when there are none. */
    double[] bounds() {
        return vertices == 0 ? null : new double[] {minX, minY, minZ, maxX, maxY, maxZ};
    }

    /**
     * {@code kept} holds a copy of the bytes that went up when the upload was asked to keep them,
     * null otherwise; see {@link LightPatch}.
     */
    record Uploaded(VertexBuffer buffer, int vertexSize, long bytes, LightPatch kept) {}

    /**
     * The vertex bytes of one upload, kept to light it again without building it again: a
     * worker writes each vertex's new light into them ({@link #patchLight}), the render thread
     * sends them up in place ({@link #send}). The format, the indices and every other byte stay;
     * a vertex's light follows from its position and normal, so the distinct vertices of an
     * indexed upload stay distinct. Native memory; {@link #close} frees it.
     */
    static final class LightPatch implements AutoCloseable {
        private java.nio.ByteBuffer bytes;
        private final int stride, lightOffset;
        /** Each uploaded vertex's own vertex; null when they are the same. */
        private final int[] source;

        private LightPatch(java.nio.ByteBuffer bytes, int stride, int lightOffset, int[] source) {
            this.bytes = bytes; this.stride = stride; this.lightOffset = lightOffset; this.source = source;
        }

        /** Sends the patched bytes into {@code buffer}, which they were uploaded to; render thread only. */
        void send(VertexBuffer buffer) {
            if (bytes == null || buffer.isInvalid()) return;
            com.mojang.blaze3d.platform.GlStateManager._glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, buffer.vertexBufferId);
            org.lwjgl.opengl.GL15.glBufferSubData(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, 0, bytes);
            com.mojang.blaze3d.platform.GlStateManager._glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, 0);
        }

        long bytes() { return bytes == null ? 0 : bytes.remaining(); }

        @Override public void close() {
            if (bytes != null) MemoryUtil.memFree(bytes);
            bytes = null;
        }
    }

    /** Writes every vertex's light, as it is now, into the bytes of its upload; any thread. */
    void patchLight(LightPatch patch) {
        if (patch.bytes == null) return;
        long base = MemoryUtil.memAddress(patch.bytes);
        int count = patch.bytes.remaining() / patch.stride;
        for (int i = 0; i < count; i++) {
            int packed = light[patch.source == null ? i : patch.source[i]];
            long at = base + (long) i * patch.stride + patch.lightOffset;
            // BufferBuilder.setLight: block light, then sky light, a short each.
            MemoryUtil.memPutShort(at, (short) (packed & 0xFFFF));
            MemoryUtil.memPutShort(at + 2, (short) (packed >> 16 & 0xFFFF));
        }
    }

    private LightPatch keep(VertexFormat format, java.nio.ByteBuffer uploaded, int[] source) {
        if (!format.contains(com.mojang.blaze3d.vertex.VertexFormatElement.UV2)) return null;
        java.nio.ByteBuffer copy = MemoryUtil.memAlloc(uploaded.remaining());
        MemoryUtil.memCopy(MemoryUtil.memAddress(uploaded), MemoryUtil.memAddress(copy), uploaded.remaining());
        return new LightPatch(copy, format.getVertexSize(), format.getOffset(com.mojang.blaze3d.vertex.VertexFormatElement.UV2),
            source == null ? null : source.clone());
    }

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
        int at = vertex * FLOATS, result = light[vertex] * 31 + color[vertex];
        for (int i = 0; i < FLOATS; i++) result = result * 31 + Float.floatToIntBits(data[at + i]);
        for (int i = 0; i < 4; i++) result = result * 31 + Float.floatToIntBits(baked[vertex * 4 + i]);
        return result ^ (result >>> 16);
    }

    private boolean same(int left, int right) {
        if (light[left] != light[right] || color[left] != color[right]) return false;
        int a = left * FLOATS, b = right * FLOATS;
        for (int i = 0; i < FLOATS; i++) if (Float.floatToIntBits(data[a + i]) != Float.floatToIntBits(data[b + i])) return false;
        for (int i = 0; i < 4; i++) if (Float.floatToIntBits(baked[left * 4 + i]) != Float.floatToIntBits(baked[right * 4 + i])) return false;
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
    Uploaded upload(boolean neutralIds, boolean indexed) { return upload(neutralIds, indexed, false); }

    /** As {@link #upload(boolean, boolean)}; with {@code keep}, its bytes are kept to be lit again. */
    Uploaded upload(boolean neutralIds, boolean indexed, boolean keep) {
        VertexFormat format = BakedLighting.FORMAT;
        int stride = format.getVertexSize();
        int written = indexed && firstOf != null ? firstOf.length : vertices;
        soupVertices += vertices;
        try (var bytes = new ByteBufferBuilder(Math.max(stride, written * stride))) {
            long target = bytes.reserve(written * stride);
            int uvOffset = format.getOffset(com.mojang.blaze3d.vertex.VertexFormatElement.UV0);
            int lightOffset = format.getOffset(com.mojang.blaze3d.vertex.VertexFormatElement.UV2);
            int colorOffset = format.getOffset(com.mojang.blaze3d.vertex.VertexFormatElement.COLOR);
            int normalOffset = format.getOffset(com.mojang.blaze3d.vertex.VertexFormatElement.NORMAL);
            int bakedOffset = format.getOffset(BakedLighting.BAKED);
            int surfaceOffset = format.getOffset(BakedLighting.SURFACE);
            for (int out = 0; out < written; out++) {
                int i = written == vertices ? out : firstOf[out];
                long at = target + (long) out * stride;
                int d = i * FLOATS;
                MemoryUtil.memPutFloat(at, data[d]);
                MemoryUtil.memPutFloat(at + 4, data[d + 1]);
                MemoryUtil.memPutFloat(at + 8, data[d + 2]);
                MemoryUtil.memPutByte(at + colorOffset, (byte) (color[i] >> 16));
                MemoryUtil.memPutByte(at + colorOffset + 1, (byte) (color[i] >> 8));
                MemoryUtil.memPutByte(at + colorOffset + 2, (byte) color[i]);
                MemoryUtil.memPutByte(at + colorOffset + 3, (byte) 255);
                MemoryUtil.memPutFloat(at + uvOffset, data[d + 3]);
                MemoryUtil.memPutFloat(at + uvOffset + 4, data[d + 4]);
                // BufferBuilder.setLight: block light, then sky light, a short each.
                MemoryUtil.memPutShort(at + lightOffset, (short) (light[i] & 0xFFFF));
                MemoryUtil.memPutShort(at + lightOffset + 2, (short) (light[i] >> 16 & 0xFFFF));
                MemoryUtil.memPutByte(at + normalOffset, normalByte(data[d + 5]));
                MemoryUtil.memPutByte(at + normalOffset + 1, normalByte(data[d + 6]));
                MemoryUtil.memPutByte(at + normalOffset + 2, normalByte(data[d + 7]));
                for (int k = 0; k < 4; k++) MemoryUtil.memPutFloat(at + bakedOffset + 4L * k, baked[i * 4 + k]);
                for (int k = 0; k < 3; k++) MemoryUtil.memPutFloat(at + surfaceOffset + 4L * k, data[d + 8 + k]);
            }
            var buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
            LightPatch kept = keep ? keep(format, MemoryUtil.memByteBuffer(target, written * stride),
                written == vertices ? null : firstOf) : null;
            if (written == vertices) {
                VertexFormat.IndexType sequential = VertexFormat.IndexType.least(vertices);
                buffer.bind();
                buffer.upload(new MeshData(bytes.build(), new MeshData.DrawState(format, vertices, vertices, VertexFormat.Mode.TRIANGLES, sequential)));
                VertexBuffer.unbind();
                uploadedVertices += vertices;
                return new Uploaded(buffer, stride, (long) vertices * stride, kept);
            }
            VertexFormat.IndexType indexType = VertexFormat.IndexType.least(written);
            try (var indices = new ByteBufferBuilder(vertices * indexType.bytes)) {
                long index = indices.reserve(vertices * indexType.bytes);
                for (int i = 0; i < vertices; i++) {
                    if (indexType == VertexFormat.IndexType.SHORT) MemoryUtil.memPutShort(index + 2L * i, (short) remap[i]);
                    else MemoryUtil.memPutInt(index + 4L * i, remap[i]);
                }
                // upload() binds a sequential index buffer for vertexCount indices; the real one
                // replaces it while this buffer's vertex array is still bound.
                buffer.bind();
                buffer.upload(new MeshData(bytes.build(), new MeshData.DrawState(format, written, vertices, VertexFormat.Mode.TRIANGLES, indexType)));
                buffer.uploadIndexBuffer(indices.build());
                VertexBuffer.unbind();
            }
            uploadedVertices += written;
            return new Uploaded(buffer, stride, (long) written * stride + (long) vertices * indexType.bytes, kept);
        }
    }

    private static byte normalByte(float value) {
        return (byte) ((int) (net.minecraft.util.Mth.clamp(value, -1.0f, 1.0f) * 127.0f) & 255);
    }
}
