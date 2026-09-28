package dev.theredja.src2mc.client.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.Arrays;
import net.minecraft.client.renderer.texture.OverlayTexture;

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

    record Uploaded(VertexBuffer buffer, int vertexSize) {}

    /**
     * Fills a buffer and uploads it, render thread only. With {@code neutralIds}, Iris's captured
     * entity ids are zeroed for the whole fill; see {@link IrisCompat#setCapturedIds}.
     */
    Uploaded upload(boolean neutralIds) {
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
                    var buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                    buffer.bind(); buffer.upload(mesh); VertexBuffer.unbind();
                    return new Uploaded(buffer, mesh.drawState().format().getVertexSize());
                }
            } finally {
                IrisCompat.restoreCapturedIds(previousIds);
            }
        }
    }
}
