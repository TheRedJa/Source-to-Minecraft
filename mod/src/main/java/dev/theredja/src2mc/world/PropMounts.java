package dev.theredja.src2mc.world;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The logic props that no longer stand where the bundle has them, on each side: copies a
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
    /** The server's, as last sent: its sub-levels collide with what the clients draw. */
    private static final Map<PropStates.Key, Map<Integer, Mount>> SERVER = new ConcurrentHashMap<>();
    private static final AtomicLong CLIENT_VERSION = new AtomicLong(), SERVER_VERSION = new AtomicLong();

    private PropMounts() {}

    public static long version() { return version(true); }

    public static long version(boolean client) { return (client ? CLIENT_VERSION : SERVER_VERSION).get(); }

    /** Replaces every client mount of one placement. */
    public static void replace(PropStates.Key key, Map<Integer, Mount> mounts) { replace(true, key, mounts); }

    /** Replaces every mount of one placement. */
    public static void replace(boolean client, PropStates.Key key, Map<Integer, Mount> mounts) {
        Map<PropStates.Key, Map<Integer, Mount>> side = client ? CLIENT : SERVER;
        if (mounts.isEmpty()) side.remove(key);
        else side.put(key, Map.copyOf(mounts));
        (client ? CLIENT_VERSION : SERVER_VERSION).incrementAndGet();
    }

    /** One placement's client mounts; empty for none. */
    public static Map<Integer, Mount> of(PropStates.Key key) { return of(true, key); }

    public static Map<Integer, Mount> of(boolean client, PropStates.Key key) {
        Map<Integer, Mount> mounts = (client ? CLIENT : SERVER).get(key);
        return mounts == null ? Map.of() : mounts;
    }

    /** Whether entity {@code entity} of a placement is drawn by its mount rather than where the bundle has it. */
    public static boolean mounted(PropStates.Key key, int entity) { return mounted(true, key, entity); }

    public static boolean mounted(boolean client, PropStates.Key key, int entity) {
        Map<Integer, Mount> mounts = (client ? CLIENT : SERVER).get(key);
        return mounts != null && mounts.containsKey(entity);
    }

    public static void clear() { clear(true); }

    public static void clear(boolean client) {
        (client ? CLIENT : SERVER).clear();
        (client ? CLIENT_VERSION : SERVER_VERSION).incrementAndGet();
    }
}
