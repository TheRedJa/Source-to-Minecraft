package dev.theredja.src2mc.logic;

import dev.theredja.src2mc.bundle.AudioTable;
import dev.theredja.src2mc.bundle.BundleMap;
import dev.theredja.src2mc.bundle.LogicTable;
import dev.theredja.src2mc.world.MapPlacement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The logic of one placed map: its entities, its event queue and its clock, after Source's server
 * game frame. Each tick the players' touches are checked first (Source does that during player
 * movement), then entities whose think is due think, then the event queue fires everything due.
 *
 * <p>The clock runs only while the map has been started, a player is inside it and the world's
 * tick rate is not frozen: Source does not run a level nobody is in, so timers and delayed
 * outputs wait, as they would there, until someone comes back.
 */
public final class MapLogic {
    public static final double TICK_SECONDS = 0.05;
    /** A loop of zero-delay outputs would otherwise hang the server tick. */
    private static final int MAX_EVENTS_PER_TICK = 20_000;
    private static final int MAX_TRACE_LINES_PER_TICK = 40;

    /** How a map comes to run, which {@code logic_auto} tells apart. */
    public enum LoadType { NEW_GAME, TRANSITION }

    final MapPlacement placement;
    final BundleMap map;
    final LogicTable table;
    final AudioTable audio;
    private final LogicEntity[] entities;
    private final Map<String, List<LogicEntity>> byName = new HashMap<>();
    private final Map<String, List<LogicEntity>> byClass = new HashMap<>();
    private final Map<UUID, LogicEntity> playerEntities = new HashMap<>();
    private final EventQueue queue = new EventQueue();
    private double time;
    private LoadType loadType = LoadType.NEW_GAME;
    private ServerLevel level;
    private List<ServerPlayer> inside = List.of();

    // Counters for /src2mc logic status.
    private long inputsFired, outputsFired, scriptCalls;
    private final Map<String, Integer> unhandled = new TreeMap<>();
    private final Set<UUID> tracers = new LinkedHashSet<>();
    private int traceLines;

    /** Entities whose sound state changed this tick, for the clients. */
    private final Set<Integer> soundChanges = new LinkedHashSet<>();
    private final List<LogicNetwork.SoundEvent> soundEvents = new ArrayList<>();
    private final List<Long> stoppedSounds = new ArrayList<>();
    private long soundEventIds;
    private boolean dirty;

    MapLogic(MapPlacement placement, BundleMap map) {
        this.placement = placement;
        this.map = map;
        this.table = map.logic();
        this.audio = map.audio();
        this.entities = new LogicEntity[table.entities().size()];
    }

    /** Spawns every entity fresh, as a map loads in Source, and activates them. */
    void spawn(LoadType type) {
        loadType = type;
        time = 0;
        queue.clear();
        byName.clear();
        byClass.clear();
        playerEntities.clear();
        for (int i = 0; i < entities.length; i++) {
            entities[i] = LogicEntities.create(this, i, table.entities().get(i));
            index(entities[i]);
        }
        for (LogicEntity entity : entities) entity.spawn();
        for (LogicEntity entity : entities) if (!entity.removed) entity.activate();
        for (LogicEntity entity : entities) if (entity instanceof SoundEntities.Synced) soundChanges.add(entity.index);
        dirty = true;
    }

    /** Entered anew through a level change while empty: Source restores the level and activates every entity again. */
    void reenter() {
        loadType = LoadType.TRANSITION;
        for (LogicEntity entity : entities) if (!entity.removed) entity.activate();
        dirty = true;
    }

    LoadType loadType() { return loadType; }

    /**
     * A player arriving through a level change who already stands in a level change does not take
     * it until they have left it once. Source has no such guard -- its maps keep the arrival point
     * clear -- so every other trigger touches them as usual.
     */
    void arrived(ServerPlayer player) {
        for (LogicEntity entity : entities) if (entity instanceof Triggers.ChangeLevel volume && !entity.removed) volume.arrived(player);
    }

    private void index(LogicEntity entity) {
        if (!entity.name().isEmpty()) byName.computeIfAbsent(entity.name().toLowerCase(Locale.ROOT), ignored -> new ArrayList<>()).add(entity);
        byClass.computeIfAbsent(entity.classname, ignored -> new ArrayList<>()).add(entity);
    }

