package dev.theredja.src2mc.client.logic;

import dev.theredja.src2mc.logic.LogicNetwork;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;

/**
 * The client's copy of what the server's map logic says plays: each running map's sound states,
 * and the one-shot sounds and stops waiting to be played. A map with no copy is not running, and
 * its sound entities do what they do without logic.
 */
public final class ClientLogic {
    private ClientLogic() {}

    private record Key(ResourceLocation dimension, long anchor) {}

    /** A one-shot sound for a map, or a stop, waiting for the audio tick. */
    public record Pending(ResourceLocation dimension, long anchor, LogicNetwork.SoundEvent event, long stop) {}

    private static final Map<Key, Map<Integer, LogicNetwork.SoundState>> STATES = new HashMap<>();
    private static final List<Pending> PENDING = new ArrayList<>();
    private static long epoch;

    /** Runs on the client thread. */
    public static void apply(LogicNetwork.SyncPayload payload) {
        Key key = new Key(payload.dimension(), payload.anchor());
        epoch++;
        if (!payload.running()) {
            STATES.remove(key);
            return;
        }
        Map<Integer, LogicNetwork.SoundState> states = payload.full() ? new HashMap<>() : STATES.computeIfAbsent(key, ignored -> new HashMap<>());
        for (LogicNetwork.SoundState state : payload.states()) states.put(state.entity(), state);
        STATES.put(key, states);
        for (LogicNetwork.SoundEvent event : payload.events()) PENDING.add(new Pending(payload.dimension(), payload.anchor(), event, 0));
        for (long id : payload.stops()) PENDING.add(new Pending(payload.dimension(), payload.anchor(), null, id));
    }

    public static boolean running(ResourceLocation dimension, long anchor) { return STATES.containsKey(new Key(dimension, anchor)); }

    /** The server's state of an entity, or null when the map is not running or has none for it. */
    public static LogicNetwork.SoundState state(ResourceLocation dimension, long anchor, int entity) {
        Map<Integer, LogicNetwork.SoundState> states = STATES.get(new Key(dimension, anchor));
        return states == null ? null : states.get(entity);
    }

    public static List<Pending> drain() {
        if (PENDING.isEmpty()) return List.of();
        List<Pending> drained = List.copyOf(PENDING);
        PENDING.clear();
        return drained;
    }

    /** Bumped by every update, so a cache of what is running can tell it is stale. */
    public static long epoch() { return epoch; }

    public static void clear() {
        STATES.clear();
        PENDING.clear();
        epoch++;
    }
}
