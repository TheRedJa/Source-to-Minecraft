package dev.theredja.src2mc.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.theredja.src2mc.bundle.BundleMap;
import dev.theredja.src2mc.bundle.LogicTable;
import dev.theredja.src2mc.bundle.SurfaceTable;
import dev.theredja.src2mc.view.ViewState;
import dev.theredja.src2mc.world.MapPlacement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class DynamicLightTest {
    private static MapLogic run(LogicTable.Entity... entities) {
        List<LogicTable.Entity> list = new ArrayList<>(List.of(new LogicTable.Entity("worldspawn", List.<String[]>of(new String[]{"classname", "worldspawn"}), List.of(), null, -1, -1)));
        list.addAll(List.of(entities));
        LogicTable table = new LogicTable(new double[]{0, 0, 0}, list, List.of(), List.of(), Map.of());
        BundleMap map = new BundleMap("m", "m.bsp", new int[]{0, 0, 0}, new int[]{15, 15, 15}, new int[]{0, 0, 0},
            List.of(), List.of(), List.of(), false, new SurfaceTable(List.of(), Map.of()), java.util.Set.of(), null, null, null, null, null, table);
        MapLogic logic = new MapLogic(new MapPlacement("c", "m", BlockPos.ZERO, BlockPos.ZERO, BlockPos.ZERO, new BlockPos(15, 15, 15)), map);
        logic.spawn(MapLogic.LoadType.NEW_GAME);
        return logic;
    }

    /** d1_trainstation_01's light_start, its target 2 blocks east, and a light that starts with nothing to light. */
    private static MapLogic intro() {
        return run(
            new LogicTable.Entity("light_dynamic", List.of(new String[]{"classname", "light_dynamic"}, new String[]{"targetname", "light"},
                new String[]{"target", "head"}, new String[]{"_light", "79 232 198 220"}, new String[]{"brightness", "1"},
                new String[]{"distance", "220"}, new String[]{"spotlight_radius", "128"}, new String[]{"_cone", "45"},
                new String[]{"_inner_cone", "30"}, new String[]{"angles", "0 44 0"}, new String[]{"spawnflags", "0"}), List.of(),
                new double[]{1, 1, 1}, -1, -1),
            new LogicTable.Entity("info_target", List.of(new String[]{"classname", "info_target"}, new String[]{"targetname", "head"}),
                List.of(), new double[]{3, 1, 1}, -1, -1));
    }

    private static DynamicLight light(MapLogic logic) { return (DynamicLight) logic.findFirst("light", null, null, null); }

    @Test void colourIsTheLightBytesTimesTwoToTheBrightnessAndPointsAtItsTarget() {
        ViewState.Light state = light(intro()).light();
        assertNotNull(state);
        // TexLightToLinear: c / 255 * 2^exponent.
        assertEquals(79 * 2 / 255f, state.red(), 1e-5);
        assertEquals(232 * 2 / 255f, state.green(), 1e-5);
        // The target is east (+x): the target overrides the angles (DynamicLightThink).
        assertEquals(1, state.dirX(), 1e-6);
        assertEquals(0, state.dirY(), 1e-6);
        assertEquals(220 / 32f, state.radius(), 1e-5);
        assertEquals(4f, state.spotRadius(), 1e-5);
        assertEquals(45f, state.outer(), 1e-5);
    }

    @Test void turnOffLightsNothingAndTurnOnRestoresTheSpawnFlags() {
        MapLogic logic = intro();
        DynamicLight light = light(logic);
        light.input("TurnOff", "", null, null);
        assertNull(light.light());
        light.input("Toggle", "", null, null);
        assertEquals(0, light.light().flags());
        light.input("distance", "64", null, null);
        assertEquals(2f, light.light().radius(), 1e-5);
    }

    @Test void theViewStateCarriesTheLitLights() {
        MapLogic logic = intro();
        assertEquals(1, logic.view(0).lights().size());
        light(logic).input("TurnOff", "", null, null);
        assertEquals(0, logic.view(0).lights().size());
    }
}
