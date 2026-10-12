package dev.theredja.src2mc.bundle;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.junit.jupiter.api.Test;

final class SunVisibilityTableTest {
    private static byte[] file(int samples, int props, int trailing) {
        ByteBuffer out = ByteBuffer.allocate(8 + 4 + 4 + samples + 4 + props * 16 + trailing).order(ByteOrder.LITTLE_ENDIAN);
        out.put(new byte[] {'S', '2', 'S', 'U', 'N', 0, 0, 0}).putInt(1).putInt(samples);
        for (int i = 0; i < samples; i++) out.put((byte) (i == 0 ? 255 : 128));
        out.putInt(props);
        for (int i = 0; i < props; i++) {
            out.putFloat(1 + i).putFloat(2).putFloat(3).put((byte) (i * 100)).put((byte) 0).put((byte) 0).put((byte) 0);
        }
        return out.array();
    }

    @Test
    void readsSamplesAndPropRecords() {
        SunVisibilityTable table = SunVisibilityTable.parse(file(3, 2, 0));
        assertEquals(3, table.sampleCount());
        assertEquals(1f, table.sample(0));
        assertEquals(128 / 255f, table.sample(2), 1e-6f);
        assertEquals(2, table.propCount());
        assertArrayEquals(new float[] {2, 2, 3}, table.propOrigin(1));
        assertEquals(100 / 255f, table.prop(1), 1e-6f);
        assertNull(table.propOrigin(2));
        assertEquals(0f, table.prop(-1));
    }

    @Test
    void acceptsAnEmptyTable() {
        assertEquals(0, SunVisibilityTable.parse(file(0, 0, 0)).sampleCount());
    }

    @Test
    void rejectsMalformedFiles() {
        assertThrows(IllegalArgumentException.class, () -> SunVisibilityTable.parse(file(2, 1, 1)));
        assertThrows(IllegalArgumentException.class, () -> SunVisibilityTable.parse(new byte[4]));
        byte[] bad = file(1, 1, 0);
        bad[0] = 'X';
        assertThrows(IllegalArgumentException.class, () -> SunVisibilityTable.parse(bad));
        byte[] version = file(1, 1, 0);
        version[8] = 2;
        assertThrows(IllegalArgumentException.class, () -> SunVisibilityTable.parse(version));
        byte[] padding = file(1, 1, 0);
        padding[padding.length - 1] = 1;
        assertThrows(IllegalArgumentException.class, () -> SunVisibilityTable.parse(padding));
        byte[] huge = file(1, 1, 0);
        huge[12] = (byte) 0xff; huge[13] = (byte) 0xff; huge[14] = (byte) 0xff; huge[15] = 0x7f;
        assertThrows(IllegalArgumentException.class, () -> SunVisibilityTable.parse(huge));
    }

    @Test
    void ambientLookupBlendsTheSampleVisibilityLikeTheCube() {
        // Two samples in the leaf in front of the plane x = 0, at x 1 and 3 blocks; the middle weighs them equally.
        LightTable light = new LightTable(List.of(), 0, new float[] {1, 0, 0, 0}, new int[] {-1, -2}, new int[] {0, 0}, new int[] {2, 0},
            new float[] {1, 0, 0, 3, 0, 0}, new float[36], new byte[0][]);
        SunVisibilityTable sun = new SunVisibilityTable(new byte[] {(byte) 255, 0}, new float[0], new byte[0]);
        float[] cube = new float[18], vis = new float[1];
        assertEquals(true, light.ambientAt(2, 0, 0, cube, sun, vis));
        assertEquals(0.5f, vis[0], 1e-5f);
        light.ambientAt(1, 0, 0, cube, sun, vis);
        assertEquals(1f, vis[0], 0.01f);
        light.ambientAt(2, 0, 0, cube, null, vis);
        assertEquals(0f, vis[0]);
    }

    /** A v2 file: 2 samples, 1 prop, 3 world lights; lists given sample 0, sample 1, prop. */
    private static byte[] v2(int lightCount, int[]... lists) {
        ByteBuffer out = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
        out.put(new byte[] {'S', '2', 'S', 'U', 'N', 0, 0, 0}).putInt(2).putInt(lightCount).putInt(2).put((byte) 255).put((byte) 0);
        out.putInt(1).putFloat(1).putFloat(2).putFloat(3).put((byte) 7).put((byte) 0).put((byte) 0).put((byte) 0);
        for (int[] list : lists) {
            out.putShort((short) list.length);
            for (int light : list) out.putShort((short) light);
        }
        return java.util.Arrays.copyOf(out.array(), out.position());
    }

    @Test
    void readsVersion2VisibilityLists() {
        SunVisibilityTable table = SunVisibilityTable.parse(v2(3, new int[] {0, 2}, new int[0], new int[] {1, 2}));
        assertEquals(true, table.hasLists());
        assertEquals(3, table.lightCount());
        assertEquals(true, table.listContains(0, 0));
        assertEquals(false, table.listContains(0, 1));
        assertEquals(true, table.listContains(0, 2));
        assertEquals(false, table.listContains(1, 0));
        assertEquals(2, table.propList(0));
        assertEquals(-1, table.propList(1));
        assertEquals(true, table.listContains(table.propList(0), 1));
        assertEquals(false, table.listContains(table.propList(0), 0));
        assertEquals(7 / 255f, table.prop(0), 1e-6f);
    }

    @Test
    void version1HasNoLists() {
        SunVisibilityTable table = SunVisibilityTable.parse(file(2, 1, 0));
        assertEquals(false, table.hasLists());
        assertEquals(false, table.listContains(0, 0));
    }

    @Test
    void rejectsMalformedVersion2Lists() {
        assertThrows(IllegalArgumentException.class, () -> SunVisibilityTable.parse(v2(3, new int[] {3}, new int[0], new int[0])));
        assertThrows(IllegalArgumentException.class, () -> SunVisibilityTable.parse(v2(3, new int[] {2, 1}, new int[0], new int[0])));
        assertThrows(IllegalArgumentException.class, () -> SunVisibilityTable.parse(v2(3, new int[] {1, 1}, new int[0], new int[0])));
        // Too few lists, and a trailing list.
        assertThrows(IllegalArgumentException.class, () -> SunVisibilityTable.parse(v2(3, new int[0], new int[0])));
        assertThrows(IllegalArgumentException.class, () -> SunVisibilityTable.parse(v2(3, new int[0], new int[0], new int[0], new int[0])));
        // A count that runs past the file.
        byte[] cut = v2(3, new int[0], new int[0], new int[] {1});
        assertThrows(IllegalArgumentException.class, () -> SunVisibilityTable.parse(java.util.Arrays.copyOf(cut, cut.length - 2)));
    }

    @Test
    void ambientLookupReportsNormalisedSampleWeights() {
        LightTable light = new LightTable(List.of(), 0, new float[] {1, 0, 0, 0}, new int[] {-1, -2}, new int[] {0, 0}, new int[] {2, 0},
            new float[] {1, 0, 0, 3, 0, 0}, new float[36], new byte[0][]);
        LightTable.SampleWeights weights = new LightTable.SampleWeights();
        light.ambientAt(2, 0, 0, new float[18], null, null, weights);
        assertEquals(2, weights.count());
        assertEquals(0, weights.index(0));
        assertEquals(0.5f, weights.weight(0), 1e-5f);
        assertEquals(1f, weights.weight(0) + weights.weight(1), 1e-5f);
    }
}
