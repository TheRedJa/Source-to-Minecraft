package dev.theredja.src2mc.bundle;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Whether the sun reaches the places a model is lit from, which Source's engine decides with a
 * ray (the {@code light_environment} skylight reaches a prop only where the sky is open above it).
 * One byte per ambient sample of the map's light table, in its order, and for each static prop
 * (by index in the placement table) the point it is lit from and the sun's visibility there.
 * 0 is shadowed, 255 fully lit. Positions are map-local blocks.
 */
public record SunVisibilityTable(byte[] samples, float[] propOrigins, byte[] propVisibility) {
    public static final int VERSION = 1;
    public static final int MAX_ENTRIES = 1 << 24;
    private static final int PROP_RECORD_BYTES = 16;
    private static final byte[] MAGIC = {'S', '2', 'S', 'U', 'N', 0, 0, 0};

    public int sampleCount() { return samples.length; }

    public int propCount() { return propVisibility.length; }

    /** The sun visibility of ambient sample {@code index}, 0 to 1. */
    public float sample(int index) { return (samples[index] & 0xff) / 255f; }

    /** The sun visibility of static prop {@code index}, 0 to 1; 0 outside the table. */
    public float prop(int index) { return index >= 0 && index < propVisibility.length ? (propVisibility[index] & 0xff) / 255f : 0f; }

    /** The point static prop {@code index} is lit from, map-local; null outside the table. */
    public float[] propOrigin(int index) {
        return index >= 0 && index < propVisibility.length
            ? new float[] {propOrigins[index * 3], propOrigins[index * 3 + 1], propOrigins[index * 3 + 2]} : null;
    }

    /** Parses a {@code .s2sun} file; throws {@link IllegalArgumentException} when it is malformed. */
    public static SunVisibilityTable parse(byte[] bytes) {
        ByteBuffer in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (bytes.length < 16) throw new IllegalArgumentException("sun visibility: truncated header");
        for (byte magic : MAGIC) {
            if (in.get() != magic) throw new IllegalArgumentException("sun visibility: bad magic");
        }
        int version = in.getInt();
        if (version != VERSION) throw new IllegalArgumentException("sun visibility: unsupported version " + version);
        int sampleCount = in.getInt();
        if (sampleCount < 0 || sampleCount > MAX_ENTRIES || (long) sampleCount + 4 > in.remaining()) {
            throw new IllegalArgumentException("sun visibility: sample count " + sampleCount + " does not match the file size");
        }
        byte[] samples = new byte[sampleCount];
        in.get(samples);
        int propCount = in.getInt();
        if (propCount < 0 || propCount > MAX_ENTRIES || (long) propCount * PROP_RECORD_BYTES != in.remaining()) {
            throw new IllegalArgumentException("sun visibility: prop count " + propCount + " does not match the file size");
        }
        float[] origins = new float[propCount * 3];
        byte[] visibility = new byte[propCount];
        for (int i = 0; i < propCount; i++) {
            for (int k = 0; k < 3; k++) {
                origins[i * 3 + k] = in.getFloat();
                if (!Float.isFinite(origins[i * 3 + k])) throw new IllegalArgumentException("sun visibility: non-finite origin of prop " + i);
            }
            visibility[i] = in.get();
            if (in.get() != 0 || in.get() != 0 || in.get() != 0) throw new IllegalArgumentException("sun visibility: padding of prop " + i + " is not zero");
        }
        return new SunVisibilityTable(samples, origins, visibility);
    }
}
