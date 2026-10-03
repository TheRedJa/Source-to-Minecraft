package dev.theredja.src2mc.world;

import dev.theredja.src2mc.bundle.BundleMap;
import dev.theredja.src2mc.bundle.LogicPropTable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.resources.ResourceLocation;

/**
 * What the logic has made of each logic prop (format.md section 18), on each side: shown or
 * hidden, solid or not, the skin it wears and its tint. The server takes them from the map's
 * entities every tick, the client from the server's sync; a prop with nothing stored is as the
 * map spawns it. Keyed by dimension and placement anchor, then by the prop's lump entity.
 *
 * <p>Each side's {@link #version} moves on every change, so the renderer and the collision
 * lookup can tell a cache went stale without comparing anything.
 */
public final class PropStates {
    /** Same bits as a mover's: not drawn, does not collide. */
    public static final int HIDDEN = MoverRegistry.HIDDEN, NOT_SOLID = MoverRegistry.NOT_SOLID;

    /** One prop's state; {@code color} is {@code 0xRRGGBB}. */
    public record State(int flags, int skin, int color) {
        public boolean hidden() { return (flags & HIDDEN) != 0; }
        public boolean solid() { return (flags & NOT_SOLID) == 0; }
    }

    public record Key(ResourceLocation dimension, long anchor) {}

    private static final Map<Key, Map<Integer, State>> SERVER = new ConcurrentHashMap<>();
    private static final Map<Key, Map<Integer, State>> CLIENT = new ConcurrentHashMap<>();
    private static final AtomicLong SERVER_VERSION = new AtomicLong(), CLIENT_VERSION = new AtomicLong();

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
        (client ? CLIENT_VERSION : SERVER_VERSION).incrementAndGet();
    }

    /** A prop as the map spawns it: hidden when it starts disabled, in its own skin and tint. */
    public static State initial(BundleMap map, LogicPropTable.Prop prop) {
        return new State(prop.startHidden() ? HIDDEN : 0, prop.skin(), map.models().get(prop.model(prop.skin())).color());
    }

    /** The prop's state now: what the logic stored, else as the map spawns it. */
    public static State effective(boolean client, Key key, BundleMap map, LogicPropTable.Prop prop) {
        State state = stored(client, key, prop.entity());
        return state != null ? state : initial(map, prop);
    }

    public static Key key(net.minecraft.world.level.Level level, MapPlacement placement) {
        return new Key(level.dimension().location(), placement.anchorWorld().asLong());
    }
}
