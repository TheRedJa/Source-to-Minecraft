package dev.theredja.src2mc.client.logic;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.theredja.src2mc.bundle.LogicTable;
import dev.theredja.src2mc.world.MapPlacement;
import dev.theredja.src2mc.world.MoverRegistry;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Which mover carries a map entity on the client, and where that puts the entity's compiled
 * frame now: its own sub-level, else its nearest parent's -- a button parented to a train is
 * on the train. Used to aim at and to draw entities that ride movers.
 */
final class Carriers {
    private Carriers() {}

    /**
     * A mover's sub-level frame: a compiled world point {@code p} (map-local plus the
     * placement's translation) is at {@code pose(p - shift)} now.
     */
    record Frame(Pose3dc pose, Vec3 shift) {
        Vec3 toWorld(Vec3 compiled) { return pose.transformPosition(compiled.subtract(shift)); }
        Vec3 toCompiled(Vec3 world) { return pose.transformPositionInverse(world).add(shift); }
    }

    /** The movers of one placement, by entity index. */
    static Map<Integer, MoverRegistry.Instance> movers(long anchor) {
        Map<Integer, MoverRegistry.Instance> movers = new HashMap<>();
        for (MoverRegistry.Instance instance : MoverRegistry.instances(true)) {
            if (instance.anchor() == anchor) movers.put(instance.entity(), instance);
        }
        return movers;
    }

    /** The mover carrying entity {@code index}, through at most 16 parents; null when none does. */
    static MoverRegistry.Instance carrier(LogicTable logic, Map<Integer, MoverRegistry.Instance> movers, int index) {
        for (int depth = 0; depth < 16 && index >= 0 && index < logic.entities().size(); depth++) {
            MoverRegistry.Instance mover = movers.get(index);
            if (mover != null) return mover;
            String parent = logic.entities().get(index).value("parentname");
            if (parent == null || parent.isBlank()) return null;
            index = byName(logic).getOrDefault(parent.split(",", 2)[0].trim().toLowerCase(Locale.ROOT), -1);
        }
        return null;
    }

    private static final Map<LogicTable, Map<String, Integer>> NAMES = new IdentityHashMap<>();

    /** First entity index per lowercase targetname, as Source's lookup finds them. */
    private static Map<String, Integer> byName(LogicTable logic) {
        // Keyed by identity; old bundle generations' tables drop out once there are many.
        if (NAMES.size() > 64 && !NAMES.containsKey(logic)) NAMES.clear();
        return NAMES.computeIfAbsent(logic, table -> {
            Map<String, Integer> names = new HashMap<>();
            for (int i = 0; i < table.entities().size(); i++) {
                String name = table.entities().get(i).targetname();
                if (name != null) names.putIfAbsent(name.toLowerCase(Locale.ROOT), i);
            }
            return names;
        });
    }

    /**
     * Where a mover's sub-level has its compiled frame, at the logical pose or, with
     * {@code partialTick} not negative, the interpolated render pose; null while it is not here.
     * A plot point is mover-local cell coordinates from the plot's centre; the compiled
     * map-local point adds the mover's cell origin.
     */
    static Frame frame(ClientLevel level, MoverRegistry.Instance instance, MapPlacement placement, float partialTick) {
        var container = SubLevelContainer.getContainer(level);
        if (container == null || !(container.getSubLevel(instance.subLevel()) instanceof ClientSubLevel subLevel)) return null;
        MoverRegistry.Resolved resolved = MoverRegistry.resolve(level, instance.subLevel());
        if (resolved == null) return null;
        BlockPos origin = MoverRegistry.plotOrigin(subLevel.getPlot());
        Vec3 shift = new Vec3(resolved.mover().originX() + placement.translation().getX() - origin.getX(),
            resolved.mover().originY() + placement.translation().getY() - origin.getY(),
            resolved.mover().originZ() + placement.translation().getZ() - origin.getZ());
        return new Frame(partialTick < 0 ? subLevel.logicalPose() : subLevel.renderPose(partialTick), shift);
    }
}
