package dev.theredja.src2mc.logic;

import dev.theredja.src2mc.bundle.LogicTable;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * {@code point_template}, after the 2013 SDK's {@code point_template.cpp},
 * {@code templateentities.cpp} and {@code MapEntity_ParseAllEntities}. As the map spawns, each
 * template, in lump order, takes every entity its {@code Template01} to {@code Template16} name
 * (a trailing {@code *} matching every name it starts) out of the map before it spawns, unless
 * spawnflag 1 keeps them; a template finds only what earlier ones left. {@code ForceSpawn} then
 * makes a copy of each at its compiled place, spawns and activates them, and fires
 * {@code OnEntitySpawned}.
 *
 * <p>Name fixup ({@code Templates_ReconnectIOForGroup}, unless spawnflag 2): a keyvalue other than
 * {@code targetname} whose value -- up to its first comma -- or an output whose target names a
 * member of the group gets {@code &0000} appended, and so does that member's {@code targetname};
 * each copy then has the number of its instance there, so a copy's outputs reach their own group's
 * members and no other copies. Members nothing in the group names keep their names. The instance
 * counter is the map's, counting from one per template with members, as precaching counts them.
 */
public final class Templates {
    private Templates() {}

    private static final int DONT_REMOVE = 1, PRESERVE_NAMES = 2;
    private static final int MAX_TEMPLATES = 16;

    /**
     * One point_template's members, record indices in the order Source adds them, and where a
     * copy's names change: per member, its {@code targetname}, each keyvalue and each output.
     */
    record Group(int template, int[] members, boolean removes, boolean[] renamed, boolean[][] keys, boolean[][] outputs) {
        int memberOf(int source) {
            for (int i = 0; i < members.length; i++) if (members[i] == source) return i;
            return -1;
        }

        private boolean fixed(int member) {
            if (renamed[member]) return true;
            for (boolean key : keys[member]) if (key) return true;
            for (boolean output : outputs[member]) if (output) return true;
            return false;
        }
    }

    // Keyed by identity: LogicTable compares and hashes as itself.
    private static final Map<LogicTable, List<Group>> GROUPS = Collections.synchronizedMap(new WeakHashMap<>());

    /** Every point_template's group of a map's logic table, worked out once. */
    static List<Group> groups(LogicTable table) {
        return GROUPS.computeIfAbsent(table, Templates::compute);
    }

    /**
     * The lump entities a template takes out of the map as it spawns: until a copy is made they
     * neither show nor collide. Empty for a map without templates; shared, so read it only.
     */
    public static BitSet removedAtSpawn(LogicTable table) {
        if (table == null) return NONE;
        return REMOVED.computeIfAbsent(table, key -> {
            BitSet removed = new BitSet();
            for (Group group : groups(key)) if (group.removes()) for (int member : group.members()) removed.set(member);
            return removed;
        });
    }

    private static final BitSet NONE = new BitSet();
    /** Asked per prop per frame by the renderers: worked out once per table, and not to be changed. */
    private static final Map<LogicTable, BitSet> REMOVED = Collections.synchronizedMap(new WeakHashMap<>());

    private static List<Group> compute(LogicTable table) {
        List<LogicTable.Entity> entities = table.entities();
        BitSet gone = new BitSet();
        List<Group> groups = new ArrayList<>();
        for (int template = 0; template < entities.size(); template++) {
            LogicTable.Entity entity = entities.get(template);
            if (!entity.classname().equals("point_template")) continue;
            int spawnflags = Variant.integer(entity.value("spawnflags"));
            // StartBuildingTemplates: every entity of each named, in entity order.
            List<Integer> members = new ArrayList<>();
            for (int slot = 1; slot <= MAX_TEMPLATES; slot++) {
                String name = entity.value(String.format(Locale.ROOT, "template%02d", slot));
                if (name == null || name.isBlank()) continue;
                for (int i = 0; i < entities.size(); i++) {
                    if (gone.get(i) || i == template) continue;
                    String targetname = entities.get(i).targetname();
                    if (targetname != null && LogicEntities.Filter.matches(targetname, name.trim())) members.add(i);
                }
            }
            // Templates_Add refuses an entity without a name; the search found none of those.
            int[] memberArray = members.stream().mapToInt(Integer::intValue).toArray();
            boolean removes = (spawnflags & DONT_REMOVE) == 0;
            if (removes) for (int member : memberArray) gone.set(member);
            groups.add(fixups(table, template, memberArray, removes, (spawnflags & PRESERVE_NAMES) == 0));
        }
        return List.copyOf(groups);
    }

