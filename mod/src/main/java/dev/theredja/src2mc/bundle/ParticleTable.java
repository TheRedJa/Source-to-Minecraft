package dev.theredja.src2mc.bundle;

import java.util.List;
import java.util.Map;

/**
 * A map's particle systems (format.md section 23): the {@code .pcf} definitions its entities and
 * impacts start, every parameter written out, and the materials they draw with. Positions and
 * sizes are in Source units and axes, as the files write them.
 *
 * <p>Compared and hashed by identity, as {@link BundleMap} is.
 */
public final class ParticleTable {
    /** One function of a system: its name and every parameter. */
    public record Function(String name, Params parameters) {}

    /** Another system started with this one, {@code delay} seconds later. */
    public record Child(int system, float delay) {}

    public record System(String name, int material, Params attributes, List<Function> renderers, List<Function> operators,
                         List<Function> initializers, List<Function> emitters, List<Function> forces,
                         List<Function> constraints, List<Child> children) {
        @Override public boolean equals(Object other) { return this == other; }
        @Override public int hashCode() { return java.lang.System.identityHashCode(this); }
    }

    /** One animation sequence of a sprite sheet: per frame its seconds and rectangles {@code u0 v0 u1 v1}. */
    public record Sequence(boolean clamp, float[] durations, float[][] rects) {
        public int frames() { return durations.length; }
        public float total() { float t = 0; for (float d : durations) t += d; return t; }
    }

    /** A particle material: its row in the map's material table, shader, and parameters. */
    public record Material(String name, int material, String shader, boolean additive, Map<String, Double> parameters,
                           List<Sequence> sheet) {
        public double parameter(String key, double fallback) {
            Double value = parameters.get(key);
            return value == null ? fallback : value;
        }
        @Override public boolean equals(Object other) { return this == other; }
        @Override public int hashCode() { return java.lang.System.identityHashCode(this); }
    }

    /**
     * How the game draws a bullet impact: {@code systems} with the system of the surface's game
     * material ({@code CHAR_TEX_*} letter to system index), {@code code} with the SDK's code effects
     * and their materials (name to material index).
     */
    public record Impacts(String style, Map<Character, Integer> systems, Map<String, Integer> materials) {}

    /**
     * One bullet hole decal a game material may get ({@code scripts/decals_subrect.txt}): its
     * material, its part of the texture ({@code u0, v0, u1, v1}), its size in Source units and its
     * pick weight.
     */
    public record Decal(int material, float u0, float v0, float u1, float v1, float width, float height, float weight) {}

    private final List<System> systems;
    private final List<Material> materials;
    private final Impacts impacts;
    private final Map<Character, List<Decal>> decals;
    private final Map<String, Integer> byName;

    public ParticleTable(List<System> systems, List<Material> materials, Impacts impacts) {
        this(systems, materials, impacts, Map.of());
    }

    public ParticleTable(List<System> systems, List<Material> materials, Impacts impacts, Map<Character, List<Decal>> decals) {
        this.systems = List.copyOf(systems);
        this.materials = List.copyOf(materials);
        this.impacts = impacts;
        this.decals = Map.copyOf(decals);
        java.util.Map<String, Integer> names = new java.util.HashMap<>();
        for (int i = 0; i < systems.size(); i++) names.putIfAbsent(systems.get(i).name().toLowerCase(java.util.Locale.ROOT), i);
        this.byName = java.util.Map.copyOf(names);
    }

    public List<System> systems() { return systems; }
    public List<Material> materials() { return materials; }
    public Impacts impacts() { return impacts; }
    /** The bullet hole decals of each game material letter; empty when the game has none. */
    public Map<Character, List<Decal>> decals() { return decals; }

    /** The system of that name, ignoring case; -1 for none. */
    public int system(String name) {
        Integer index = byName.get(name.toLowerCase(java.util.Locale.ROOT));
        return index == null ? -1 : index;
    }

    @Override public boolean equals(Object other) { return this == other; }
    @Override public int hashCode() { return java.lang.System.identityHashCode(this); }

    /**
     * Parameter values as the file writes them: numbers and number arrays as doubles, booleans as
     * 0 or 1, strings as strings.
     */
    public static final class Params {
        private final Map<String, Object> values;

        public Params(Map<String, Object> values) { this.values = Map.copyOf(values); }

        public boolean has(String key) { return values.containsKey(key); }

        public double number(String key, double fallback) {
            Object value = values.get(key);
            if (value instanceof double[] array && array.length > 0) return array[0];
            return fallback;
        }

        public int integer(String key, int fallback) { return (int) Math.round(number(key, fallback)); }

        public boolean bool(String key, boolean fallback) {
            Object value = values.get(key);
            if (value instanceof double[] array && array.length > 0) return array[0] != 0;
            return fallback;
        }

        /** A vector of {@code size} values; missing ones from {@code fallback}. */
        public double[] vector(String key, double... fallback) {
            Object value = values.get(key);
            double[] out = fallback.clone();
            if (value instanceof double[] array) java.lang.System.arraycopy(array, 0, out, 0, Math.min(array.length, out.length));
            return out;
        }

        public String string(String key, String fallback) {
            Object value = values.get(key);
            return value instanceof String s ? s : fallback;
        }

        public java.util.Set<String> keys() { return values.keySet(); }
    }
}
