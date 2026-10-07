package dev.theredja.src2mc.client.render;

import dev.theredja.src2mc.bundle.BundleMap;
import dev.theredja.src2mc.bundle.LogicTable;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * {@code func_areaportalwindow}, after the 2013 SDK's {@code func_areaportalwindow.cpp} and
 * {@code c_func_areaportalwindow.cpp}: on activation it takes the model of the first entity named
 * its {@code target} and hides that entity, then draws the model itself with a blend that grows
 * with the eye's distance to it -- {@code TranslucencyLimit} up to {@code FadeStartDist}, solid
 * from {@code FadeDist} on -- so a far area behind it fades to the brush (usually
 * {@code tools/toolsblack}) instead of being drawn. The converter makes every such target a
 * mover; this says how much of it to draw.
 */
final class AreaPortalWindows {
    private AreaPortalWindows() {}

    /** Source units per block; fade distances are Source units. */
    private static final double UNITS_PER_BLOCK = 32.0;

    /** A window's fade, by the lump index of the entity whose model it draws. */
    record Window(float fadeStart, float fadeEnd, float translucencyLimit) {
        /** {@code GetDistanceBlend}: the eye's distance to the brush's world box, remapped and clamped. */
        float blend(AABB box, Vec3 eye) {
            double dx = Math.max(0, Math.max(box.minX - eye.x, eye.x - box.maxX));
            double dy = Math.max(0, Math.max(box.minY - eye.y, eye.y - box.maxY));
            double dz = Math.max(0, Math.max(box.minZ - eye.z, eye.z - box.maxZ));
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz) * UNITS_PER_BLOCK;
            return remapClamped(distance, fadeStart, fadeEnd, translucencyLimit, 1);
        }
    }

    /** {@code RemapValClamped}. */
    static float remapClamped(double value, double a, double b, double c, double d) {
        if (a == b) return (float) (value >= b ? d : c);
        double t = Math.clamp((value - a) / (b - a), 0.0, 1.0);
        return (float) (c + (d - c) * t);
    }

    // Keyed by identity: LogicTable compares and hashes as itself.
    private static final Map<LogicTable, Map<Integer, Window>> WINDOWS = Collections.synchronizedMap(new WeakHashMap<>());

    /** The window that draws lump entity {@code entity}'s model; null for none. */
    static Window of(BundleMap map, int entity) {
        LogicTable table = map.logic();
        if (table == null) return null;
        return WINDOWS.computeIfAbsent(table, AreaPortalWindows::find).get(entity);
    }

    private static Map<Integer, Window> find(LogicTable table) {
        List<LogicTable.Entity> entities = table.entities();
        Map<Integer, Window> windows = new HashMap<>();
        for (LogicTable.Entity window : entities) {
            if (!window.classname().equalsIgnoreCase("func_areaportalwindow")) continue;
            String target = window.value("target");
            if (target == null || target.isEmpty()) continue;
            for (int i = 0; i < entities.size(); i++) {
                // FindEntityByName: the first of that name.
                if (!target.equalsIgnoreCase(entities.get(i).targetname())) continue;
                windows.putIfAbsent(i, new Window(number(window, "fadestartdist"), number(window, "fadedist"),
                    number(window, "translucencylimit")));
                break;
            }
        }
        return windows;
    }

    private static float number(LogicTable.Entity entity, String key) {
        String value = entity.value(key);
        if (value == null) return 0;
        try {
            return Float.parseFloat(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
