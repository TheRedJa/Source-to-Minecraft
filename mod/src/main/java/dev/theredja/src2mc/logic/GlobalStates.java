package dev.theredja.src2mc.logic;

import dev.theredja.src2mc.bundle.LogicTable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * Source's global states ({@code globalstate.cpp}): named flags with a counter that outlive a
 * map, such as {@code gordon_precriminal}. {@code env_global} sets them; {@code logic_auto} and
 * game code read them.
 *
 * <p>Where they live. A playthrough's table is {@link Table}, one per {@link ServerLevel}, saved
 * in that level's {@link LogicSavedData} ({@code "globals"}), so every map placed in one world
 * shares it and it survives a save. Source also keeps them across a level change, and in the
 * one-world-per-map mode a level change is a different Minecraft world with its own save, so the
 * table is also mirrored into every player's persistent data ({@value #PLAYER_KEY}, inside the {@code PlayerPersisted} compound so a respawn keeps it), which the
 * level change carries over with the rest of the player's data. A map takes the table of the
 * arriving players in when it starts or is entered by a level change ({@link #bind}), and a new
 * game clears it, as {@code CServerGameDLL::GameInit} does with {@code ResetGlobalState}.
 *
 * <p>Names are case-insensitive. Until a map is bound to its level (its first tick) it has a
 * table of its own, which a standalone run, such as a test, keeps using.
 */
final class GlobalStates {
    private GlobalStates() {}

    /** {@code GLOBALESTATE}: {@code GLOBAL_OFF}, {@code GLOBAL_ON}, {@code GLOBAL_DEAD}. */
    static final int OFF = 0, ON = 1, DEAD = 2;
    /** What {@link #state} reports for a name not in the table. */
    static final int ABSENT = -1;
    /** The key in a player's persistent data of the table the level change carries. */
    static final String PLAYER_KEY = "src2mc_globals";

    /** {@code GlobalEntity_GetState}, or {@link #ABSENT}. */
    static int state(MapLogic map, String name) { return map.globals().state(name); }

    /** Whether the state is in the table and {@code GLOBAL_ON}. */
    static boolean isOn(MapLogic map, String name) { return state(map, name) == ON; }

    /** Sets a state, adding it to the table when it is not in it ({@code GlobalEntity_Add} or {@code SetState}). */
    static void set(MapLogic map, String name, int state) { map.globals().set(name, state); }

    static int counter(MapLogic map, String name) { return map.globals().counter(name); }

    /** {@code GlobalEntity_SetCounter}; a name not in the table is added as on first, as {@code env_global} does. */
    static void setCounter(MapLogic map, String name, int counter) { map.globals().setCounter(name, counter); }

    /** {@code GlobalEntity_AddToCounter}: returns the new counter. */
    static int addToCounter(MapLogic map, String name, int delta) { return map.globals().addToCounter(name, delta); }

    /**
     * The table of a level, or of a standalone run: each name's state and counter, saved as one
     * compound. {@link #declare}d entries are what a map's entities set as it spawns, kept to
     * be done again on the table the map is bound to.
     */
    static final class Table {
        private static final class Entry {
            int state, counter;
        }

        private record Declared(String name, int state, int counter) {}

        private final Map<String, Entry> entries = new TreeMap<>();
        private final List<Declared> declared = new ArrayList<>();
        private Runnable listener = () -> {};

        /** Called after every change; a level's table saves and mirrors itself through it. */
        void onChange(Runnable listener) { this.listener = listener; }

        private static String key(String name) { return name == null ? "" : name.toLowerCase(Locale.ROOT); }

        boolean contains(String name) { return entries.containsKey(key(name)); }

        int state(String name) {
            Entry entry = entries.get(key(name));
            return entry == null ? ABSENT : entry.state;
        }

        int counter(String name) {
            Entry entry = entries.get(key(name));
            return entry == null ? 0 : entry.counter;
        }

        void set(String name, int state) {
            entries.computeIfAbsent(key(name), ignored -> new Entry()).state = state;
            listener.run();
        }

        void setCounter(String name, int counter) {
            entries.computeIfAbsent(key(name), ignored -> newOn()).counter = counter;
            listener.run();
        }

        int addToCounter(String name, int delta) {
            Entry entry = entries.computeIfAbsent(key(name), ignored -> newOn());
            entry.counter += delta;
            listener.run();
            return entry.counter;
        }

        private static Entry newOn() {
            Entry entry = new Entry();
            entry.state = ON;
            return entry;
        }

        /**
         * {@code CEnvGlobal::Spawn} with its set flag: adds the name with {@code state} unless it is
         * there, and sets a counter other than 0 either way.
         */
        void declare(String name, int state, int counter) {
            declared.add(new Declared(name, state, counter));
            apply(name, state, counter);
        }

        private void apply(String name, int state, int counter) {
            if (!contains(name)) set(name, state);
            if (counter != 0) setCounter(name, counter);
        }

        /** Does what the entities of a map declared as they spawned on {@code shared}. */
        void replayInto(Table shared) {
            for (Declared item : declared) shared.apply(item.name(), item.state(), item.counter());
        }

        /** {@code ResetGlobalState}. */
        void reset() {
            entries.clear();
            listener.run();
        }

        /** Takes the entries of a saved table over those of this one. */
        void adopt(CompoundTag tag) {
            if (tag == null || tag.isEmpty()) return;
            for (String name : tag.getAllKeys()) {
                CompoundTag saved = tag.getCompound(name);
                Entry entry = entries.computeIfAbsent(name, ignored -> new Entry());
                entry.state = saved.getInt("state");
                entry.counter = saved.getInt("counter");
            }
            listener.run();
        }

        /** Loads a saved table without announcing a change. */
        void load(CompoundTag tag) {
            Runnable keep = listener;
            listener = () -> {};
            entries.clear();
            adopt(tag);
            listener = keep;
        }

        CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            entries.forEach((name, entry) -> {
                CompoundTag saved = new CompoundTag();
                saved.putInt("state", entry.state);
                saved.putInt("counter", entry.counter);
                tag.put(name, saved);
            });
            return tag;
        }

        /** For listings: {@code name=state/counter}. */
        @Override public String toString() {
            StringBuilder out = new StringBuilder();
            entries.forEach((name, entry) -> out.append(out.isEmpty() ? "" : ", ").append(name).append('=')
                .append(entry.state == ON ? "on" : entry.state == OFF ? "off" : "dead").append(entry.counter != 0 ? "/" + entry.counter : ""));
            return out.toString();
        }
    }

    /**
     * Binds a map to its level's table on the map's first tick, once it is known which level it
     * is in. {@code entry} is how the map came to run: {@link MapLogic.LoadType#NEW_GAME} starts
     * the table afresh, {@link MapLogic.LoadType#TRANSITION} takes in the table the arriving
     * players carry, and null (a map restored from a save) keeps the level's own. What the map's
     * entities declared as they spawned is then done on the level's table.
     */
    static void bind(MapLogic map, ServerLevel level, List<ServerPlayer> inside, MapLogic.LoadType entry) {
        Table shared = LogicSavedData.get(level).globals();
        if (entry == MapLogic.LoadType.NEW_GAME) {
            // The table is the level's, shared by every map in it: only a new game in a level
            // with no other map on the table starts it afresh, or starting map B would wipe the
            // globals map A set. Source has one map at a time, so there GameInit always resets.
            boolean taken = false;
            for (MapLogic other : LogicSystem.runningMaps(level)) {
                if (other != map && other.globalsBound() && other.globals() == shared) { taken = true; break; }
            }
            if (resets(entry, taken)) shared.reset();
        } else if (entry == MapLogic.LoadType.TRANSITION) {
            for (ServerPlayer player : inside) {
                CompoundTag carried = carried(player);
                if (carried != null) shared.adopt(carried);
            }
        }
        Table own = map.globals();
        if (own != shared) {
            // A map restored from a save keeps the saved counters: Source does not spawn its
            // entities again on a restore, so their spawn-time declarations must not overwrite them.
            if (replays(entry)) own.replayInto(shared);
            map.bindGlobals(shared);
        }
    }

    /** Whether a map entering as {@code entry} starts the level's table afresh: a new game does, unless another map is already on the table. */
    static boolean resets(MapLogic.LoadType entry, boolean takenByOtherMap) { return entry == MapLogic.LoadType.NEW_GAME && !takenByOtherMap; }

    /** Whether a map's spawn-time declarations are done on the level's table: not for a restore (null), which Source does not spawn again. */
    static boolean replays(MapLogic.LoadType entry) { return entry != null; }

    /**
     * The table a player carries, or null. It is kept in the {@code PlayerPersisted} compound,
     * the only part of a player's persistent data NeoForge copies over a death or respawn
     * ({@code ServerPlayer.restoreFrom}); older saves have it at the top level.
     */
    static CompoundTag carried(ServerPlayer player) {
        CompoundTag data = player.getPersistentData();
        if (data.contains(Player.PERSISTED_NBT_TAG, 10)) {
            CompoundTag persisted = data.getCompound(Player.PERSISTED_NBT_TAG);
            if (persisted.contains(PLAYER_KEY, 10)) return persisted.getCompound(PLAYER_KEY);
        }
        return data.contains(PLAYER_KEY, 10) ? data.getCompound(PLAYER_KEY) : null;
    }

    /** Puts a level's table into its players' persistent data, for a level change to carry. */
    static void mirror(ServerLevel level, Table table) {
        CompoundTag tag = table.toTag();
        for (ServerPlayer player : level.players()) {
            CompoundTag data = player.getPersistentData();
            CompoundTag persisted = data.getCompound(Player.PERSISTED_NBT_TAG);
            persisted.put(PLAYER_KEY, tag.copy());
            data.put(Player.PERSISTED_NBT_TAG, persisted);
            data.remove(PLAYER_KEY);
        }
    }

    /** {@code CEnvGlobal}. */
    static final class EnvGlobal extends LogicEntity {
        /** {@code SF_GLOBAL_SET}: the spawn adds the state with its initial value. */
        private static final int SET = 1;
        private String global = "";

        EnvGlobal(MapLogic map, int index, LogicTable.Entity entity) { super(map, index, entity); }

        @Override void spawn() {
            global = key("globalstate", "");
            // No state to act on: Source removes the entity.
            if (global.isEmpty()) { remove(); return; }
            if (hasSpawnFlags(SET)) map.globals().declare(global, Variant.integer(key("initialstate")), Variant.integer(key("counter")));
        }

        @Override boolean accept(String input, String value, Actor activator, LogicEntity caller) {
            switch (input) {
                case "turnon" -> set(global, ON);
                case "turnoff" -> set(global, OFF);
                case "remove" -> set(global, DEAD);
                case "toggle" -> {
                    // A dead state stays dead; one not in the table reads as off, as GlobalEntity_GetState does.
                    int now = GlobalStates.state(map, global);
                    if (now == ON) set(global, OFF);
                    else if (now == OFF || now == ABSENT) set(global, ON);
                }
                case "setcounter" -> setCounter(global, Variant.integer(value));
                case "addtocounter" -> addToCounter(global, Variant.integer(value));
                case "getcounter" -> {
                    // Not in the table: added as on first, with a counter of 0.
                    if (GlobalStates.state(map, global) == ABSENT) set(global, ON);
                    fire("counter", activator, this, Integer.toString(GlobalStates.counter(map, global)), 0);
                }
                default -> { return false; }
            }
            return true;
        }

        private void set(String name, int state) { GlobalStates.set(map, name, state); }

        private void setCounter(String name, int counter) { GlobalStates.setCounter(map, name, counter); }

        private void addToCounter(String name, int delta) { GlobalStates.addToCounter(map, name, delta); }

        @Override String state() {
            int now = GlobalStates.state(map, global);
            return global + (now == ABSENT ? " (not set)" : now == ON ? " on" : now == OFF ? " off" : " dead") + ", counter " + GlobalStates.counter(map, global);
        }
    }
}
