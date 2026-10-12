package dev.theredja.src2mc.bundle;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

final class WorldLightTableTest {
    private static byte[] file(int count, int trailing) {
        ByteBuffer out = ByteBuffer.allocate(16 + count * 68 + trailing).order(ByteOrder.LITTLE_ENDIAN);
        out.put(new byte[] {'S', '2', 'W', 'L', 'I', 'T', 0, 0}).putInt(1).putInt(count);
        for (int i = 0; i < count; i++) {
            out.put((byte) 2).put((byte) 3).putShort((short) 33);
            float[] v = {1, 2, 3, 4, 5, 6, 0, -1, 0, 0.9f, 0.5f, 2, 10, 0, 0, 0.25f};
            for (float f : v) out.putFloat(f);
        }
        return out.array();
    }

    @Test
    void readsARecord() {
        WorldLightTable.Light light = WorldLightTable.parse(file(2, 0)).lights().get(1);
        assertEquals(WorldLightTable.TYPE_SPOT, light.type());
        assertEquals(3, light.flags());
        assertEquals(33, light.style());
        assertArrayEquals(new float[] {1, 2, 3}, light.origin());
        assertArrayEquals(new float[] {4, 5, 6}, light.intensity());
        assertArrayEquals(new float[] {0, -1, 0}, light.direction());
        assertEquals(10f, light.radius());
        assertEquals(0.25f, light.quadratic());
    }

    @Test
    void attenuationAndConeFollowTheFormulas() {
        WorldLightTable.Light light = WorldLightTable.parse(file(1, 0)).lights().get(0);
        assertEquals(1f / (0.25f * 4f), light.attenuation(2f), 1e-6f);
        assertEquals(0f, light.attenuation(11f));
        assertEquals(1f, light.spotFactor(1f));
        assertEquals(0f, light.spotFactor(0.4f));
        assertEquals(0.25f, light.spotFactor(0.7f), 1e-5f); // ((0.7-0.5)/0.4)^2
    }

    @Test
    void rejectsMalformedFiles() {
        assertThrows(IllegalArgumentException.class, () -> WorldLightTable.parse(file(1, 1)));
        assertThrows(IllegalArgumentException.class, () -> WorldLightTable.parse(new byte[4]));
        byte[] bad = file(1, 0);
        bad[0] = 'X';
        assertThrows(IllegalArgumentException.class, () -> WorldLightTable.parse(bad));
    }
}
