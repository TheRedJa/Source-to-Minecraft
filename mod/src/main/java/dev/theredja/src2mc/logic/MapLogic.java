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
    /** By slot: the lump's entities, the game code's, then copies point_templates made. */
    private final List<LogicEntity> entities = new ArrayList<>();
    /** How many slots the map itself has; copies come after. */
    private final int mapEntities;
    /**
     * Slots of removed copies, by the map time they were freed: taken again by a copy made a
     * second or more later. A slot taken in the same tick it was freed -- a conveyor kills its
     * last train and makes a new one at once -- looked to the sub-levels and the clients like
     * the old entity jumping to the new one's place.
     */
    private final java.util.TreeMap<Integer, Double> freeSlots = new java.util.TreeMap<>();
    private static final double SLOT_REUSE_DELAY = 1.0;
    /** The record a copy being made is of; see {@link #sourceFor}. */
    private int creatingSource = -1;
    /** Each point_template's group, built as the map spawns; see {@link Templates}. */
    private final Map<Integer, Templates.Group> templates = new HashMap<>();
    /** {@code g_iCurrentTemplateInstance}, the number name fixup appends. */
    private int templateInstance;
    /** Slot, template and instance of each copy alive, for saving. */
    private final Map<Integer, int[]> copies = new java.util.TreeMap<>();
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
    private boolean dirty, restoring;
    /** Screen effects a run without a server would have sent; see {@link ScreenEffects#drain}. */
    final List<ScreenEffects.Sent> screenSent = new ArrayList<>();

    MapLogic(MapPlacement placement, BundleMap map) {
        this.placement = placement;
        this.map = map;
        this.table = map.logic();
        this.audio = map.audio();
        this.mapEntities = table.entities().size() + table.engineEntities().size();
    }

    /** Spawns every entity fresh, as a map loads in Source, and activates them. */
    void spawn(LoadType type) {
        loadType = type;
        time = 0;
        queue.clear();
        byName.clear();
        byClass.clear();
        playerEntities.clear();
        entities.clear();
        freeSlots.clear();
        copies.clear();
        for (int i = 0; i < mapEntities; i++) {
            entities.add(LogicEntities.create(this, i, record(i)));
            index(entities.get(i));
        }
        // CPointTemplate: each takes its entities out of the map before anything spawns.
        templates.clear();
        templateInstance = Templates.build(this, templates);
        for (LogicEntity entity : entities) if (!entity.removed) entity.spawn();
        for (LogicEntity entity : entities) if (!entity.removed) entity.activate();
        // What the game's own code queues as the map spawns; a restored save has its queue already.
        if (!restoring) {
            for (LogicTable.EngineEvent event : table.engineEvents()) queue(event.delay(), event.target(), null, event.input(), event.parameter(), null, null);
        }
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
        if (entity.index >= mapEntities && entity(entity.index) == entity) {
            freeSlots.put(entity.index, time);
            copies.remove(entity.index);
        }
        dirty = true;
    }

    /** The lowest freed slot whose delay is over, taken out of the free ones; null for none. */
    private Integer reusableSlot() {
        Integer found = null;
        for (Map.Entry<Integer, Double> slot : freeSlots.entrySet()) {
            if (time - slot.getValue() >= SLOT_REUSE_DELAY) { found = slot.getKey(); break; }
        }
        // Removed only after the walk: a TreeMap reuses a deleted entry's node for its successor.
        if (found != null) freeSlots.remove(found);
        return found;
    }

    /** The record index a slot's entity is made from, while it is being made. */
    int sourceFor(int index) { return creatingSource >= 0 ? creatingSource : index; }

    /** Whether the slot holds a copy a point_template made. */
    boolean isCopy(LogicEntity entity) { return entity.index >= mapEntities; }

    /**
     * Makes a copy of record {@code source}, as {@code record} (its keyvalues with any name fixup
     * applied), in a free slot ({@code at}, or any for -1), and indexes it; it is not spawned
     * yet. Null when slot {@code at} is taken.
     */
    LogicEntity createCopy(int source, LogicTable.Entity record, int template, int instance, int at) {
        Integer free = at >= 0 ? (freeSlots.remove(at) != null ? Integer.valueOf(at) : null) : reusableSlot();
        if (at >= 0 && free == null && at != entities.size()) return null;
        int slot = free == null ? entities.size() : free;
        LogicEntity copy;
        creatingSource = source;
        try {
            copy = LogicEntities.create(this, slot, record);
        } finally {
            creatingSource = -1;
        }
        if (free == null) entities.add(copy);
        else entities.set(slot, copy);
        index(copy);
        copies.put(slot, new int[]{source, template, instance});
        dirty = true;
        return copy;
    }

    /** The point_template group of entity {@code template}, or null. */
    Templates.Group template(int template) { return templates.get(template); }

    /** {@code Templates_StartUniqueInstance}: the next instance number. */
    int nextTemplateInstance() {
        templateInstance++;
        if (templateInstance >= 10_000) templateInstance = 0;
        return templateInstance;
    }

    /** Every slot, removed ones included, for code outside the logic that walks them. */
    int slots() { return entities.size(); }

    /** Per lump entity, whether the bundle has a mover for it; built when first asked. */
    private boolean[] movers;

    /** Whether the entity is one of the map's movers, or a copy of one. */
    boolean isMover(LogicEntity entity) {
        if (movers == null) {
            movers = new boolean[mapEntities];
            if (map.movers() != null) for (var mover : map.movers().movers()) if (mover.entity() < mapEntities) movers[mover.entity()] = true;
        }
        return entity.source >= 0 && entity.source < movers.length && movers[entity.source];
    }

    /**
     * The solid movers, each with its map-local box where it is now: Source's pushers touch
     * triggers as they move ({@code CPhysicsPushedEntities::FinishPushers}).
     */
    private List<Triggers.Toucher> touchers() {
        if (map.movers() == null) return List.of();
        List<Triggers.Toucher> out = new ArrayList<>();
        for (int i = 0; i < entities.size(); i++) {
            LogicEntity entity = entities.get(i);
            if (entity.removed || !isMover(entity) || (entity.moverState() & dev.theredja.src2mc.world.MoverRegistry.NOT_SOLID) != 0) continue;
            AABB box = moverBox(entity);
            if (box != null) out.add(new Triggers.Toucher(entity, box));
        }
        return out;
    }

    /** A mover's box where it is now: its brushes' box as compiled, else its cells', moved as it is. */
    private AABB moverBox(LogicEntity entity) {
        double[] b;
        LogicTable.Volume volume = volume(entity);
        if (volume != null) {
            b = volume.bounds();
        } else {
            int at = map.movers().indexOfEntity(entity.source);
            if (at < 0) return null;
            var mover = map.movers().movers().get(at);
            b = new double[]{mover.originX(), mover.originY(), mover.originZ(),
                mover.originX() + mover.sizeX(), mover.originY() + mover.sizeY(), mover.originZ() + mover.sizeZ()};
        }
        Rigid motion = entity.worldMotion(time);
        if (motion == null) return new AABB(b[0], b[1], b[2], b[3], b[4], b[5]);
        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX, maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (int corner = 0; corner < 8; corner++) {
            org.joml.Vector3d p = motion.apply(new org.joml.Vector3d((corner & 1) == 0 ? b[0] : b[3], (corner & 2) == 0 ? b[1] : b[4],
                (corner & 4) == 0 ? b[2] : b[5]));
            minX = Math.min(minX, p.x); minY = Math.min(minY, p.y); minZ = Math.min(minZ, p.z);
            maxX = Math.max(maxX, p.x); maxY = Math.max(maxY, p.y); maxZ = Math.max(maxZ, p.z);
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * A logic prop that no longer stands where the bundle has it -- a copy, or one parented anew
     * -- and how to draw it: on mover {@code mover}'s sub-level (-1: in the map) moved by
     * {@code motion} from its compiled place, relative to that mover's own move.
     */
    record Mount(int entity, int source, int mover, Rigid motion) {}

    /** Every live {@link Mount} of the map now. */
    List<Mount> mounts() {
        var props = map.logicProps();
        if (props == null) return List.of();
        List<Mount> out = new ArrayList<>();
        for (int i = 0; i < entities.size(); i++) {
            LogicEntity entity = entities.get(i);
            if (entity.removed || !(isCopy(entity) || entity.reparented()) || props.byEntity(entity.source) == null) continue;
            LogicEntity carrier = entity.parent();
            for (int depth = 0; carrier != null && !isMover(carrier) && depth < 16; depth++) carrier = carrier.parent();
            Rigid world = entity.worldMotion(time);
            if (world == null) world = Rigid.IDENTITY;
            if (carrier != null) {
                Rigid carried = carrier.worldMotion(time);
                if (carried != null) world = carried.inverse().after(world);
            }
            out.add(new Mount(entity.index, entity.source, carrier == null ? -1 : carrier.index, world));
        }
        return out;
    }

    /** Every entity whose {@code parentname} names {@code parent}, looked up while the parent still stands. */
    List<LogicEntity> childrenOf(LogicEntity parent) {
        List<LogicEntity> children = new ArrayList<>();
        for (int i = 0; i < entities.size(); i++) {
            LogicEntity entity = entities.get(i);
            if (entity != parent && !entity.removed && entity.parent() == parent) children.add(entity);
        }
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
        List<Triggers.Toucher> touchers = touchers();
        for (int i = 0; i < entities.size(); i++) {
            if (entities.get(i) instanceof Triggers.Touchable touchable && !entities.get(i).removed) touchable.touch(inside, touchers);
        }
        for (int i = 0; i < entities.size(); i++) {
            LogicEntity entity = entities.get(i);
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
            for (int i = 0; i < entities.size(); i++) {
                LogicEntity entity = entities.get(i);
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
            for (int i = 0; i < entities.size(); i++) if (!entities.get(i).removed && entities.get(i).classname.startsWith(prefix)) found.add(entities.get(i));
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

    LogicEntity entity(int index) { return index >= 0 && index < entities.size() ? entities.get(index) : null; }

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

    /** The table record of entity {@code index}: the lump's entities, then those the game's code adds. */
    LogicTable.Entity record(int index) {
        int lump = table.entities().size();
        return index < lump ? table.entities().get(index) : table.engineEntities().get(index - lump);
    }

    double[] position(LogicEntity entity) {
        if (entity.index < 0) return null;
        LogicTable.Entity source = record(entity.source);
        if (source.origin() != null) return source.origin();
        if (source.volume() >= 0) {
            double[] b = table.volumes().get(source.volume()).bounds();
            return new double[]{(b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2};
        }
        return null;
    }

    LogicTable.Volume volume(LogicEntity entity) {
        if (entity.index < 0) return null;
        int volume = record(entity.source).volume();
        return volume < 0 ? null : table.volumes().get(volume);
    }

    LogicTable.Scene scene(LogicEntity entity) {
        if (entity.index < 0) return null;
        int scene = record(entity.source).scene();
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
            for (int index : soundChanges) if (entity(index) instanceof SoundEntities.Synced synced) states.add(synced.soundState());
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
        for (LogicEntity entity : entities) if (!entity.removed) live++;
        StringBuilder text = new StringBuilder(String.format(Locale.ROOT,
            "%s: time %.2fs, %d/%d entities, %d queued, %d inputs, %d outputs fired, %d VScript calls skipped",
            placement.mapId(), time, live, entities.size(), queue.size(), inputsFired, outputsFired, scriptCalls));
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
        tag.putInt("entity_count", mapEntities);
        tag.putInt("template_instance", templateInstance);
        ListTag copyList = new ListTag();
        for (Map.Entry<Integer, int[]> copy : copies.entrySet()) {
            if (entity(copy.getKey()) == null || entity(copy.getKey()).removed) continue;
            int[] made = copy.getValue();
            copyList.add(new net.minecraft.nbt.IntArrayTag(new int[]{copy.getKey(), made[0], made[1], made[2]}));
        }
        tag.put("copies", copyList);
        tag.putString("load_type", loadType.name());
        tag.putDouble("time", time);
        CompoundTag states = new CompoundTag();
        for (LogicEntity entity : entities) {
            if (entity.removed && isCopy(entity)) continue;
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
            if (event.direct() != null && event.direct().index >= 0 && !event.direct().removed) item.putInt("direct", event.direct().index);
            else if (event.direct() != null) continue;
            item.putString("input", event.input());
            if (event.value() != null) item.putString("value", event.value());
            if (event.activator() instanceof PlayerActor player) item.putUUID("activator_player", player.id());
            else if (event.activator() instanceof LogicEntity entity && entity.index >= 0 && !entity.removed) item.putInt("activator", entity.index);
            if (event.caller() != null && event.caller().index >= 0 && !event.caller().removed) item.putInt("caller", event.caller().index);
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
        restoring = true;
        try {
            spawn(LoadType.valueOf(tag.getString("load_type").isEmpty() ? "NEW_GAME" : tag.getString("load_type")));
        } finally {
            restoring = false;
        }
        if (tag.getInt("entity_count") != mapEntities) return false;
        time = tag.getDouble("time");
        // The copies first, each spawned as it was made, so states and links find them.
        for (Tag value : tag.getList("copies", Tag.TAG_INT_ARRAY)) {
            int[] made = ((net.minecraft.nbt.IntArrayTag) value).getAsIntArray();
            Templates.Group group = made.length == 4 && made[0] >= mapEntities ? templates.get(made[2]) : null;
            if (group == null) continue;
            // Slots freed before the save stay free until the copy that had them is back.
            while (entities.size() < made[0]) {
                LogicEntity vacant = new LogicEntity(this, entities.size(), null);
                vacant.removed = true;
                freeSlots.put(vacant.index, Double.NEGATIVE_INFINITY);
                entities.add(vacant);
            }
            LogicEntity copy = Templates.restore(this, group, made[1], made[3], made[0]);
            if (copy == null) continue;
            copy.spawn();
            copy.activate();
        }
        templateInstance = tag.getInt("template_instance");
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
