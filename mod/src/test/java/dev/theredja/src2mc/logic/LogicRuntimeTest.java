package dev.theredja.src2mc.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.theredja.src2mc.bundle.BundleManifest;
import dev.theredja.src2mc.bundle.BundleMap;
import dev.theredja.src2mc.bundle.BundleValidator;
import dev.theredja.src2mc.bundle.LogicTable;
import dev.theredja.src2mc.bundle.SurfaceTable;
import dev.theredja.src2mc.world.MapPlacement;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** The I/O runtime against the SDK's rules, on small hand-made maps; and, given a real bundle, a smoke run of its logic. */
final class LogicRuntimeTest {
    private static final MapPlacement PLACEMENT = new MapPlacement("c", "m", BlockPos.ZERO, BlockPos.ZERO, BlockPos.ZERO, new BlockPos(15, 15, 15));

    /** Builds entities: classname, then key/value pairs, then outputs as "Output:target,input,param,delay,times". */
    private static LogicTable.Entity entity(String classname, String... pairs) {
        List<String[]> keyvalues = new ArrayList<>();
        List<LogicTable.Output> outputs = new ArrayList<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (pairs[i].startsWith("On") || pairs[i].startsWith("Out")) {
                String[] f = pairs[i + 1].split(",", -1);
                outputs.add(new LogicTable.Output(pairs[i], f[0], f[1], f[2], Double.parseDouble(f[3]), Integer.parseInt(f[4])));
            } else {
                keyvalues.add(new String[]{pairs[i], pairs[i + 1]});
            }
        }
        keyvalues.add(0, new String[]{"classname", classname});
        return new LogicTable.Entity(classname, keyvalues, outputs, null, -1, -1);
    }

    private static MapLogic run(LogicTable.Entity... entities) {
        List<LogicTable.Entity> list = new ArrayList<>(List.of(entity("worldspawn")));
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

    private static String state(MapLogic logic, String name) { return logic.findFirst(name, null, null, null).state(); }

    @Test void autoFiresAfterAFifthOfASecondAndDelaysHold() {
        MapLogic logic = run(
            entity("logic_auto", "OnMapSpawn", "relay,Trigger,,0.5,-1", "OnNewGame", "counter,Add,10,0,-1"),
            entity("logic_relay", "targetname", "relay", "OnTrigger", "counter,Add,1,0,-1"),
            entity("math_counter", "targetname", "counter"));
        seconds(logic, 0.15);
        assertTrue(state(logic, "counter").startsWith("0 "));
        seconds(logic, 0.1);
        assertTrue(state(logic, "counter").startsWith("10 "), "OnNewGame at 0.2 s");
        seconds(logic, 0.5);
        assertTrue(state(logic, "counter").startsWith("11 "), "the relay half a second later");
    }

    @Test void connectionsFireInReverseOfTheirOrderAndTimesRunOut() {
        // AddEventAction puts each connection first, so the last listed fires first and the first listed wins.
        MapLogic logic = run(
            entity("logic_relay", "targetname", "relay", "spawnflags", "2",
                "OnTrigger", "counter,SetValue,1,0,-1", "OnTrigger", "counter,SetValue,2,0,-1", "OnTrigger", "once,Add,1,0,1"),
            entity("math_counter", "targetname", "counter"),
            entity("math_counter", "targetname", "once"));
        logic.queue(0, "relay", null, "Trigger", null, null, null);
        logic.queue(0, "relay", null, "Trigger", null, null, null);
        seconds(logic, 0.05);
        assertTrue(state(logic, "counter").startsWith("1 "));
        assertTrue(state(logic, "once").startsWith("1 "), "a times-1 connection fires once");
    }

    @Test void aRelayWaitsOutItsLongestDelayBeforeRefiring() {
        MapLogic logic = run(
            entity("logic_relay", "targetname", "relay", "OnTrigger", "counter,Add,1,1,-1"),
            entity("math_counter", "targetname", "counter"));
        logic.queue(0, "relay", null, "Trigger", null, null, null);
        logic.queue(0.5, "relay", null, "Trigger", null, null, null);
        logic.queue(1.5, "relay", null, "Trigger", null, null, null);
        seconds(logic, 3);
        assertTrue(state(logic, "counter").startsWith("2 "), "the trigger at 0.5 s is ignored");
    }

    @Test void cancelPendingDropsWhatTheRelayQueued() {
        MapLogic logic = run(
            entity("logic_relay", "targetname", "relay", "OnTrigger", "counter,Add,1,1,-1"),
            entity("math_counter", "targetname", "counter"));
        logic.queue(0, "relay", null, "Trigger", null, null, null);
        logic.queue(0.5, "relay", null, "CancelPending", null, null, null);
        seconds(logic, 2);
        assertTrue(state(logic, "counter").startsWith("0 "));
    }

    @Test void branchListenersHearChangesAndCasesMatch() {
        MapLogic logic = run(
            entity("logic_branch", "targetname", "b1"),
            entity("logic_branch", "targetname", "b2"),
            entity("logic_branch_listener", "Branch01", "b1", "Branch02", "b2", "OnAllTrue", "counter,Add,1,0,-1"),
            entity("logic_case", "targetname", "case", "Case01", "door", "Case02", "WINDOW", "OnCase02", "counter,Add,100,0,-1",
                "OnDefault", "counter,Add,1000,0,-1"),
            entity("math_counter", "targetname", "counter"));
        logic.queue(0, "b*", null, "SetValue", "1", null, null);
        logic.queue(0, "case", null, "InValue", "window", null, null);
        logic.queue(0, "case", null, "InValue", "roof", null, null);
        seconds(logic, 0.1);
        assertTrue(state(logic, "counter").startsWith("1101 "), state(logic, "counter"));
    }

    @Test void countersHitTheirLimitOnceAndTimersRepeat() {
        MapLogic logic = run(
            entity("logic_timer", "RefireTime", "1", "OnTimer", "counter,Add,1,0,-1"),
            entity("math_counter", "targetname", "counter", "max", "3", "OnHitMax", "hits,Add,1,0,-1"),
            entity("math_counter", "targetname", "hits"));
        seconds(logic, 5.01);
        assertTrue(state(logic, "counter").startsWith("3 "));
        assertTrue(state(logic, "hits").startsWith("1 "), "OnHitMax once while it stays at the limit");
    }

    @Test void stateSurvivesSavingMidDelay() {
        LogicTable.Entity[] entities = {
            entity("logic_relay", "targetname", "relay", "OnTrigger", "counter,Add,5,2,-1"),
            entity("math_counter", "targetname", "counter")};
        MapLogic logic = run(entities);
        logic.queue(0, "relay", null, "Trigger", null, new PlayerActor(UUID.randomUUID()), null);
        seconds(logic, 1);
        CompoundTag saved = logic.save();
        MapLogic restored = run(entities);
        assertTrue(restored.load(saved));
        seconds(restored, 1.5);
        assertTrue(state(restored, "counter").startsWith("5 "));
    }

    @Test void sourceAnglesTurnTheWaySourceDoes() {
        // Yaw 90 turns Source's forward (+x) to its left (+y), which is Minecraft's north (-z).
        org.joml.Vector3d forward = MoverPose.angles(0, 90, 0).transform(new org.joml.Vector3d(1, 0, 0));
        assertEquals(0, forward.x, 1e-9);
        assertEquals(-1, forward.z, 1e-9);
        // Pitch 90 points forward straight down.
        org.joml.Vector3d down = MoverPose.angles(90, 0, 0).transform(new org.joml.Vector3d(1, 0, 0));
        assertEquals(-1, down.y, 1e-9);
    }

    @Test void aDoorTurnsAtItsSpeedAndReversesFromWhereItIs() {
        MapLogic logic = run(entity("func_door_rotating", "targetname", "door", "distance", "90", "speed", "90", "spawnflags", "0"));
        LogicEntity door = logic.findFirst("door", null, null, null);
        logic.queue(0, "door", null, "Open", null, null, null);
        seconds(logic, 0.5);
        org.joml.Vector3d turned = door.pose(logic.time()).rotation().transform(new org.joml.Vector3d(1, 0, 0));
        // Within a tick: the Open is queued for the next tick.
        assertEquals(Math.cos(Math.toRadians(45)), turned.x, 0.06, "half way after half its travel time");
        assertTrue(door.moving(logic.time()));
        logic.queue(0, "door", null, "Close", null, null, null);
        seconds(logic, 0.3);
        assertTrue(door.moving(logic.time()), "closing from half open takes half the time, not all of it");
        seconds(logic, 0.3);
        assertTrue(!door.moving(logic.time()) && state(logic, "door").startsWith("closed"), state(logic, "door"));
        assertEquals(1, door.pose(logic.time()).rotation().transform(new org.joml.Vector3d(1, 0, 0)).x, 1e-9);
    }

    @Test void aPropDoorTurnsFromItsOwnClosedAnglesTheWaySourceOpensIt() {
        MapLogic logic = run(entity("prop_door_rotating", "targetname", "door", "angles", "0 270 0", "distance", "90", "speed", "100",
            "opendir", "1", "spawnpos", "0"));
        LogicEntity door = logic.findFirst("door", null, null, null);
        // The prop is placed at its closed angles, so closed is no turn at all.
        assertEquals(1, Math.abs(door.pose(logic.time()).rotation().w), 1e-9);
        logic.queue(0, "door", null, "Open", null, null, null);
        seconds(logic, 1.2);
        // Forward opens to the yaw minus the distance: a turn of -90 degrees about up.
        org.joml.Vector3d turned = door.pose(logic.time()).rotation().transform(new org.joml.Vector3d(1, 0, 0));
        org.joml.Vector3d expected = MoverPose.angles(0, -90, 0).transform(new org.joml.Vector3d(1, 0, 0));
        assertEquals(expected.x, turned.x, 1e-6);
        assertEquals(expected.z, turned.z, 1e-6);
        MapLogic back = run(entity("prop_door_rotating", "targetname", "door", "angles", "0 270 0", "distance", "90", "spawnpos", "2"));
        org.joml.Vector3d spawned = back.findFirst("door", null, null, null).pose(back.time()).rotation().transform(new org.joml.Vector3d(1, 0, 0));
        org.joml.Vector3d open = MoverPose.angles(0, 90, 0).transform(new org.joml.Vector3d(1, 0, 0));
        assertEquals(open.z, spawned.z, 1e-6, "spawning open back starts turned by the distance");
    }

    @Test void moverCellsAreJoinedIntoOnePieceSoSableNeverSplitsThem() {
        int[] joined = MoverSystem.connected(new int[]{0, 0, 0, 3, 2, 0, 0, 0, 5});
        java.util.Set<Long> cells = new java.util.HashSet<>();
        for (int i = 0; i < joined.length; i += 3) cells.add(BlockPos.asLong(joined[i], joined[i + 1], joined[i + 2]));
        assertTrue(cells.contains(BlockPos.asLong(3, 2, 0)) && cells.contains(BlockPos.asLong(0, 0, 5)));
        // Every cell reaches the first through face neighbours.
        java.util.ArrayDeque<Long> queue = new java.util.ArrayDeque<>(List.of(BlockPos.asLong(0, 0, 0)));
        java.util.Set<Long> seen = new java.util.HashSet<>(queue);
        while (!queue.isEmpty()) {
            long cell = queue.poll();
            for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
                long next = BlockPos.offset(cell, direction);
                if (cells.contains(next) && seen.add(next)) queue.add(next);
            }
        }
        assertEquals(cells.size(), seen.size());
        assertEquals(3, MoverSystem.connected(new int[0]).length, "an empty mover still gets one block");
    }

    /** With {@code -Dsrc2mc.testBundle}: spawns each map, presses every button it has, and runs it for two minutes. */
    @Test void realMapsRun() throws Exception {
        String path = System.getProperty("src2mc.testBundle");
        Assumptions.assumeTrue(path != null, "set -Dsrc2mc.testBundle to run a real map's logic");
        BundleManifest manifest = new BundleValidator().validate(Path.of(path));
        for (BundleMap map : manifest.maps()) {
            if (map.logic() == null) continue;
            MapLogic logic = new MapLogic(PLACEMENT, map);
            logic.spawn(MapLogic.LoadType.NEW_GAME);
            long started = System.nanoTime();
            seconds(logic, 10);
            PlayerActor player = new PlayerActor(UUID.randomUUID());
            int pressed = 0, sounds = 0;
            for (int i = 0; i < map.logic().entities().size(); i++) {
                if (logic.entity(i) instanceof Movers.Usable usable && usable.usable()) { usable.use(player, true); pressed++; }
            }
            for (int second = 0; second < 120; second++) {
                seconds(logic, 1);
                LogicNetwork.Update update = logic.drainUpdate(false);
                sounds += update.events().size();
            }
            // Every scene, started once: each speak event must reach the clients as a voice line.
            int expected = 0, voices = 0;
            for (int i = 0; i < map.logic().entities().size(); i++) {
                LogicTable.Scene scene = logic.scene(logic.entity(i));
                if (!(logic.entity(i) instanceof SoundEntities.Scene) || scene == null || logic.entity(i).removed) continue;
                for (LogicTable.SceneEvent event : scene.events()) if (event.speaks() && map.audio() != null && map.audio().script(event.script()) != null) expected++;
                logic.queue(0, null, logic.entity(i), "Start", null, player, null);
                seconds(logic, scene.length() + 1);
                for (LogicNetwork.SoundEvent event : logic.drainUpdate(false).events()) if (event.voice()) voices++;
            }
            System.out.println(map.mapId() + ": scene lines " + voices + " of " + expected);
            assertEquals(expected, voices, "every line of every scene plays");
            double millis = (System.nanoTime() - started) / 1e6;
            System.out.println(map.mapId() + ": pressed " + pressed + ", one-shot sounds " + sounds
                + String.format(java.util.Locale.ROOT, ", %.1f ms per simulated second\n", millis / 130) + logic.status());
        }
    }
}
