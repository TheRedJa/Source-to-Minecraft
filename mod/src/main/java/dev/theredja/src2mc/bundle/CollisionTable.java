package dev.theredja.src2mc.bundle;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * What each map-local cell collides as, where that is not the block's default (format.md
 * section 14). A map block with no entry is a full cube; a carrier with no entry is nothing.
 *
 * Shapes are boxes in sixteenths of the cell that carries them. A box may reach one cell out,
 * -16 to 32: a floor that sits a little way up into the air cell above its block hangs off that
 * block, so breaking the block takes the floor's collision with it.
 *
 * Looked up on the physics hot path, so a cell is one hash probe of a packed key; the table is
 * immutable once decoded.
 */
public final class CollisionTable {
    public static final byte[] MAGIC = {'S', '2', 'C', 'O', 'L', 'L', 0, 0};
    public static final int VERSION = 1;
    public static final int STEPS = 16;
    /** Map-local coordinates fit 21 signed bits per axis in the packed key. */
    private static final int COORDINATE_LIMIT = 1 << 20;

    private final byte[][] shapes;
    private final Long2IntOpenHashMap cells;

    private CollisionTable(byte[][] shapes, Long2IntOpenHashMap cells) {
        this.shapes = shapes;
        this.cells = cells;
        this.cells.defaultReturnValue(-1);
    }

    /** The shape index of a map-local cell, or -1 where the block keeps its default. */
    public int shapeAt(int x, int y, int z) {
        if (Math.abs(x) >= COORDINATE_LIMIT || Math.abs(y) >= COORDINATE_LIMIT || Math.abs(z) >= COORDINATE_LIMIT) return -1;
        return cells.get(key(x, y, z));
    }

    public int shapeCount() { return shapes.length; }

    /** Receives one map-local cell and its shape index. */
    @FunctionalInterface public interface CellVisitor { void visit(int x, int y, int z, int shape); }

    /** Visits every cell with an entry, in no particular order. */
    public void forEachCell(CellVisitor visitor) {
        for (var entry : cells.long2IntEntrySet()) {
            long key = entry.getLongKey();
            visitor.visit((int) (key << 1 >> 43), (int) (key << 22 >> 43), (int) (key << 43 >> 43), entry.getIntValue());
        }
    }

    public int cellCount() { return cells.size(); }

    /** Boxes of one shape: six signed sixteenths per box, x1 y1 z1 x2 y2 z2. */
    public byte[] boxes(int shape) { return shapes[shape].clone(); }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x1FFFFFL) << 42 | ((long) y & 0x1FFFFFL) << 21 | ((long) z & 0x1FFFFFL);
    }

    /** Decode and validate one {@code collision.s2coll}. */
    public static CollisionTable decode(byte[] bytes, long maxShapes, long maxSections) throws BundleValidationException {
        ByteBuffer in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        need(in, MAGIC.length + 4);
        byte[] magic = new byte[MAGIC.length];
        in.get(magic);
        if (!Arrays.equals(magic, MAGIC)) fail(BundleErrorCode.INVALID_SCHEMA, "invalid collision magic");
        long version = Integer.toUnsignedLong(in.getInt());
        if (version != VERSION) fail(BundleErrorCode.UNSUPPORTED_VERSION, "unsupported collision version " + version);

        need(in, 4);
        long shapeCount = Integer.toUnsignedLong(in.getInt());
        if (shapeCount > maxShapes) fail(BundleErrorCode.LIMIT_EXCEEDED, "collision shape count exceeds limit");
        // Every shape takes at least its two-byte box count.
        need(in, shapeCount * 2);
        byte[][] shapes = new byte[(int) shapeCount][];
        for (int s = 0; s < shapeCount; s++) {
            need(in, 2);
            int boxes = Short.toUnsignedInt(in.getShort());
            need(in, boxes * 6L);
            byte[] data = new byte[boxes * 6];
            in.get(data);
            for (int b = 0; b < boxes; b++) {
                for (int axis = 0; axis < 3; axis++) {
                    int low = data[b * 6 + axis], high = data[b * 6 + axis + 3];
                    if (low >= high || low < -STEPS || high > 2 * STEPS) {
                        fail(BundleErrorCode.INVALID_SCHEMA, "collision box is empty or reaches past a neighbouring cell");
                    }
                }
            }
            if (s > 0 && compareShapes(shapes[s - 1], data) >= 0) {
                fail(BundleErrorCode.DUPLICATE_IDENTITY, "collision shapes are not uniquely sorted");
            }
            shapes[s] = data;
        }

        need(in, 4);
        long sections = Integer.toUnsignedLong(in.getInt());
        if (sections > maxSections) fail(BundleErrorCode.LIMIT_EXCEEDED, "collision section count exceeds limit");
        need(in, sections * 14);
        Long2IntOpenHashMap cells = new Long2IntOpenHashMap();
        int[] previous = null;
        for (long i = 0; i < sections; i++) {
            need(in, 14);
            int[] at = {in.getInt(), in.getInt(), in.getInt()};
            if (previous != null && compare(previous, at) >= 0) {
                fail(BundleErrorCode.DUPLICATE_IDENTITY, "collision sections are not uniquely sorted");
            }
            previous = at;
            for (int axis = 0; axis < 3; axis++) {
                if (Math.abs((long) at[axis] * 16) >= COORDINATE_LIMIT) fail(BundleErrorCode.INVALID_SCHEMA, "collision section out of range");
            }
            int count = Short.toUnsignedInt(in.getShort());
            if (count == 0) fail(BundleErrorCode.INVALID_SCHEMA, "collision section has no cells");
            need(in, count * 6L);
            int prior = -1;
            for (int c = 0; c < count; c++) {
                int local = Short.toUnsignedInt(in.getShort());
                long shape = Integer.toUnsignedLong(in.getInt());
                if (local >= 4096 || local <= prior) fail(BundleErrorCode.INVALID_SCHEMA, "collision cells are not uniquely sorted");
                if (shape >= shapeCount) fail(BundleErrorCode.INVALID_REFERENCE, "collision shape index out of range");
                prior = local;
                int x = at[0] * 16 + (local & 15), y = at[1] * 16 + (local >> 8 & 15), z = at[2] * 16 + (local >> 4 & 15);
                cells.put(key(x, y, z), (int) shape);
            }
        }
        if (in.hasRemaining()) fail(BundleErrorCode.INVALID_SCHEMA, "trailing collision bytes");
        cells.trim();
        return new CollisionTable(shapes, cells);
    }

    /** The converter's order: box by box, each box by its signed bytes, a prefix first. */
    private static int compareShapes(byte[] a, byte[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int c = Byte.compare(a[i], b[i]);
            if (c != 0) return c;
        }
        return Integer.compare(a.length, b.length);
    }

    private static int compare(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) {
            int c = Integer.compare(a[i], b[i]);
            if (c != 0) return c;
        }
        return 0;
    }

    private static void need(ByteBuffer in, long bytes) throws BundleValidationException {
        if (bytes < 0 || bytes > in.remaining()) fail(BundleErrorCode.INVALID_SCHEMA, "truncated collision table");
    }

    private static void fail(BundleErrorCode code, String message) throws BundleValidationException {
        throw new BundleValidationException(code, message);
    }
}
