package dev.theredja.src2mc.bundle;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Whether the sun reaches the places a model is lit from, which Source's engine decides with a
 * ray (the {@code light_environment} skylight reaches a prop only where the sky is open above it).
 * One byte per ambient sample of the map's light table, in its order, and for each static prop
 * (by index in the placement table) the point it is lit from and the sun's visibility there.
 * 0 is shadowed, 255 fully lit. Positions are map-local blocks.
 *
 * <p>Version 2 adds, for every sample and then every prop, the sorted list of the world lights
 * (indices into the world light table) that reach the point unoccluded, so a lamp behind a wall
 * does not light it. The lists are stored flat: list {@code i} is
 * {@code lightIndices[listOffsets[i] .. listOffsets[i + 1])}, the samples' lists first, then the
 * props'. Version 1 has none ({@link #hasLists()} false).
 */
public record SunVisibilityTable(byte[] samples, float[] propOrigins, byte[] propVisibility,
                                 int lightCount, int[] listOffsets, char[] lightIndices) {
    public static final int VERSION = 2;
    public static final int MAX_ENTRIES = 1 << 24;
    private static final int PROP_RECORD_BYTES = 16;
    private static final byte[] MAGIC = {'S', '2', 'S', 'U', 'N', 0, 0, 0};

    /** A version 1 table: no light visibility lists. */
    public SunVisibilityTable(byte[] samples, float[] propOrigins, byte[] propVisibility) {
        this(samples, propOrigins, propVisibility, 0, null, null);
    }

    public int sampleCount() { return samples.length; }

    /** Whether the table lists the visible lights per sample and prop (version 2). */
    public boolean hasLists() { return listOffsets != null; }

    /** The list of the prop's visible lights, as a list index for {@link #listContains}; -1 outside the table. */
    public int propList(int prop) { return prop >= 0 && prop < propVisibility.length ? samples.length + prop : -1; }

    /** Whether list {@code list} (a sample index, or {@link #propList}) holds world light {@code light}; binary search. */
    public boolean listContains(int list, int light) {
        if (listOffsets == null || list < 0 || list + 1 >= listOffsets.length) return false;
        int lo = listOffsets[list], hi = listOffsets[list + 1] - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int v = lightIndices[mid];
            if (v < light) lo = mid + 1; else if (v > light) hi = mid - 1; else return true;
        }
        return false;
    }

    /** How many lights list {@code list} holds. */
    public int listSize(int list) { return listOffsets[list + 1] - listOffsets[list]; }

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
        if (version != 1 && version != VERSION) throw new IllegalArgumentException("sun visibility: unsupported version " + version);
        int lightCount = 0;
        if (version == 2) {
            if (in.remaining() < 4) throw new IllegalArgumentException("sun visibility: truncated header");
            lightCount = in.getInt();
            if (lightCount < 0 || lightCount > 0xffff + 1) throw new IllegalArgumentException("sun visibility: light count " + lightCount + " is out of range");
        }
        if (in.remaining() < 4) throw new IllegalArgumentException("sun visibility: truncated header");
        int sampleCount = in.getInt();
        if (sampleCount < 0 || sampleCount > MAX_ENTRIES || (long) sampleCount + 4 > in.remaining()) {
            throw new IllegalArgumentException("sun visibility: sample count " + sampleCount + " does not match the file size");
        }
        byte[] samples = new byte[sampleCount];
        in.get(samples);
        int propCount = in.getInt();
        long recordBytes = (long) propCount * PROP_RECORD_BYTES;
        if (propCount < 0 || propCount > MAX_ENTRIES || (version == 1 ? recordBytes != in.remaining() : recordBytes > in.remaining())) {
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
        if (version == 1) return new SunVisibilityTable(samples, origins, visibility);
        int lists = sampleCount + propCount;
        int[] offsets = new int[lists + 1];
        char[] indices = new char[Math.min(in.remaining() / 2, 1 << 16)];
        int used = 0;
        for (int list = 0; list < lists; list++) {
            if (in.remaining() < 2) throw new IllegalArgumentException("sun visibility: truncated light list " + list);
            int n = in.getShort() & 0xffff;
            if (n > lightCount || (long) n * 2 > in.remaining()) throw new IllegalArgumentException("sun visibility: light list " + list + " has " + n + " entries");
            if (used + n > indices.length) indices = java.util.Arrays.copyOf(indices, Math.max(used + n, (int) Math.min(Integer.MAX_VALUE - 8, indices.length * 2L)));
            int previous = -1;
            for (int k = 0; k < n; k++) {
                int light = in.getShort() & 0xffff;
                if (light >= lightCount) throw new IllegalArgumentException("sun visibility: light index " + light + " in list " + list + " is not below the light count " + lightCount);
                if (light <= previous) throw new IllegalArgumentException("sun visibility: light list " + list + " is not ascending and unique");
                previous = light;
                indices[used++] = (char) light;
            }
            offsets[list + 1] = used;
        }
        if (in.hasRemaining()) throw new IllegalArgumentException("sun visibility: " + in.remaining() + " trailing byte(s)");
        return new SunVisibilityTable(samples, origins, visibility, lightCount, offsets, java.util.Arrays.copyOf(indices, used));
    }
}