    void removed(LogicEntity entity) {
        List<LogicEntity> named = byName.get(entity.name().toLowerCase(Locale.ROOT));
        if (named != null) named.remove(entity);
        List<LogicEntity> classed = byClass.get(entity.classname);
        if (classed != null) classed.remove(entity);
        dirty = true;
    }

    /** Every entity whose {@code parentname} names {@code parent}, looked up while the parent still stands. */
    List<LogicEntity> childrenOf(LogicEntity parent) {
        List<LogicEntity> children = new ArrayList<>();
        for (LogicEntity entity : entities) if (entity != null && entity != parent && !entity.removed && entity.parent() == parent) children.add(entity);
        return children;
    }

    void rename(LogicEntity entity, String name) {
        List<LogicEntity> named = byName.get(entity.name().toLowerCase(Locale.ROOT));
        if (named != null) named.remove(entity);
        entity.setName(name);
        if (!entity.name().isEmpty()) {
            List<LogicEntity> list = byName.computeIfAbsent(entity.name().toLowerCase(Locale.ROOT), ignored -> new ArrayList<>());
            // Keep lump order, which is the order Source's entity list finds them in.
            int at = 0;
            while (at < list.size() && list.get(at).index < entity.index) at++;
            list.add(at, entity);
        }
    }

    /** One server tick of a running map. */
    void tick(ServerLevel level, List<ServerPlayer> inside) {
        this.level = level;
        this.inside = inside;
        traceLines = 0;
        time += TICK_SECONDS;
        dirty = true;
        for (LogicEntity entity : entities) if (entity instanceof Triggers.Touchable touchable && !entity.removed) touchable.touch(inside);
        for (LogicEntity entity : entities) {
            if (entity.removed || entity.nextThink > time) continue;
            entity.nextThink = LogicEntity.NEVER;
            entity.think();
        }
        int fired = 0;
        EventQueue.Event event;
        while ((event = queue.poll(time)) != null) {
            dispatch(event);
            if (++fired >= MAX_EVENTS_PER_TICK) {
                dev.theredja.src2mc.Src2mc.LOGGER.warn("src2mc logic: {} fired {} events in one tick; the rest wait for the next",
                    placement.mapId(), fired);
                break;
            }
        }
    }

    /** {@code CEventQueue::ServiceEvents} for one event: every entity the target names, else every entity of that classname. */
    private void dispatch(EventQueue.Event event) {
        inputsFired++;
        if (event.direct() != null) {
            event.direct().input(event.input(), event.value(), event.activator(), event.caller());
            return;
        }
        List<LogicEntity> targets = find(event.target(), event.caller(), event.activator(), event.caller());
        if (targets.isEmpty()) targets = byClassname(event.target());
        if (targets.isEmpty()) {
            unhandled.merge("(no target) " + event.target().toLowerCase(Locale.ROOT) + "." + event.input().toLowerCase(Locale.ROOT), 1, Integer::sum);
            return;
        }
        for (LogicEntity target : targets) target.input(event.input(), event.value(), event.activator(), event.caller());
    }

    void queue(double delay, String target, LogicEntity direct, String input, String value, Actor activator, LogicEntity caller) {
        queue.add(time + delay, target, direct, input, value, activator, caller);
    }

    int cancel(LogicEntity caller) { return queue.cancel(caller); }

    /**
     * {@code CGlobalEntityList::FindEntityByName} with {@code FindEntityProcedural}'s special names,
     * in entity order. A name ending in {@code *} matches every name it starts.
     */
    List<LogicEntity> find(String target, LogicEntity searching, Actor activator, LogicEntity caller) {
        if (target == null || target.isEmpty()) return List.of();
        String name = target.toLowerCase(Locale.ROOT);
        if (name.startsWith("!")) {
            LogicEntity found = switch (name) {
                case "!self" -> searching;
                case "!caller" -> caller;
                case "!activator" -> actorEntity(activator);
                case "!player", "!pvsplayer", "!picker", "!speechtarget" -> actorEntity(player(activator));
                default -> null;
            };
            return found == null || found.removed ? List.of() : List.of(found);
        }
        if (name.endsWith("*")) {
            String prefix = name.substring(0, name.length() - 1);
            List<LogicEntity> found = new ArrayList<>();
            for (LogicEntity entity : entities) {
                if (!entity.removed && entity.name().toLowerCase(Locale.ROOT).startsWith(prefix) && !entity.name().isEmpty()) found.add(entity);
            }
            return found;
        }
        List<LogicEntity> found = byName.get(name);
        return found == null ? List.of() : List.copyOf(found);
    }

