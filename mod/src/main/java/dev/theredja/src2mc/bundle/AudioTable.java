package dev.theredja.src2mc.bundle;

import java.util.List;
import java.util.Map;

/**
 * A map's validated sound table (format.md section 15): its sounds, the soundscapes its
 * {@code env_soundscape} entities select, its {@code ambient_generic} sounds, and the soundscripts
 * each surface property and the map's logic play. An {@code entity} is the entity's index in the
 * BSP entity lump, which is its index in the logic table too. Indices are validated in range; an
 * absent optional index is -1. Positions are map-local blocks; sound levels are Source decibels.
 */
public record AudioTable(List<Sound> sounds, List<Soundscape> soundscapes, List<Emitter> emitters,
                         List<Ambient> ambients, List<Script> scripts, Map<String, Surface> surfaces) {
    /** Local positions an {@code env_soundscape} can name. */
    public static final int LOCAL_POSITIONS = 8;

    public AudioTable {
        sounds = List.copyOf(sounds);
        soundscapes = List.copyOf(soundscapes);
        emitters = List.copyOf(emitters);
        ambients = List.copyOf(ambients);
        scripts = List.copyOf(scripts);
        surfaces = Map.copyOf(surfaces);
    }

    /** {@code loopStart} is the frame a loop restarts at, or -1 for a sound that plays once. */
    public record Sound(String contentId, String source, int channels, int sampleRate, long frames, long loopStart) {
        public boolean loops() { return loopStart >= 0; }
        public String entryPath() { return "audio/" + contentId + ".ogg"; }
    }

    /** Source's interval: a value picked uniformly between the two ends each time it is used. */
    public record Range(double low, double high) {
        public double sample(java.util.random.RandomGenerator random) {
            return low == high ? low : low + random.nextDouble() * (high - low);
        }
    }

    public record Soundscape(String name, List<Loop> loops, List<Random> randoms, List<Child> children) {
        public Soundscape { loops = List.copyOf(loops); randoms = List.copyOf(randoms); children = List.copyOf(children); }
    }

    /** {@code playlooping}; {@code position} -1 is heard everywhere. */
    public record Loop(int sound, Range volume, Range pitch, Range soundLevel, int position) {}

    /** {@code playrandom}; {@code position} -1 with {@code randomPosition} false is heard everywhere. */
    public record Random(int[] sounds, Range time, Range volume, Range pitch, Range soundLevel, int position,
                         boolean randomPosition) {
        public Random { sounds = sounds.clone(); }
        @Override public int[] sounds() { return sounds.clone(); }
        public int sound(int index) { return sounds[index]; }
        public int soundCount() { return sounds.length; }
    }

    /** {@code playsoundscape}; the overrides are -1 when absent. */
    public record Child(int soundscape, Range volume, int position, int positionOverride, int ambientPositionOverride) {}

    /** One {@code env_soundscape}. {@code radius} is in blocks, -1 for no limit; positions hold null where unnamed. */
    public record Emitter(int entity, double x, double y, double z, double radius, int soundscape, double[][] positions,
                          boolean startDisabled) {
        public Emitter {
            double[][] copy = new double[positions.length][];
            for (int i = 0; i < positions.length; i++) copy[i] = positions[i] == null ? null : positions[i].clone();
            positions = copy;
        }
        public double[] position(int index) {
            return index < 0 || index >= positions.length || positions[index] == null ? null : positions[index].clone();
        }
    }

    /**
     * One {@code ambient_generic}. A sound level of 0 is heard everywhere. {@code flags} are its spawnflags:
     * Source starts it with the map unless it starts silent or is not looping.
     */
    public record Ambient(int entity, double x, double y, double z, int[] sounds, Range volume, Range pitch, Range soundLevel, int flags) {
        public static final int EVERYWHERE = 1, START_SILENT = 16, NOT_LOOPING = 32;
        public Ambient { sounds = sounds.clone(); }
        public boolean startsWithMap() { return (flags & (START_SILENT | NOT_LOOPING)) == 0; }
        public boolean looping() { return (flags & NOT_LOOPING) == 0; }
        @Override public int[] sounds() { return sounds.clone(); }
        public int sound(int index) { return sounds[index]; }
        public int soundCount() { return sounds.length; }
    }

    public record Script(String name, int[] sounds, Range volume, Range pitch, Range soundLevel) {
        public Script { sounds = sounds.clone(); }
        @Override public int[] sounds() { return sounds.clone(); }
        public int sound(int index) { return sounds[index]; }
        public int soundCount() { return sounds.length; }
    }

    /** Scripts a surface property plays, by index into {@link #scripts()}, -1 where it plays none. */
    public record Surface(String name, int stepLeft, int stepRight, int impactSoft, int impactHard, int breakSound) {}

    /** The script named {@code name}, lowercase, or null. */
    public Script script(String name) {
        if (name == null) return null;
        String key = name.toLowerCase(java.util.Locale.ROOT);
        for (Script script : scripts) if (script.name().equals(key)) return script;
        return null;
    }

    /** The surface for a {@code $surfaceprop}, falling back to {@code default} as Source does. */
    public Surface surface(String surfaceProp) {
        Surface surface = surfaceProp == null ? null : surfaces.get(surfaceProp.toLowerCase(java.util.Locale.ROOT));
        return surface != null ? surface : surfaces.get("default");
    }

    // Records holding arrays compare by identity, like the other large tables.
    @Override public boolean equals(Object other) { return this == other; }
    @Override public int hashCode() { return System.identityHashCode(this); }
}
