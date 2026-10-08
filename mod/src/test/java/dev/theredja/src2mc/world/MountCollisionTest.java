package dev.theredja.src2mc.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.theredja.src2mc.bundle.CollisionTable;
import dev.theredja.src2mc.bundle.LogicPropTable;
import dev.theredja.src2mc.bundle.MoverTable;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

final class MountCollisionTest {
    /** A placed prop's table: a lower slab in map-local cells (0, 0, 0) and (1, 0, 0). */
    private static CollisionTable slabs() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{'S', '2', 'C', 'O', 'L', 'L', 0, 0});
        u32(out, 1);
        u32(out, 1);
        u16(out, 1);
        out.writeBytes(new byte[]{0, 0, 0, 16, 8, 16});
        u32(out, 1);
        u32(out, 0); u32(out, 0); u32(out, 0);
        u16(out, 2);
        u16(out, 0); u32(out, 0);
        u16(out, 1); u32(out, 0);
        return CollisionTable.decode(out.toByteArray(), 1_000, 1_000);
    }

    private static void u16(ByteArrayOutputStream out, int v) { out.write(v & 255); out.write(v >> 8 & 255); }

    private static void u32(ByteArrayOutputStream out, long v) {
        out.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((int) v).array());
    }

    private static MoverTable.Mover carrier() {
        return new MoverTable.Mover(9, "func_tracktrain", new int[]{3, 0, 1}, new int[]{1, 1, 1}, null, null,
            new int[0], new int[]{0, 0, 0}, List.of());
    }

    private static LogicPropTable.Prop prop(CollisionTable table) {
        return new LogicPropTable.Prop(4, "00".repeat(32), -1, new int[]{0}, 0, false, table, -1, List.of());
    }

    /** A half turn about up and a whole-block move turn and move each box exactly into the carrier's cells. */
    @Test void aHalfTurnMovesBoxesExactly() throws Exception {
        CollisionTable table = slabs();
        var mount = new PropMounts.Mount(4, 4, 9, 0, 1, 0, 0, 10, 2, 4);
        var moved = MountCollision.moved(null, prop(table), table, mount, carrier());
        assertEquals(2, moved.size());
        byte[] slab = {0, 0, 0, 16, 8, 16};
        assertArrayEquals(slab, moved.get(BlockPos.asLong(6, 2, 2)).toByteArray());
        assertArrayEquals(slab, moved.get(BlockPos.asLong(5, 2, 2)).toByteArray());
    }

    /** A move of half a block cuts each box along the cell border it now crosses. */
    @Test void aHalfBlockMoveSplitsBoxesAtCellBorders() throws Exception {
        CollisionTable table = slabs();
        var mount = new PropMounts.Mount(4, 4, 9, 0, 0, 0, 1, 3.5, 0, 1);
        var moved = MountCollision.moved(null, prop(table), table, mount, carrier());
        assertArrayEquals(new byte[]{8, 0, 0, 16, 8, 16}, moved.get(BlockPos.asLong(0, 0, 0)).toByteArray());
        assertArrayEquals(new byte[]{0, 0, 0, 8, 8, 16, 8, 0, 0, 16, 8, 16}, moved.get(BlockPos.asLong(1, 0, 0)).toByteArray());
        assertArrayEquals(new byte[]{0, 0, 0, 8, 8, 16}, moved.get(BlockPos.asLong(2, 0, 0)).toByteArray());
    }

    /** Any other turn is covered by the box around the turned one. */
    @Test void anOddTurnIsCoveredByTheBoxAroundIt() throws Exception {
        CollisionTable table = slabs();
        double half = Math.sin(Math.PI / 8);
        var mount = new PropMounts.Mount(4, 4, 9, 0, half, 0, Math.cos(Math.PI / 8), 3, 0, 1);
        var moved = MountCollision.moved(null, prop(table), table, mount, carrier());
        // The slabs, 2 by 1 blocks, turned 45 degrees about up span 0..2.12 along x, -1.41..0.71 along z;
        // each cell's box is covered on its own.
        assertEquals(true, moved.containsKey(BlockPos.asLong(0, 0, -2)));
        assertEquals(true, moved.containsKey(BlockPos.asLong(2, 0, -1)));
        for (long cell : moved.keySet()) {
            assertEquals(true, BlockPos.getX(cell) >= 0 && BlockPos.getX(cell) <= 2 && BlockPos.getZ(cell) >= -2 && BlockPos.getZ(cell) <= 0,
                BlockPos.of(cell).toString());
        }
    }
}