    /** The first entity {@link #find} names, or null. */
    LogicEntity findFirst(String target, LogicEntity searching, Actor activator, LogicEntity caller) {
        List<LogicEntity> found = find(target, searching, activator, caller);
        return found.isEmpty() ? null : found.get(0);
    }

    private List<LogicEntity> byClassname(String target) {
        String name = target.toLowerCase(Locale.ROOT);
        if (name.endsWith("*")) {
            String prefix = name.substring(0, name.length() - 1);
            List<LogicEntity> found = new ArrayList<>();
            for (LogicEntity entity : entities) if (!entity.removed && entity.classname.startsWith(prefix)) found.add(entity);
            return found;
        }
        List<LogicEntity> found = byClass.get(name);
        return found == null ? List.of() : List.copyOf(found);
    }

    /**
     * The player a chain is about: its activator when that is a player, as the user chose for
     * multiplayer; otherwise the first player inside the map, Source's only player.
     */
    PlayerActor player(Actor activator) {
        if (activator instanceof PlayerActor player) return player;
        return inside.isEmpty() ? null : new PlayerActor(inside.get(0).getUUID());
    }

    /** Inputs sent to a player go to a stand-in that has none of its own, and are counted as unhandled. */
    private LogicEntity actorEntity(Actor actor) {
        if (actor instanceof LogicEntity entity) return entity;
        if (actor instanceof PlayerActor player) return playerEntities.computeIfAbsent(player.id(), id -> new LogicEntity(this, -1, null));
        return null;
    }

    ServerPlayer serverPlayer(Actor actor) {
        if (!(actor instanceof PlayerActor player) || level == null) return null;
        return level.getServer().getPlayerList().getPlayer(player.id());
    }

    /** Where a player stands, map-local, or null when they are not in this map's level. */
    double[] playerPosition(PlayerActor actor) {
        if (level == null || actor == null) return null;
        net.minecraft.world.entity.player.Player player = level.getPlayerByUUID(actor.id());
        if (player == null) return null;
        return new double[]{player.getX() - placement.translation().getX(), player.getY() - placement.translation().getY(),
            player.getZ() - placement.translation().getZ()};
    }

    LogicEntity entity(int index) { return index >= 0 && index < entities.length ? entities[index] : null; }

    double time() { return time; }

    ServerLevel level() { return level; }

    List<ServerPlayer> inside() { return inside; }

    /** A map-local position as a world one. */
    Vec3 world(double[] local) {
        return new Vec3(local[0] + placement.translation().getX(), local[1] + placement.translation().getY(), local[2] + placement.translation().getZ());
    }

    /** A player's box, map-local. */
    AABB local(ServerPlayer player) {
        return player.getBoundingBox().move(-placement.translation().getX(), -placement.translation().getY(), -placement.translation().getZ());
    }

