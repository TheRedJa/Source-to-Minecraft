package dev.theredja.src2mc.world;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The logic props that no longer stand where the bundle has them, on the client: copies a
 * point_template made, and props given a new parent at runtime (format.md section 18). Each is
 * drawn on its own -- on a mover's sub-level, or in the map -- and its bundle place (placed or
 * baked into its mover) draws nothing. Keyed by dimension and placement anchor, then by the
 * entity's slot; {@link #version} moves on every change.
 */
public final class PropMounts {
    /**
     * Entity {@code entity}, made from lump entity {@code source}'s record, drawn on mover entity
     * {@code mover}'s sub-level (-1: in the map), its compiled points moved by the rotation
     * {@code (qx, qy, qz, qw)} then the translation {@code (x, y, z)}, map-local blocks, relative
     * to that mover's own move.
     */
    public record Mount(int entity, int source, int mover, double qx, double qy, double qz, double qw, double x, double y, double z) {}

    private static final Map<PropStates.Key, Map<Integer, Mount>> CLIENT = new ConcurrentHashMap<>();
    private static final AtomicLong VERSION = new AtomicLong();

    private PropMounts() {}

    public static long version() { return VERSION.get(); }

    /** Replaces every mount of one placement. */
    public static void replace(PropStates.Key key, Map<Integer, Mount> mounts) {
        if (mounts.isEmpty()) CLIENT.remove(key);
        else CLIENT.put(key, Map.copyOf(mounts));
        VERSION.incrementAndGet();
    }

    /** One placement's mounts; empty for none. */
    public static Map<Integer, Mount> of(PropStates.Key key) {
        Map<Integer, Mount> mounts = CLIENT.get(key);
        return mounts == null ? Map.of() : mounts;
    }

    /** Whether entity {@code entity} of a placement is drawn by its mount rather than where the bundle has it. */
    public static boolean mounted(PropStates.Key key, int entity) {
        Map<Integer, Mount> mounts = CLIENT.get(key);
        return mounts != null && mounts.containsKey(entity);
    }

    public static void clear() {
        CLIENT.clear();
        VERSION.incrementAndGet();
    }
}
