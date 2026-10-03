package dev.theredja.src2mc.logic;

import dev.theredja.src2mc.bundle.LogicTable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * One map entity taking part in the map's logic, after Source's {@code CBaseEntity}: its
 * keyvalues, its outputs, and the inputs every entity accepts. Subclasses add a class's own
 * inputs and behaviour; a class without one is still a target for those common inputs, so a
 * chain through it neither breaks nor fires twice.
 *
 * <p>Life cycle as in Source: every entity of the map is created, then {@link #spawn} reads its
 * keyvalues, then {@link #activate} may look the others up. {@link #think} runs once the map's
 * clock reaches {@link #nextThink}.
 */
public class LogicEntity implements Actor {
    static final double NEVER = Double.POSITIVE_INFINITY;

    final MapLogic map;
    /** The entity's index in the BSP entity lump; -1 for a stand-in that is not in the map. */
    final int index;
    final String classname;
    private final Map<String, String> keys = new HashMap<>();
    /** Connections per lowercase output name, in Source's firing order. */
    private final Map<String, List<Connection>> outputs = new HashMap<>();
    private String targetname;
    private boolean renamed, outputsChanged;
    boolean removed;
    double nextThink = NEVER;
    int spawnflags;

    LogicEntity(MapLogic map, int index, LogicTable.Entity entity) {
        this.map = map;
        this.index = index;
        this.classname = entity == null ? "player" : entity.classname();
        if (entity == null) return;
        for (String[] pair : entity.keyvalues()) keys.put(pair[0].toLowerCase(Locale.ROOT), pair[1]);
        targetname = entity.targetname();
        // CBaseEntityOutput::AddEventAction puts each new connection first, so an output fires its
        // connections in the reverse of the order the map lists them.
        for (LogicTable.Output output : entity.outputs()) {
            outputs.computeIfAbsent(output.output().toLowerCase(Locale.ROOT), ignored -> new ArrayList<>())
                .add(0, new Connection(output.target(), output.input(), output.parameter(), output.delay(), output.times()));
        }
        spawnflags = (int) number("spawnflags", 0);
    }

    @Override public String name() { return targetname == null ? "" : targetname; }
    @Override public String classname() { return classname; }

    /** Reads the class's keyvalues; every entity of the map exists, but none has spawned for certain. */
    void spawn() {}

    /** Runs after every entity has spawned, and again when the map is entered anew (a level change back into it). */
    void activate() {}

    void think() {}

    /**
     * The class's own inputs, {@code input} lowercase. False for one the class does not have,
     * which then falls to the inputs every entity has.
     */
    boolean accept(String input, String value, Actor activator, LogicEntity caller) { return false; }

    /** {@code AcceptInput}: an input arriving from the queue. */
    final void input(String input, String value, Actor activator, LogicEntity caller) {
        if (removed) return;
        String name = input.toLowerCase(Locale.ROOT);
        if (accept(name, value, activator, caller) || common(name, value, activator, caller)) return;
        map.unhandled(this, name);
    }

    private boolean common(String input, String value, Actor activator, LogicEntity caller) {
        switch (input) {
            case "kill", "killhierarchy" -> remove();
            case "fireuser1", "fireuser2", "fireuser3", "fireuser4" -> fire("onuser" + input.charAt(8), activator, null);
            case "addoutput" -> addOutput(value);
            case "runscriptcode", "runscriptfile", "callscriptfunction" -> map.scriptCall(this, input);
            default -> { return false; }
        }
        return true;
    }

    /** {@code InputAddOutput}: {@code "Output target:input:param:delay:times"} adds a connection, {@code "key value"} sets a keyvalue. */
    private void addOutput(String value) {
        if (value == null) return;
        String text = value.trim();
        int space = text.indexOf(' ');
        if (space <= 0) return;
        String key = text.substring(0, space).toLowerCase(Locale.ROOT), rest = text.substring(space + 1).trim();
        Connection connection = Connection.parse(rest);
        if (connection != null && rest.indexOf(':') >= 0) {
            outputs.computeIfAbsent(key, ignored -> new ArrayList<>()).add(0, connection);
            outputsChanged = true;
        } else if (key.equals("targetname")) {
            map.rename(this, rest);
        } else {
            keys.put(key, rest);
            keyChanged(key);
        }
    }

    /** A keyvalue changed at runtime through {@code AddOutput}. */
    void keyChanged(String key) {}

    void setName(String name) {
        targetname = name == null || name.isEmpty() ? null : name;
        renamed = true;
    }

    /** {@code FireOutput} with the entity itself as caller and no delay. */
    final void fire(String output, Actor activator, String value) { fire(output, activator, this, value, 0); }

    /**
     * {@code CBaseEntityOutput::FireOutput}: queues every connection of {@code output}. A connection
     * with its own parameter sends that, after only its own delay; one without sends the output's
     * value after its delay plus {@code delay} -- Source's asymmetry, kept.
     */
    final void fire(String output, Actor activator, LogicEntity caller, String value, double delay) {
        List<Connection> connections = outputs.get(output);
        map.outputFired(this, output, connections == null ? 0 : connections.size());
        if (connections == null || connections.isEmpty()) return;
        for (Connection connection : List.copyOf(connections)) {
            if (connection.parameter == null) {
                map.queue(connection.delay + delay, connection.target, null, connection.input, value, activator, caller);
            } else {
                map.queue(connection.delay, connection.target, null, connection.input, connection.parameter, activator, caller);
            }
            map.trace(caller, output, connection, connection.parameter == null ? value : connection.parameter);
            if (connection.timesLeft > 0 && --connection.timesLeft == 0) {
                connections.remove(connection);
                outputsChanged = true;
            } else if (connection.timesLeft > 0) {
                outputsChanged = true;
            }
        }
    }

    /** Whether {@code output} has any connection left: Source checks this to decide, for one, whether {@code OnSpawn} is worth a think. */
    final boolean hasOutput(String output) {
        List<Connection> connections = outputs.get(output);
        return connections != null && !connections.isEmpty();
    }

    /** The longest delay among an output's connections: Source's {@code GetMaxDelay}. */
    final double maxDelay(String output) {
        double max = 0;
        List<Connection> connections = outputs.get(output);
        if (connections != null) for (Connection connection : connections) max = Math.max(max, connection.delay);
        return max;
    }

    /** {@code UTIL_Remove}. */
    void remove() {
        if (removed) return;
        removed = true;
        nextThink = NEVER;
        map.removed(this);
    }

    String key(String key) { return keys.get(key); }

    String key(String key, String fallback) {
        String value = keys.get(key);
        return value == null ? fallback : value;
    }

    double number(String key, double fallback) {
        String value = keys.get(key);
        return value == null || value.isBlank() ? fallback : Variant.number(value);
    }

    boolean flag(String key) { return Variant.bool(keys.get(key)); }

    boolean hasSpawnFlags(int flags) { return (spawnflags & flags) != 0; }

    /** The entity's map-local position, or null; a brush entity without an origin sits at its shape's centre. */
    double[] position() { return map.position(this); }

    /** Saves what can have changed since spawn; {@link #load} restores it onto a freshly spawned entity. */
    void save(CompoundTag tag) {
        if (removed) tag.putBoolean("removed", true);
        if (renamed) tag.putString("targetname", name());
        if (nextThink != NEVER) tag.putDouble("next_think", nextThink);
        tag.putInt("spawnflags", spawnflags);
        if (outputsChanged) {
            CompoundTag saved = new CompoundTag();
            for (Map.Entry<String, List<Connection>> entry : outputs.entrySet()) {
                ListTag list = new ListTag();
                for (Connection connection : entry.getValue()) {
                    CompoundTag item = new CompoundTag();
                    item.putString("target", connection.target);
                    item.putString("input", connection.input);
                    if (connection.parameter != null) item.putString("parameter", connection.parameter);
                    item.putDouble("delay", connection.delay);
                    item.putInt("times", connection.timesLeft);
                    list.add(item);
                }
                saved.put(entry.getKey(), list);
            }
            tag.put("outputs", saved);
        }
    }

    void load(CompoundTag tag) {
        removed = tag.getBoolean("removed");
        if (tag.contains("targetname")) setName(tag.getString("targetname"));
        nextThink = tag.contains("next_think") ? tag.getDouble("next_think") : NEVER;
        if (tag.contains("spawnflags")) spawnflags = tag.getInt("spawnflags");
        if (tag.contains("outputs")) {
            outputs.clear();
            CompoundTag saved = tag.getCompound("outputs");
            for (String output : saved.getAllKeys()) {
                List<Connection> list = new ArrayList<>();
                for (Tag value : saved.getList(output, Tag.TAG_COMPOUND)) {
                    CompoundTag item = (CompoundTag) value;
                    list.add(new Connection(item.getString("target"), item.getString("input"),
                        item.contains("parameter") ? item.getString("parameter") : null, item.getDouble("delay"), item.getInt("times")));
                }
                outputs.put(output, list);
            }
            outputsChanged = true;
        }
    }

    /** For tracing and listings: {@code classname "name" #index}. */
    String describe() {
        return classname + (targetname == null ? "" : " \"" + targetname + "\"") + (index >= 0 ? " #" + index : "");
    }

    /** Persistent state worth showing in a listing; empty for none. */
    String state() { return ""; }
}