    double[] position(LogicEntity entity) {
        if (entity.index < 0) return null;
        LogicTable.Entity source = table.entities().get(entity.index);
        if (source.origin() != null) return source.origin();
        if (source.volume() >= 0) {
            double[] b = table.volumes().get(source.volume()).bounds();
            return new double[]{(b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2};
        }
        return null;
    }

    LogicTable.Volume volume(LogicEntity entity) {
        if (entity.index < 0) return null;
        int volume = table.entities().get(entity.index).volume();
        return volume < 0 ? null : table.volumes().get(volume);
    }

    LogicTable.Scene scene(LogicEntity entity) {
        if (entity.index < 0) return null;
        int scene = table.entities().get(entity.index).scene();
        return scene < 0 ? null : table.scenes().get(scene);
    }

    // ---- What the clients hear.

    void soundChanged(LogicEntity entity) { soundChanges.add(entity.index); }

    /**
     * Plays a sound the logic makes once: a button's click, a line of a scene. {@code script} is a
     * soundscript name or a sound file path; {@code at} map-local, or null with no speaker for one
     * heard everywhere. Returns an id {@link #stopSound} takes, or -1 when the map has no such sound.
     */
    long emitSound(String script, double[] at, UUID speaker, String caption, double duration, double volume) {
        // Maps write "0", and INFRA "nothing", where a sound keyvalue plays none.
        if (script == null || script.isBlank() || script.trim().equals("0") || script.equalsIgnoreCase("nothing") || audio == null) return -1;
        AudioTable.Script entry = audio.script(normalizeSound(script));
        if (entry == null) {
            unhandled.merge("(no sound) " + script.toLowerCase(Locale.ROOT), 1, Integer::sum);
            return -1;
        }
        long id = ++soundEventIds;
        boolean voice = speaker != null || caption != null;
        soundEvents.add(new LogicNetwork.SoundEvent(id, entry.name(), at, speaker, caption, (float) duration, (float) volume, voice));
        return id;
    }

    void stopSound(long id) { if (id > 0) stoppedSounds.add(id); }

    /** Until when each scene actor is speaking, for {@code busyactor}. */
    private final Map<String, Double> speakingUntil = new HashMap<>();

    void speaking(String actor, double seconds) { speakingUntil.merge(actor.toLowerCase(Locale.ROOT), time + seconds, Math::max); }

    boolean isSpeaking(String actor) {
        Double until = speakingUntil.get(actor.toLowerCase(Locale.ROOT));
        return until != null && until > time;
    }

    static String normalizeSound(String name) { return LogicNames.sound(name); }

    /** Every sound state, for a player who arrives; leaves this tick's changes for everyone else. */
    LogicNetwork.Update fullState() {
        List<LogicNetwork.SoundState> states = new ArrayList<>();
        for (LogicEntity entity : entities) if (entity instanceof SoundEntities.Synced synced && !entity.removed) states.add(synced.soundState());
        return new LogicNetwork.Update(states, List.of(), List.of());
    }

    /** Hands what changed this tick to the network and forgets it. */
    LogicNetwork.Update drainUpdate(boolean full) {
        List<LogicNetwork.SoundState> states = new ArrayList<>();
        if (full) {
            for (LogicEntity entity : entities) if (entity instanceof SoundEntities.Synced synced && !entity.removed) states.add(synced.soundState());
        } else {
            for (int index : soundChanges) if (entities[index] instanceof SoundEntities.Synced synced) states.add(synced.soundState());
        }
        LogicNetwork.Update update = new LogicNetwork.Update(states, List.copyOf(soundEvents), List.copyOf(stoppedSounds));
        soundChanges.clear();
        soundEvents.clear();
        stoppedSounds.clear();
        return update;
    }

    // ---- Counters and tracing.

    void outputFired(LogicEntity entity, String output, int connections) { if (connections > 0) outputsFired++; }

    void unhandled(LogicEntity entity, String input) { unhandled.merge(entity.classname + "." + input, 1, Integer::sum); }

    void scriptCall(LogicEntity entity, String input) {
        scriptCalls++;
        unhandled.merge(entity.classname + "." + input + " (VScript)", 1, Integer::sum);
    }

    void setTrace(UUID player, boolean on) { if (on) tracers.add(player); else tracers.remove(player); }

    void trace(LogicEntity caller, String output, Connection connection, String value) {
        if (tracers.isEmpty() || level == null || traceLines >= MAX_TRACE_LINES_PER_TICK) return;
        traceLines++;
        String line = String.format(Locale.ROOT, "[%.2f] %s.%s -> %s.%s(%s)%s", time,
            caller == null ? "?" : caller.describe(), output, connection.target, connection.input, value == null ? "" : value,
            connection.delay > 0 ? String.format(Locale.ROOT, " +%.2fs", connection.delay) : "");
        for (UUID id : tracers) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(id);
            if (player != null) player.sendSystemMessage(Component.literal(line));
        }
    }

    String status() {
        int live = 0;
        for (LogicEntity entity : entities) if (entity != null && !entity.removed) live++;
        StringBuilder text = new StringBuilder(String.format(Locale.ROOT,
            "%s: time %.2fs, %d/%d entities, %d queued, %d inputs, %d outputs fired, %d VScript calls skipped",
            placement.mapId(), time, live, entities.length, queue.size(), inputsFired, outputsFired, scriptCalls));
        if (!unhandled.isEmpty()) {
            text.append("\n  unhandled (most frequent first):");
            unhandled.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(12)
                .forEach(entry -> text.append("\n    ").append(entry.getKey()).append(" x").append(entry.getValue()));
        }
        return text.toString();
    }

