package dev.theredja.src2mc.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.theredja.src2mc.bundle.BundleMap;
import dev.theredja.src2mc.bundle.LogicTable;
import dev.theredja.src2mc.bundle.SurfaceTable;
import dev.theredja.src2mc.world.MapPlacement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** env_global and its table, the logic_auto gate, math_remap, func_train and the inputs that are only accepted. */
final class GlobalStatesTest {
    private static final MapPlacement PLACEMENT = new MapPlacement("c", "m", BlockPos.ZERO, BlockPos.ZERO, BlockPos.ZERO, new BlockPos(15, 15, 15));

    private static LogicTable.Entity entity(String classname, String... pairs) {
        List<String[]> keyvalues = new ArrayList<>();
        List<LogicTable.Output> outputs = new ArrayList<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (pairs[i].startsWith("On") || pairs[i].startsWith("Out") || pairs[i].equals("Counter")) {
                String[] f = pairs[i + 1].split(",", -1);
                outputs.add(new LogicTable.Output(pairs[i], f[0], f[1], f[2], Double.parseDouble(f[3]), Integer.parseInt(f[4])));
            } else {
                keyvalues.add(new String[]{pairs[i], pairs[i + 1]});
            }
        }
        keyvalues.add(0, new String[]{"classname", classname});
        return new LogicTable.Entity(classname, keyvalues, outputs, null, -1, -1);
    }

    private static LogicTable.Entity at(LogicTable.Entity entity, double x, double y, double z) {
        return new LogicTable.Entity(entity.classname(), entity.keyvalues(), entity.outputs(), new double[]{x, y, z}, entity.volume(), -1);
    }

    /** A func_train brush: a box whose bounds centre is (1, 1, 1). */
    private static LogicTable.Entity trainBrush(LogicTable.Entity entity) {
        return new LogicTable.Entity(entity.classname(), entity.keyvalues(), entity.outputs(), null, 0, -1);
    }

    private static MapLogic run(LogicTable.Entity... entities) {
        List<LogicTable.Entity> list = new ArrayList<>(List.of(entity("worldspawn")));
        list.addAll(List.of(entities));
        double[][] planes = {{1, 0, 0, 2}, {-1, 0, 0, 0}, {0, 1, 0, 2}, {0, -1, 0, 0}, {0, 0, 1, 2}, {0, 0, -1, 0}};
        List<LogicTable.Volume> volumes = List.of(new LogicTable.Volume(new double[]{0, 0, 0, 2, 2, 2}, List.<double[][]>of(planes)));
        LogicTable table = new LogicTable(new double[]{0, 0, 0}, list, volumes, List.of(), Map.of());
        BundleMap map = new BundleMap("m", "m.bsp", new int[]{0, 0, 0}, new int[]{15, 15, 15}, new int[]{0, 0, 0},
            List.of(), List.of(), List.of(), false, new SurfaceTable(List.of(), Map.of()), java.util.Set.of(), null, null, null, null, null, table);
        MapLogic logic = new MapLogic(PLACEMENT, map);
        logic.spawn(MapLogic.LoadType.NEW_GAME);
        return logic;
    }

    private static void seconds(MapLogic logic, double seconds) {
        for (int i = 0; i < Math.round(seconds / MapLogic.TICK_SECONDS); i++) logic.tick(null, List.of());
    }

    private static String state(MapLogic logic, String name) { return logic.findFirst(name, null, null, null).state(); }

    @Test void envGlobalSetsTogglesAndCountsLikeSource() {
        MapLogic logic = run(
            entity("env_global", "targetname", "g", "globalstate", "Gordon_Test", "initialstate", "1", "spawnflags", "1", "counter", "5",
                "Counter", "out,Add,,0,-1"),
            entity("env_global", "targetname", "absent", "globalstate", "never_set"),
            entity("math_counter", "targetname", "out"));
        assertEquals(1, GlobalStates.state(logic, "gordon_test"), "added on at spawn, names ignore case");
        assertEquals(5, GlobalStates.counter(logic, "gordon_test"));
        assertEquals(GlobalStates.ABSENT, GlobalStates.state(logic, "never_set"), "no set flag, nothing added");
        logic.queue(0, "g", null, "Toggle", null, null, null);
        seconds(logic, 0.1);
        assertFalse(GlobalStates.isOn(logic, "gordon_test"));
        assertEquals(GlobalStates.OFF, GlobalStates.state(logic, "gordon_test"));
        logic.queue(0, "g", null, "Remove", null, null, null);
        logic.queue(0, "g", null, "Toggle", null, null, null);
        seconds(logic, 0.1);
        assertEquals(GlobalStates.DEAD, GlobalStates.state(logic, "gordon_test"), "a dead state stays dead");
        logic.queue(0, "g", null, "AddToCounter", "3", null, null);
        logic.queue(0, "g", null, "GetCounter", null, null, null);
        seconds(logic, 0.1);
        assertEquals(8, GlobalStates.counter(logic, "gordon_test"));
        assertTrue(state(logic, "out").startsWith("8 "), state(logic, "out"));
        // Absent: a toggle reads it as off and so turns it on.
        logic.queue(0, "absent", null, "Toggle", null, null, null);
        seconds(logic, 0.1);
        assertTrue(GlobalStates.isOn(logic, "never_set"));
    }

    @Test void aGatedAutoFiresOnlyWhileItsStateIsOn() {
        MapLogic closed = run(
            entity("logic_auto", "globalstate", "gate", "OnMapSpawn", "hits,Add,1,0,-1"),
            entity("math_counter", "targetname", "hits"));
        seconds(closed, 0.5);
        assertTrue(state(closed, "hits").startsWith("0 "), "no state: the gate is shut");
        MapLogic off = run(
            entity("env_global", "globalstate", "gate", "spawnflags", "1", "initialstate", "0"),
            entity("logic_auto", "globalstate", "gate", "OnMapSpawn", "hits,Add,1,0,-1"),
            entity("math_counter", "targetname", "hits"));
        seconds(off, 0.5);
        assertTrue(state(off, "hits").startsWith("0 "), "off: shut");
        MapLogic on = run(
            entity("env_global", "globalstate", "gate", "spawnflags", "1", "initialstate", "1"),
            entity("logic_auto", "globalstate", "gate", "OnMapSpawn", "hits,Add,1,0,-1"),
            entity("logic_auto", "OnMapSpawn", "hits,Add,10,0,-1"),
            entity("math_counter", "targetname", "hits"));
        seconds(on, 0.5);
        assertTrue(state(on, "hits").startsWith("11 "), state(on, "hits"));
    }

    @Test void tableSurvivesSavingAndReplaysWhatTheMapDeclared() {
        GlobalStates.Table table = new GlobalStates.Table();
        table.set("Alpha", GlobalStates.ON);
        table.setCounter("alpha", 4);
        table.set("beta", GlobalStates.DEAD);
        GlobalStates.Table loaded = new GlobalStates.Table();
        loaded.load(table.toTag());
        assertEquals(GlobalStates.ON, loaded.state("ALPHA"));
        assertEquals(4, loaded.counter("alpha"));
        assertEquals(GlobalStates.DEAD, loaded.state("beta"));
        // A map's own table is done again on the level's: an existing state keeps its value, a counter is set.
        GlobalStates.Table own = new GlobalStates.Table();
        own.declare("alpha", GlobalStates.OFF, 9);
        own.declare("gamma", GlobalStates.ON, 0);
        own.replayInto(loaded);
        assertEquals(GlobalStates.ON, loaded.state("alpha"));
        assertEquals(9, loaded.counter("alpha"));
        assertEquals(GlobalStates.ON, loaded.state("gamma"));
        loaded.reset();
        assertEquals(GlobalStates.ABSENT, loaded.state("alpha"));
    }

    @Test void aNewGameOnlyResetsATableNoOtherMapIsOnAndARestoreDoesNotReplay() {
        assertTrue(GlobalStates.resets(MapLogic.LoadType.NEW_GAME, false));
        assertFalse(GlobalStates.resets(MapLogic.LoadType.NEW_GAME, true), "starting map B must not wipe map A's globals");
        assertFalse(GlobalStates.resets(MapLogic.LoadType.TRANSITION, false));
        assertFalse(GlobalStates.resets(null, false));
        assertTrue(GlobalStates.replays(MapLogic.LoadType.NEW_GAME));
        assertTrue(GlobalStates.replays(MapLogic.LoadType.TRANSITION));
        assertFalse(GlobalStates.replays(null), "a restored map keeps its saved counters");
    }

    @Test void mathRemapMapsRangesAndHonoursItsFlags() {
        String[] common = {"in1", "1", "in2", "10", "out1", "0.1", "out2", "1.0"};
        MapLogic logic = run(
            entity("math_remap", concat(common, "targetname", "plain", "OutValue", "seen,SetValue,,0,-1")),
            entity("math_remap", concat(common, "targetname", "ignore", "spawnflags", "1", "OutValue", "seen2,SetValue,,0,-1")),
            entity("math_remap", concat(common, "targetname", "clamp", "spawnflags", "2", "OutValue", "seen3,SetValue,,0,-1")),
            entity("math_counter", "targetname", "seen"), entity("math_counter", "targetname", "seen2"), entity("math_counter", "targetname", "seen3"));
        logic.queue(0, "plain", null, "InValue", "5.5", null, null);
        logic.queue(0, "plain", null, "InValue", "19", null, null);
        logic.queue(0, "ignore", null, "InValue", "19", null, null);
        logic.queue(0, "clamp", null, "InValue", "19", null, null);
        seconds(logic, 0.1);
        // 0.1 + (19 - 1) * 0.9 / 9 = 1.9, passed on unclamped; the counter keeps what it was last given.
        assertTrue(state(logic, "seen").startsWith("1.9"), state(logic, "seen"));
        assertTrue(state(logic, "seen2").startsWith("0 "), "out-of-range input dropped: " + state(logic, "seen2"));
        assertTrue(state(logic, "seen3").startsWith("1 "), "output clamped to out2: " + state(logic, "seen3"));
        logic.queue(0, "plain", null, "Disable", null, null, null);
        logic.queue(0, "plain", null, "InValue", "1", null, null);
        seconds(logic, 0.1);
        assertTrue(state(logic, "seen").startsWith("1.9"), "disabled: no output");
    }

    private static String[] concat(String[] a, String... b) {
        String[] out = new String[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    @Test void autosaveAndAchievementInputsAreAccepted() {
        MapLogic logic = run(
            entity("logic_autosave", "targetname", "save"),
            entity("logic_achievement", "targetname", "ach", "AchievementEvent", "ACHIEVEMENT_EVENT_X", "OnFired", "hits,Add,1,0,-1"),
            entity("logic_achievement", "targetname", "noevent", "OnFired", "hits,Add,10,0,-1"),
            entity("math_counter", "targetname", "hits"));
        logic.queue(0, "save", null, "Save", null, null, null);
        logic.queue(0, "save", null, "SaveDangerous", "5", null, null);
        logic.queue(0, "save", null, "SetMinHitpointsThreshold", "30", null, null);
        logic.queue(0, "ach", null, "FireEvent", null, null, null);
        logic.queue(0, "noevent", null, "FireEvent", null, null, null);
        logic.queue(0, "ach", null, "Disable", null, null, null);
        logic.queue(0.01, "ach", null, "FireEvent", null, null, null);
        seconds(logic, 0.2);
        assertTrue(state(logic, "hits").startsWith("1 "), state(logic, "hits"));
        assertFalse(logic.status().contains("logic_autosave"), "no unhandled input: " + logic.status());
    }

    @Test void aTrainRunsFromCornerToCornerAndWaits() {
        // 320 units a second is 10 blocks a second; the second corner makes it 20 on leaving, and waits 1 s.
        MapLogic logic = run(
            trainBrush(entity("func_train", "targetname", "t", "target", "c1", "speed", "320")),
            at(entity("path_corner", "targetname", "c1", "target", "c2"), 10, 1, 1),
            at(entity("path_corner", "targetname", "c2", "target", "c3", "wait", "1", "speed", "640", "OnPass", "passes,Add,1,0,-1"), 20, 1, 1),
            at(entity("path_corner", "targetname", "c3", "OnPass", "ends,Add,1,0,-1"), 20, 1, 11),
            entity("math_counter", "targetname", "passes"), entity("math_counter", "targetname", "ends"));
        LogicEntity train = logic.findFirst("t", null, null, null);
        seconds(logic, 0.5);
        // On its first corner, the centre of its bounds (1, 1, 1) there: moved by (9, 0, 0); a named train waits.
        MoverPose rest = train.pose(logic.time());
        assertEquals(9, rest.x(), 1e-9);
        assertEquals(0, rest.z(), 1e-9);
        assertFalse(train.moving(logic.time()));
        logic.queue(0, "t", null, "Start", null, null, null);
        seconds(logic, 0.55);
        assertEquals(9 + 5, train.pose(logic.time()).x(), 0.6, "half way along the first leg after half a second");
        assertTrue(train.moving(logic.time()));
        seconds(logic, 0.6);
        assertTrue(state(logic, "passes").startsWith("1 "), "OnPass of the corner it arrived at: " + state(logic, "passes"));
        assertEquals(19, train.pose(logic.time()).x(), 1e-6, "waiting at the second corner");
        assertFalse(train.moving(logic.time()));
        seconds(logic, 1.2);
        // The leg to the third corner is 10 blocks at the second corner's 20 blocks a second: half a second.
        assertTrue(train.pose(logic.time()).z() > 0, "set out again after the wait");
        seconds(logic, 1.0);
        assertEquals(10, train.pose(logic.time()).z(), 1e-6);
        assertTrue(state(logic, "ends").startsWith("1 "), state(logic, "ends"));
        assertFalse(train.moving(logic.time()));
    }

    @Test void aTrainStoppedMidLegResumesToTheSameCorner() {
        MapLogic logic = run(
            trainBrush(entity("func_train", "targetname", "t", "target", "c1", "speed", "320")),
            at(entity("path_corner", "targetname", "c1", "target", "c2"), 1, 1, 1),
            at(entity("path_corner", "targetname", "c2", "OnPass", "passes,Add,1,0,-1"), 21, 1, 1),
            entity("math_counter", "targetname", "passes"));
        LogicEntity train = logic.findFirst("t", null, null, null);
        seconds(logic, 0.1);
        logic.queue(0, "t", null, "Toggle", null, null, null);
        seconds(logic, 0.55);
        logic.queue(0, "t", null, "Toggle", null, null, null);
        seconds(logic, 0.1);
        double stopped = train.pose(logic.time()).x();
        assertTrue(stopped > 4 && stopped < 8, "stopped part way: " + stopped);
        seconds(logic, 0.5);
        assertEquals(stopped, train.pose(logic.time()).x(), 1e-9, "stays where it stopped");
        logic.queue(0, "t", null, "Toggle", null, null, null);
        seconds(logic, 2.0);
        assertEquals(20, train.pose(logic.time()).x(), 1e-6);
        assertTrue(state(logic, "passes").startsWith("1 "), state(logic, "passes"));
        // Saved and restored, it stands where it was.
        MapLogic loaded = run(
            trainBrush(entity("func_train", "targetname", "t", "target", "c1", "speed", "320")),
            at(entity("path_corner", "targetname", "c1", "target", "c2"), 1, 1, 1),
            at(entity("path_corner", "targetname", "c2", "OnPass", "passes,Add,1,0,-1"), 21, 1, 1),
            entity("math_counter", "targetname", "passes"));
        assertTrue(loaded.load(logic.save()));
        assertEquals(20, loaded.findFirst("t", null, null, null).pose(loaded.time()).x(), 1e-6);
    }
}
