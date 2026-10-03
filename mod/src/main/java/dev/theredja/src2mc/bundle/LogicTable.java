package dev.theredja.src2mc.bundle;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A map's validated logic table (format.md section 16): every entity of the BSP entity lump with
 * every keyvalue and output as the map wrote them, the shapes of its brush entities, its parsed
 * choreography scenes and the closed captions they and the sound table use. Positions are
 * map-local blocks. Indices are validated in range; an absent optional index is -1.
 */
public record LogicTable(double[] sourceOrigin, List<Entity> entities, List<Volume> volumes, List<Scene> scenes,
                         Map<String, String> captions, Map<String, String> strings, List<Entity> engineEntities,
                         List<EngineEvent> engineEvents) {
    public LogicTable {
        sourceOrigin = sourceOrigin.clone();
        entities = List.copyOf(entities);
        volumes = List.copyOf(volumes);
        scenes = List.copyOf(scenes);
        captions = Map.copyOf(captions);
        strings = Map.copyOf(strings);
        engineEntities = List.copyOf(engineEntities);
        engineEvents = List.copyOf(engineEvents);
    }

    public LogicTable(double[] sourceOrigin, List<Entity> entities, List<Volume> volumes, List<Scene> scenes, Map<String, String> captions) {
        this(sourceOrigin, entities, volumes, scenes, captions, Map.of(), List.of(), List.of());
    }

    /**
     * An input the game's own code queues as the map spawns ({@code EntFire}), such as INFRA's
     * chapter title script; {@code delay} in seconds.
     */
    public record EngineEvent(String target, String input, String parameter, double delay) {}

    /**
     * A HUD text as {@code g_pVGuiLocalize->Find} shows it: a {@code #} token's localized text,
     * anything else, or a token the strings lack, as written.
     */
    public String localize(String text) {
        if (text == null) return "";
        String token = text.startsWith("#") ? text.substring(1) : text;
        String found = strings.get(token.toLowerCase(Locale.ROOT));
        return found != null ? found : text;
    }

    @Override public double[] sourceOrigin() { return sourceOrigin.clone(); }

    /**
     * One entity. {@code keyvalues} holds {@code [key, value]} pairs in lump order, outputs excluded;
     * {@code origin} is null where the entity has none.
     */
    public record Entity(String classname, List<String[]> keyvalues, List<Output> outputs, double[] origin, int volume, int scene) {
        public Entity {
            keyvalues = List.copyOf(keyvalues);
            outputs = List.copyOf(outputs);
            origin = origin == null ? null : origin.clone();
        }

        /** The value of {@code key}, matched as Source matches keys, ignoring case; the last one wins. Null if absent. */
        public String value(String key) {
            String found = null;
            for (String[] pair : keyvalues) if (pair[0].equalsIgnoreCase(key)) found = pair[1];
            return found;
        }

        public String targetname() {
            String name = value("targetname");
            return name == null || name.isEmpty() ? null : name;
        }

        @Override public double[] origin() { return origin == null ? null : origin.clone(); }
    }

    /** One output connection: {@code times} is -1 for unlimited. */
    public record Output(String output, String target, String input, String parameter, double delay, int times) {}

    /** A brush entity's shape: its map-local bounds and convex brushes. */
    public record Volume(double[] bounds, List<double[][]> brushes) {
        public Volume {
            bounds = bounds.clone();
            brushes = List.copyOf(brushes);
        }

        @Override public double[] bounds() { return bounds.clone(); }

        /** Whether the map-local box overlaps any brush: no plane of that brush has the box wholly outside it. */
        public boolean intersects(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            if (maxX < bounds[0] || minX > bounds[3] || maxY < bounds[1] || minY > bounds[4] || maxZ < bounds[2] || minZ > bounds[5]) return false;
            for (double[][] brush : brushes) {
                boolean separated = false;
                for (double[] plane : brush) {
                    // The box corner reaching furthest against the normal.
                    double x = plane[0] >= 0 ? minX : maxX, y = plane[1] >= 0 ? minY : maxY, z = plane[2] >= 0 ? minZ : maxZ;
                    if (plane[0] * x + plane[1] * y + plane[2] * z > plane[3]) { separated = true; break; }
                }
                if (!separated) return true;
            }
            return false;
        }

        /**
         * Where the segment from {@code from} along {@code direction} first enters a brush, as a fraction of
         * {@code direction}'s length within {@code [0, 1]}; -1 when it does not.
         */
        public double clip(double fx, double fy, double fz, double dx, double dy, double dz) {
            double best = -1;
            for (double[][] brush : brushes) {
                double enter = 0, exit = 1;
                boolean miss = false;
                for (double[] plane : brush) {
                    double start = plane[0] * fx + plane[1] * fy + plane[2] * fz - plane[3];
                    double along = plane[0] * dx + plane[1] * dy + plane[2] * dz;
                    if (along == 0) {
                        if (start > 0) { miss = true; break; }
                        continue;
                    }
                    double t = -start / along;
                    if (along < 0) enter = Math.max(enter, t);
                    else exit = Math.min(exit, t);
                    if (enter > exit) { miss = true; break; }
                }
                if (!miss && (best < 0 || enter < best)) best = enter;
            }
            return best;
        }
    }

    /** A {@code logic_choreographed_scene}'s events, in start order. */
    public record Scene(String file, double length, List<SceneEvent> events) {
        public Scene { events = List.copyOf(events); }
    }

    /**
     * A {@code speak} event ({@code script} set, {@code trigger} 0) or a {@code firetrigger} event
     * ({@code trigger} 1..16). {@code end} is -1 where the event has none; {@code caption} is null where
     * the captions have no entry.
     */
    public record SceneEvent(String actor, double start, double end, String script, String caption, int trigger) {
        public boolean speaks() { return trigger == 0; }
    }

    /** The caption text for a token, or null. */
    public String caption(String token) {
        return token == null ? null : captions.get(token.toLowerCase(Locale.ROOT));
    }

    // Large and never rebuilt within a generation: compared by identity, like the other tables.
    @Override public boolean equals(Object other) { return this == other; }
    @Override public int hashCode() { return System.identityHashCode(this); }
}
