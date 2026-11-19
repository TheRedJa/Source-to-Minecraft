package dev.theredja.src2mc.logic;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class PlayerEntitiesTest {
    @Test void sourceAxesMapToMinecraftOnes() {
        // Source (x, y, z) is Minecraft (x, z, -y), 32 units a block.
        assertArrayEquals(new double[]{1, 2, -3}, PlayerEntities.vector("32 96 64"), 1e-9);
        Vec3 east = PlayerEntities.direction(0, 0), north = PlayerEntities.direction(0, 90), down = PlayerEntities.direction(90, 0);
        assertEquals(1, east.x, 1e-9);
        assertEquals(-1, north.z, 1e-9);
        assertEquals(-1, down.y, 1e-9);
    }

    @Test void sourceYawIsMinecraftYawMinusNinety() {
        assertEquals(-90f, PlayerEntities.yaw(0), 1e-4);
        assertEquals(180f, Math.abs(PlayerEntities.yaw(90)), 1e-4);
    }

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

    private static LogicTable.Entity teleport(String target) {
        return new LogicTable.Entity("point_teleport", List.of(new String[]{"classname", "point_teleport"}, new String[]{"targetname", "tp"},
            new String[]{"target", target}, new String[]{"spawnflags", "1"}), List.of(), new double[]{0, 0, 0}, -1, -1);
    }

    @Test void aTeleportToTheSpawnPoseOfThePlayerWaitsForThemInsteadOfRemovingItself() {
        // pointteleport.cpp:73-110: "!player" is the player; nobody is inside as the map spawns, so it must stay.
        MapLogic logic = run(teleport("!player"));
        assertFalse(logic.findFirst("tp", null, null, null) == null, "kept for the player to arrive");
        // A named target that is not there is still removed, as Source does.
        assertTrue(run(teleport("nobody")).findFirst("tp", null, null, null) == null);
    }

    @Test void speedValuesAreForgottenWhenTheServerStops() {
        PlayerEntities.SpeedMod.CURRENT.put(UUID.randomUUID(), 0.5);
        PlayerEntities.SpeedMod.clear();
        assertTrue(PlayerEntities.SpeedMod.CURRENT.isEmpty());
    }
}
