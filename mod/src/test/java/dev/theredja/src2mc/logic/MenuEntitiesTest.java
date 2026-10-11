package dev.theredja.src2mc.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.theredja.src2mc.bundle.BundleMap;
import dev.theredja.src2mc.bundle.LogicTable;
import dev.theredja.src2mc.bundle.SurfaceTable;
import dev.theredja.src2mc.menu.MenuState;
import dev.theredja.src2mc.world.MapPlacement;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

/** point_viewcontrol and vgui_screen on a small hand-made map, run a tick at a time. */
final class MenuEntitiesTest {
    private static final MapPlacement PLACEMENT = new MapPlacement("c", "m", BlockPos.ZERO, BlockPos.ZERO, BlockPos.ZERO, new BlockPos(15, 15, 15));

    private static LogicTable.Entity entity(String classname, double[] origin, String... pairs) {
        List<String[]> keyvalues = new ArrayList<>();
        keyvalues.add(new String[]{"classname", classname});
        List<LogicTable.Output> outputs = new ArrayList<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (pairs[i].startsWith("On")) {
                String[] f = pairs[i + 1].split(",", -1);
                outputs.add(new LogicTable.Output(pairs[i], f[0], f[1], f[2], Double.parseDouble(f[3]), Integer.parseInt(f[4])));
            } else {
                keyvalues.add(new String[]{pairs[i], pairs[i + 1]});
            }
        }
        return new LogicTable.Entity(classname, keyvalues, outputs, origin, -1, -1);
    }

    private static MapLogic run(LogicTable.Entity... entities) {
        List<LogicTable.Entity> list = new ArrayList<>(List.of(entity("worldspawn", null)));
        list.addAll(List.of(entities));
        LogicTable table = new LogicTable(new double[]{0, 0, 0}, list, List.of(), List.of(), Map.of());
        BundleMap map = new BundleMap("m", "m.bsp", new int[]{0, 0, 0}, new int[]{15, 15, 15}, new int[]{0, 0, 0},
            List.of(), List.of(), List.of(), false, new SurfaceTable(List.of(), Map.of()), java.util.Set.of(), null, null, null, null, null, table);
        MapLogic logic = new MapLogic(PLACEMENT, map);
        logic.spawn(MapLogic.LoadType.NEW_GAME);
        return logic;
    }

    private static void seconds(MapLogic logic, double seconds) {
        for (int i = 0; i < Math.round(seconds / MapLogic.TICK_SECONDS); i++) logic.tick(null, List.of());
    }

    private static MapLogic menu() {
        return run(
            entity("point_viewcontrol", new double[]{0, 0, 0}, "targetname", "vc", "smoothspeed", "1", "pathlengthinseconds", "1.75", "fov", "42",
                "angles", "13 270 0", "allowcontrols", "1", "OnEndFollow", "counter,Add,1,0,-1"),
            entity("point_viewcontrol_node", new double[]{10, 0, 0}, "targetname", "node", "fov", "39", "angles", "355 270 0"),
            entity("math_counter", null, "targetname", "counter"),
            entity("vgui_screen", new double[]{1, 1, 1}, "targetname", "screen", "panelname", "p"));
    }

    private static MenuState.View view(MapLogic logic) { return logic.menu(0).views().get(0); }

    private static String counter(MapLogic logic) { return logic.findFirst("counter", null, null, null).state(); }

    @Test void enableHoldsTheCameraAtItsOwnPose() {
        MapLogic logic = menu();
        assertFalse(view(logic).enabled());
        logic.queue(0, "vc", null, "Enable", null, null, null);
        seconds(logic, 0.1);
        MenuState.View view = view(logic);
        assertTrue(view.enabled());
        assertTrue(view.controls(), "allowcontrols");
        assertFalse(view.moving());
        assertEquals(13, view.rest().pitch(), 1e-9);
        assertEquals(270, view.rest().yaw(), 1e-9);
        assertEquals(42, view.rest().fov(), 1e-9);
    }

    @Test void aGlideEndsOnceAtTheNodeAndReverseBringsItBack() {
        MapLogic logic = menu();
        logic.queue(0, "vc", null, "Enable", null, null, null);
        logic.queue(0.1, "vc", null, "SetTargetNode", "node", null, null);
        seconds(logic, 1.0);
        MenuState.View gliding = view(logic);
        assertTrue(gliding.moving());
        assertEquals(2, gliding.path().size(), "the camera's own pose, then the node");
        assertEquals(42, gliding.path().get(0).fov(), 1e-9, "point 0 takes the viewcontrol's fov keyvalue");
        assertEquals(39, gliding.path().get(1).fov(), 1e-9, "the node its own");
        assertTrue(counter(logic).startsWith("0 "), "OnEndFollow is not fired on the way");
        seconds(logic, 1.0);
        MenuState.View arrived = view(logic);
        assertFalse(arrived.moving());
        assertEquals(10, arrived.rest().x(), 1e-9);
        assertEquals(39, arrived.rest().fov(), 1e-9);
        assertTrue(counter(logic).startsWith("1 "), counter(logic));
        seconds(logic, 3.0);
        assertTrue(counter(logic).startsWith("1 "), "resting does not fire it again (INFRA's every-tick fire is debounced)");

        logic.queue(0, "vc", null, "Reverse", null, null, null);
        seconds(logic, 2.0);
        MenuState.View back = view(logic);
        assertFalse(back.moving());
        assertEquals(0, back.rest().x(), 1e-9, "back at path point 0");
        assertEquals(1, back.path().size(), "that point is all the path there is now");
        assertFalse(back.reverse());
        assertTrue(counter(logic).startsWith("2 "), counter(logic));
    }

    @Test void aMissingTargetLeavesTheCameraWhereItIs() {
        MapLogic logic = menu();
        logic.queue(0, "vc", null, "Enable", null, null, null);
        logic.queue(0.1, "vc", null, "SetTargetNode", "nothing", null, null);
        seconds(logic, 1.0);
        assertFalse(view(logic).moving());
        assertEquals(0, view(logic).rest().x(), 1e-9);
        assertTrue(counter(logic).startsWith("0 "));
    }

    @Test void controlsAndScreensFollowTheirInputsAndSurviveASave() {
        MapLogic logic = menu();
        logic.queue(0, "vc", null, "DisableControls", null, null, null);
        logic.queue(0, "screen", null, "SetInactive", null, null, null);
        seconds(logic, 0.1);
        assertFalse(view(logic).controls());
        assertFalse(logic.menu(0).screens().get(0).active());
        assertFalse(logic.hasActiveScreen());
        CompoundTag saved = logic.save();
        MapLogic restored = run(
            entity("point_viewcontrol", new double[]{0, 0, 0}, "targetname", "vc", "allowcontrols", "1", "fov", "42"),
            entity("point_viewcontrol_node", new double[]{10, 0, 0}, "targetname", "node"),
            entity("math_counter", null, "targetname", "counter"),
            entity("vgui_screen", new double[]{1, 1, 1}, "targetname", "screen", "panelname", "p"));
        assertTrue(restored.load(saved));
        assertFalse(restored.menu(0).views().get(0).controls());
        assertFalse(restored.menu(0).screens().get(0).active());
    }

    @Test void menuStateSurvivesTheWire() {
        MapLogic logic = menu();
        logic.queue(0, "vc", null, "Enable", null, null, null);
        logic.queue(0.1, "vc", null, "SetTargetNode", "node", null, null);
        seconds(logic, 0.5);
        MenuState state = logic.menu(1234);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        MenuState.write(buffer, state);
        assertEquals(state, MenuState.read(buffer));
        // The glide's pose follows game time from the start the server sent.
        MenuState.View view = state.views().get(0);
        assertEquals(view.path().get(0).x(), view.pose(view.startTick()).x(), 1e-9);
        assertEquals(10, view.pose(view.startTick() + 20 * 10).x(), 1e-9, "long after, the node");
    }
}
