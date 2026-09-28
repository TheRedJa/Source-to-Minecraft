package dev.theredja.src2mc.bundle;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

final class CollisionTableTest {
    /** Little-endian writer for hand-built tables, laid out as src/output/cell_collision.rs writes them. */
    private static final class Out {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Out raw(int... values) { for (int v : values) bytes.write(v); return this; }
        Out u16(int v) { return raw(v & 255, v >> 8 & 255); }
        Out u32(long v) { ByteBuffer b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((int) v); bytes.writeBytes(b.array()); return this; }
        byte[] done() { return bytes.toByteArray(); }
    }

    private static Out header() { return new Out().raw('S', '2', 'C', 'O', 'L', 'L', 0, 0).u32(1); }

    private static CollisionTable decode(byte[] bytes) throws BundleValidationException {
        return CollisionTable.decode(bytes, 1_000, 1_000);
    }

    @Test void cellsResolveToTheirShapeAndOthersKeepTheDefault() throws Exception {
        byte[] bytes = header()
            .u32(2)
            .u16(0)                                    // shape 0: nothing
            .u16(1).raw(0, 0, 0, 16, 8, 16)            // shape 1: lower slab
            .u32(2)
            .u32(0).u32(0).u32(0).u16(2).u16(0).u32(1).u16(5).u32(1)
            .u32(2).u32(-1).u32(0).u16(1).u16((3 << 8) | (2 << 4) | 8).u32(0)
            .done();
        CollisionTable table = decode(bytes);
        assertEquals(2, table.shapeCount());
        assertEquals(3, table.cellCount());
        assertEquals(1, table.shapeAt(0, 0, 0));
        assertEquals(1, table.shapeAt(5, 0, 0));
        assertEquals(-1, table.shapeAt(1, 0, 0));
        // Section (2, -1, 0), local x 8 y 3 z 2.
        assertEquals(0, table.shapeAt(40, -13, 2));
        assertArrayEquals(new byte[] {0, 0, 0, 16, 8, 16}, table.boxes(1));
    }

    @Test void boxesMayReachOneCellOutButNoFurther() throws Exception {
        byte[] reaching = header().u32(1).u16(1).raw(0, 0xF0, 0, 16, 32, 16).u32(0).done();
        assertArrayEquals(new byte[] {0, -16, 0, 16, 32, 16}, decode(reaching).boxes(0));
        byte[] tooFar = header().u32(1).u16(1).raw(0, 0, 0, 16, 33, 16).u32(0).done();
        assertThrows(BundleValidationException.class, () -> decode(tooFar));
        byte[] empty = header().u32(1).u16(1).raw(4, 0, 0, 4, 16, 16).u32(0).done();
        assertThrows(BundleValidationException.class, () -> decode(empty));
    }

    @Test void unsortedOrDanglingDataIsRefused() {
        byte[] unsortedShapes = header().u32(2).u16(1).raw(0, 0, 0, 16, 8, 16).u16(0).u32(0).done();
        assertThrows(BundleValidationException.class, () -> decode(unsortedShapes));
        byte[] danglingShape = header().u32(1).u16(0).u32(1).u32(0).u32(0).u32(0).u16(1).u16(0).u32(1).done();
        assertThrows(BundleValidationException.class, () -> decode(danglingShape));
        byte[] trailing = header().u32(0).u32(0).raw(0).done();
        assertThrows(BundleValidationException.class, () -> decode(trailing));
        byte[] truncated = header().u32(3).done();
        assertThrows(BundleValidationException.class, () -> decode(truncated));
    }
}
