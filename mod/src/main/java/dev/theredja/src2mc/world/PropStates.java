package dev.theredja.src2mc.world;

import dev.theredja.src2mc.bundle.BundleMap;
import dev.theredja.src2mc.bundle.LogicPropTable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.resources.ResourceLocation;

/**
 * What the logic has made of each logic prop (format.md section 18), on each side: shown or
 * hidden, solid or not, the skin it wears and its tint, and an animated prop's sequence. The
 * server takes them from the map's entities every tick, the client from the server's sync; a prop
 * with nothing stored is as the map spawns it. Keyed by dimension and placement anchor, then by
 * the prop's lump entity.
 *
 * <p>Each side's {@link #version} moves on every change, so the renderer and the collision
 * lookup can tell a cache went stale without comparing anything.
 */
public final class PropStates {
    /** Same bits as a mover's: not drawn, does not collide. */
    public static final int HIDDEN = MoverRegistry.HIDDEN, NOT_SOLID = MoverRegistry.NOT_SOLID;

    /**
     * One prop's state; {@code color} is {@code 0xRRGGBB}. An animated prop plays
     * {@code sequence} (-1 for a prop without animation) from {@code cycle} at map time
     * {@code time}, advancing {@code rate} cycles per cycle-second (its playback rate, or 0 while
     * the server does not advance it); {@code parity} moves whenever Source would restart the
     * sequence, so a client can blend out of the old one. {@code pose} is the sequence whose
     * settled pose its collision is in, or -1 for the pose it spawned in.
     */
    public record State(int flags, int skin, int color, int sequence, float cycle, float rate, double time, int parity, int pose) {
        public State(int flags, int skin, int color) { this(flags, skin, color, -1, 0, 0, 0, 0, -1); }
        public boolean hidden() { return (flags & HIDDEN) != 0; }
        public boolean solid() { return (flags & NOT_SOLID) == 0; }
    }

    public record Key(ResourceLocation dimension, long anchor) {}

    private static final Map<Key, Map<Integer, State>> SERVER = new ConcurrentHashMap<>();
    private static final Map<Key, Map<Integer, State>> CLIENT = new ConcurrentHashMap<>();
    private static final AtomicLong SERVER_VERSION = new AtomicLong(), CLIENT_VERSION = new AtomicLong();
    /** Per placement, the server's map time minus the client's clock, as last synced. */
    private static final Map<Key, Double> CLOCKS = new ConcurrentHashMap<>();

    private PropStates() {}

    private static Map<Key, Map<Integer, State>> side(boolean client) { return client ? CLIENT : SERVER; }

    public static long version(boolean client) { return (client ? CLIENT_VERSION : SERVER_VERSION).get(); }

    /** The stored state of one prop, or null for one the logic has not touched. */
    public static State stored(boolean client, Key key, int entity) {
        Map<Integer, State> states = side(client).get(key);
        return states == null ? null : states.get(entity);
    }

    /** Every stored state of one placement; empty for none. */
    public static Map<Integer, State> stored(boolean client, Key key) {
        Map<Integer, State> states = side(client).get(key);
        return states == null ? Map.of() : Map.copyOf(states);
    }

    /** Every placement with stored states on one side. */
    public static java.util.Set<Key> keys(boolean client) { return java.util.Set.copyOf(side(client).keySet()); }

    /** Stores a state; true when it differs from the one stored. */
    public static boolean set(boolean client, Key key, int entity, State state) {
        State previous = side(client).computeIfAbsent(key, ignored -> new ConcurrentHashMap<>()).put(entity, state);
        if (state.equals(previous)) return false;
        (client ? CLIENT_VERSION : SERVER_VERSION).incrementAndGet();
        return true;
    }

    /** Replaces every state of one placement, for a full sync. */
    public static void replace(boolean client, Key key, Map<Integer, State> states) {
        if (states.isEmpty()) side(client).remove(key);
        else side(client).put(key, new ConcurrentHashMap<>(states));
        (client ? CLIENT_VERSION : SERVER_VERSION).incrementAndGet();
    }

    public static void clear(boolean client) {
        side(client).clear();
        if (client) CLOCKS.clear();
        (client ? CLIENT_VERSION : SERVER_VERSION).incrementAndGet();
    }

    /** Client: the server's map time of a placement was {@code mapTime} at client clock {@code clientTime}. */
    public static void syncClock(Key key, double mapTime, double clientTime) { CLOCKS.put(key, mapTime - clientTime); }

    /** Client: the placement's map time at client clock {@code clientTime}; the clock itself before any sync. */
    public static double mapTime(Key key, double clientTime) { return clientTime + CLOCKS.getOrDefault(key, 0.0); }

    /**
     * A prop as the map spawns it: hidden when it starts disabled, gone -- neither drawn nor solid
     * -- when a point_template takes it out of the map, in its own skin and tint, still in its
     * first sequence.
     */
    public static State initial(BundleMap map, LogicPropTable.Prop prop) {
        int flags = dev.theredja.src2mc.logic.Templates.removedAtSpawn(map.logic()).get(prop.entity()) ? HIDDEN | NOT_SOLID
            : prop.startHidden() ? HIDDEN : 0;
        return new State(flags, prop.skin(), map.models().get(prop.model(prop.skin())).color(), prop.sequence(), 0, 0, 0, 0, -1);
    }

    /** The prop's state now: what the logic stored, else as the map spawns it. */
    public static State effective(boolean client, Key key, BundleMap map, LogicPropTable.Prop prop) {
        State state = stored(client, key, prop.entity());
        return state != null ? state : initial(map, prop);
    }

    /** The collision table of the pose the prop stands in: a settled pose when it has one, else the spawn pose's. */
    public static dev.theredja.src2mc.bundle.CollisionTable collision(LogicPropTable.Prop prop, State state) {
        if (state.pose() >= 0) {
            var pose = prop.pose(state.pose());
            if (pose != null) return pose;
        }
        return prop.collision();
    }

    public static Key key(net.minecraft.world.level.Level level, MapPlacement placement) {
        return new Key(level.dimension().location(), placement.anchorWorld().asLong());
    }
}
