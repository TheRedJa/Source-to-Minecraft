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
}