    /** {@code Templates_ReconnectIOForGroup}. */
    private static Group fixups(LogicTable table, int template, int[] members, boolean removes, boolean allowFixup) {
        int count = members.length;
        boolean[] renamed = new boolean[count];
        boolean[][] keys = new boolean[count][], outputs = new boolean[count][];
        String[] names = new String[count];
        for (int i = 0; i < count; i++) names[i] = table.entities().get(members[i]).targetname();
        for (int i = 0; i < count; i++) {
            LogicTable.Entity entity = table.entities().get(members[i]);
            keys[i] = new boolean[entity.keyvalues().size()];
            outputs[i] = new boolean[entity.outputs().size()];
            if (!allowFixup) continue;
            for (int k = 0; k < entity.keyvalues().size(); k++) {
                String[] pair = entity.keyvalues().get(k);
                if (pair[0].equalsIgnoreCase("targetname")) continue;
                int comma = pair[1].indexOf(',');
                int named = named(names, comma < 0 ? pair[1] : pair[1].substring(0, comma));
                if (named < 0) continue;
                keys[i][k] = true;
                renamed[named] = true;
            }
            // Outputs are keyvalues "target,input,..." in Source's map data; here the target is apart.
            for (int o = 0; o < entity.outputs().size(); o++) {
                int named = named(names, entity.outputs().get(o).target());
                if (named < 0) continue;
                outputs[i][o] = true;
                renamed[named] = true;
            }
        }
        return new Group(template, members, removes, renamed, keys, outputs);
    }

    private static int named(String[] names, String value) {
        for (int i = 0; i < names.length; i++) if (names[i] != null && names[i].equalsIgnoreCase(value)) return i;
        return -1;
    }

    /** Member {@code member}'s record as copy {@code instance} has it: names fixed up where the group needs it. */
    static LogicTable.Entity record(LogicTable table, Group group, int member, int instance) {
        LogicTable.Entity entity = table.entities().get(group.members()[member]);
        if (!group.fixed(member)) return entity;
        String suffix = String.format(Locale.ROOT, "&%04d", instance);
        List<String[]> keyvalues = new ArrayList<>(entity.keyvalues().size());
        for (int k = 0; k < entity.keyvalues().size(); k++) {
            String[] pair = entity.keyvalues().get(k);
            String value = pair[1];
            if (pair[0].equalsIgnoreCase("targetname") && group.renamed()[member]) {
                value = value + suffix;
            } else if (group.keys()[member][k]) {
                int comma = value.indexOf(',');
                value = comma < 0 ? value + suffix : value.substring(0, comma) + suffix + value.substring(comma);
            }
            keyvalues.add(new String[]{pair[0], value});
        }
        List<LogicTable.Output> outputs = new ArrayList<>(entity.outputs().size());
        for (int o = 0; o < entity.outputs().size(); o++) {
            LogicTable.Output output = entity.outputs().get(o);
            outputs.add(group.outputs()[member][o] ? new LogicTable.Output(output.output(), output.target() + suffix, output.input(),
                output.parameter(), output.delay(), output.times()) : output);
        }
        return new LogicTable.Entity(entity.classname(), keyvalues, outputs, entity.origin(), entity.volume(), entity.scene());
    }

    /**
     * Takes each template's members out of the map as it spawns, before any entity spawns, and
     * returns the instance counter as precaching leaves it.
     */
    static int build(MapLogic map, Map<Integer, Group> out) {
        int instances = 0;
        for (Group group : groups(map.table)) {
            out.put(group.template(), group);
            if (group.members().length > 0) instances++;
            if (!group.removes()) continue;
            for (int member : group.members()) {
                LogicEntity entity = map.entity(member);
                if (entity == null || entity.removed) continue;
                // UTIL_RemoveImmediate before it spawns: nothing fires, nothing is parented yet.
                entity.removed = true;
                map.removed(entity);
            }
        }
        return instances;
    }

    /** A saved copy made again in its slot, not yet spawned; null when its template no longer has that member. */
    static LogicEntity restore(MapLogic map, Group group, int source, int instance, int slot) {
        int member = group.memberOf(source);
        if (member < 0) return null;
        return map.createCopy(source, record(map.table, group, member, instance), group.template(), instance, slot);
    }

    /** {@code CPointTemplate}. */
    static final class PointTemplate extends LogicEntity {
        PointTemplate(MapLogic map, int index, LogicTable.Entity entity) { super(map, index, entity); }

        @Override boolean accept(String input, String value, Actor activator, LogicEntity caller) {
            if (!input.equals("forcespawn")) return false;
            Group group = map.template(index);
            if (group == null || group.members().length == 0) return true;
            createInstance(group);
            fire("onentityspawned", this, null);
            return true;
        }

        /**
         * {@code CreateInstance}: a copy of every member at its compiled place (the template
         * stands where the map put it), spawned, parented as {@code SpawnHierarchicalList} links
         * them, then activated.
         */
        private void createInstance(Group group) {
            int instance = map.nextTemplateInstance();
            List<LogicEntity> made = new ArrayList<>(group.members().length);
            for (int member = 0; member < group.members().length; member++) {
                LogicEntity copy = map.createCopy(group.members()[member], record(map.table, group, member, instance), index, instance, -1);
                if (copy != null) made.add(copy);
            }
            for (LogicEntity copy : made) copy.spawn();
            for (LogicEntity copy : made) copy.attachAtSpawn();
            for (LogicEntity copy : made) if (!copy.removed) copy.activate();
        }

        @Override String state() {
            Group group = map.template(index);
            return group == null ? "" : group.members().length + " templates" + (group.removes() ? "" : ", kept in the map");
        }
    }
}