    /** Entities whose name, or else classname, contains {@code filter}, with what they hold. */
    List<String> list(String filter, int limit) {
        String needle = filter.toLowerCase(Locale.ROOT);
        List<String> lines = new ArrayList<>();
        for (LogicEntity entity : entities) {
            if (entity.removed) continue;
            if (!entity.name().toLowerCase(Locale.ROOT).contains(needle) && !entity.classname.contains(needle)) continue;
            String state = entity.state();
            lines.add(entity.describe() + (state.isEmpty() ? "" : ": " + state)
                + (entity.nextThink != LogicEntity.NEVER ? String.format(Locale.ROOT, " (thinks in %.2fs)", entity.nextThink - time) : ""));
            if (lines.size() >= limit) break;
        }
        return lines;
    }

    // ---- Saving.

    boolean dirty() { return dirty; }

    CompoundTag save() {
        dirty = false;
        CompoundTag tag = new CompoundTag();
        tag.putInt("entity_count", entities.length);
        tag.putString("load_type", loadType.name());
        tag.putDouble("time", time);
        CompoundTag states = new CompoundTag();
        for (LogicEntity entity : entities) {
            CompoundTag state = new CompoundTag();
            entity.save(state);
            if (!state.isEmpty()) states.put(Integer.toString(entity.index), state);
        }
        tag.put("entities", states);
        ListTag events = new ListTag();
        for (EventQueue.Event event : queue.pending()) {
            CompoundTag item = new CompoundTag();
            item.putDouble("time", event.time());
            if (event.target() != null) item.putString("target", event.target());
            if (event.direct() != null && event.direct().index >= 0) item.putInt("direct", event.direct().index);
            else if (event.direct() != null) continue;
            item.putString("input", event.input());
            if (event.value() != null) item.putString("value", event.value());
            if (event.activator() instanceof PlayerActor player) item.putUUID("activator_player", player.id());
            else if (event.activator() instanceof LogicEntity entity && entity.index >= 0) item.putInt("activator", entity.index);
            if (event.caller() != null && event.caller().index >= 0) item.putInt("caller", event.caller().index);
            events.add(item);
        }
        tag.put("events", events);
        return tag;
    }

    /**
     * Spawns the map and puts a saved state on it. False, leaving it freshly spawned, when the save
     * is of a different export of the map.
     */
    boolean load(CompoundTag tag) {
        spawn(LoadType.valueOf(tag.getString("load_type").isEmpty() ? "NEW_GAME" : tag.getString("load_type")));
        if (tag.getInt("entity_count") != entities.length) return false;
        time = tag.getDouble("time");
        CompoundTag states = tag.getCompound("entities");
        for (LogicEntity entity : entities) {
            String key = Integer.toString(entity.index);
            if (!states.contains(key)) continue;
            String spawnedName = entity.name();
            entity.load(states.getCompound(key));
            if (!entity.name().equals(spawnedName)) {
                // Re-index under the saved name.
                String saved = entity.name();
                entity.setName(spawnedName);
                rename(entity, saved);
            }
            if (entity.removed) removed(entity);
        }
        for (Tag value : tag.getList("events", Tag.TAG_COMPOUND)) {
            CompoundTag item = (CompoundTag) value;
            Actor activator = item.hasUUID("activator_player") ? new PlayerActor(item.getUUID("activator_player"))
                : item.contains("activator") ? entity(item.getInt("activator")) : null;
            queue.add(item.getDouble("time"), item.contains("target") ? item.getString("target") : null,
                item.contains("direct") ? entity(item.getInt("direct")) : null, item.getString("input"),
                item.contains("value") ? item.getString("value") : null, activator,
                item.contains("caller") ? entity(item.getInt("caller")) : null);
        }
        for (LogicEntity entity : entities) if (entity instanceof SoundEntities.Synced) soundChanges.add(entity.index);
        return true;
    }

    /** {@code (anchor, mapId)} for logs. */
    @Override public String toString() { return placement.mapId() + "@" + placement.anchorWorld().toShortString(); }

    /** Insertion-ordered copy of the name index's keys, for command suggestions. */
    List<String> names() { return List.copyOf(new LinkedHashMap<>(byName).keySet()); }
}
