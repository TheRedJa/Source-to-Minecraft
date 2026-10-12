package dev.theredja.src2mc.bundle;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * The world lights vrad compiled from a map's light entities (format.md section 27): what Source
 * lights models with besides their ambient cube. Positions, directions, radius and attenuation are
 * in map-local blocks; {@code intensity} is linear RGB.
 */
public record WorldLightTable(List<Light> lights) {
    public WorldLightTable { lights = List.copyOf(lights); }

    public static final int TYPE_SURFACE = 0, TYPE_POINT = 1, TYPE_SPOT = 2, TYPE_SKYLIGHT = 3,
        TYPE_QUAKE = 4, TYPE_SKY_AMBIENT = 5;
    public static final int FLAG_IN_AMBIENT_CUBE = 1, FLAG_CAST_ENTITY_SHADOWS = 2;
    public static final int VERSION = 1;
    public static final int MAX_LIGHTS = 1 << 20;
    private static final int RECORD_BYTES = 4 + 16 * 4;
    private static final byte[] MAGIC = {'S', '2', 'W', 'L', 'I', 'T', 0, 0};

    /**
     * @param style 0 is always on; others are Source light styles
     * @param direction a spotlight's axis (light towards target); a skylight's direction of travel
     * @param radius the light is ignored beyond it; always positive
     */
    public record Light(int type, int flags, int style, float[] origin, float[] intensity,
                        float[] direction, float stopdot, float stopdot2, float exponent,
                        float radius, float constant, float linear, float quadratic) {
        /** Irradiance scale at {@code distance} blocks: {@code 1 / (c + l d + q d^2)}, 0 beyond the radius. */
        public float attenuation(float distance) {
            if (distance > radius) return 0f;
            float divisor = constant + distance * (linear + distance * quadratic);
            return divisor > 0f ? 1f / divisor : 0f;
        }

        /** A spotlight's cone factor for the cosine of the angle off its axis. */
        public float spotFactor(float cos) {
            if (!(stopdot > stopdot2)) return cos >= stopdot ? 1f : 0f;
            float t = (cos - stopdot2) / (stopdot - stopdot2);
            t = Math.max(0f, Math.min(1f, t));
            return (float) Math.pow(t, exponent);
        }
    }

    /** Parses a {@code .s2wlit} file; throws {@link IllegalArgumentException} when it is malformed. */
    public static WorldLightTable parse(byte[] bytes) {
        ByteBuffer in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (bytes.length < 16) throw new IllegalArgumentException("world lights: truncated header");
        for (byte magic : MAGIC) {
            if (in.get() != magic) throw new IllegalArgumentException("world lights: bad magic");
        }
        int version = in.getInt();
        if (version != VERSION) throw new IllegalArgumentException("world lights: unsupported version " + version);
        int count = in.getInt();
        if (count < 0 || count > MAX_LIGHTS || (long) count * RECORD_BYTES != in.remaining()) {
            throw new IllegalArgumentException("world lights: count " + count + " does not match the file size");
        }
        List<Light> lights = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int type = in.get() & 0xff;
            int flags = in.get() & 0xff;
            int style = in.getShort() & 0xffff;
            float[] v = new float[16];
            for (int k = 0; k < 16; k++) {
                v[k] = in.getFloat();
                if (!Float.isFinite(v[k])) throw new IllegalArgumentException("world lights: non-finite value in light " + i);
            }
            if (type > TYPE_SKY_AMBIENT) throw new IllegalArgumentException("world lights: unknown type " + type);
            if (v[12] <= 0f) throw new IllegalArgumentException("world lights: non-positive radius in light " + i);
            if (v[13] < 0f || v[14] < 0f || v[15] < 0f) throw new IllegalArgumentException("world lights: negative attenuation in light " + i);
            // A spotlight whose inner cone is not inside its outer one has a hard edge at the inner.
            if (type == TYPE_SPOT && !(v[9] > v[10])) v[10] = Math.min(v[10], v[9]);
            lights.add(new Light(type, flags, style, new float[] {v[0], v[1], v[2]}, new float[] {v[3], v[4], v[5]},
                new float[] {v[6], v[7], v[8]}, v[9], v[10], v[11], v[12], v[13], v[14], v[15]));
        }
        return new WorldLightTable(lights);
    }
}
