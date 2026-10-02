package dev.theredja.src2mc.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.theredja.src2mc.bundle.BundleMap;
import dev.theredja.src2mc.bundle.BundleModel;
import dev.theredja.src2mc.bundle.BundleProp;
import dev.theredja.src2mc.bundle.SurfaceTable;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class PropGroundTest {
    private static BundleMap map(List<BundleProp> props) {
        // A crate one block on a side, centred on its origin's X and Z, standing on it.
        BundleModel crate = new BundleModel("c".repeat(64), "models/crate.mdl", new int[]{0}, "wood_crate",
            new float[]{-0.5F, 0, -0.5F, 0.5F, 1, 0.5F});
        return new BundleMap("m", "m.bsp", new int[]{0, 0, 0}, new int[]{15, 15, 15}, new int[]{0, 0, 0},
            List.of(), List.of(crate), props, false, new SurfaceTable(List.of(), Map.of()), java.util.Set.of(), null, null, null, null);
    }

    private static BundleProp prop(double x, double y, double z, double[] rotation, double scale) {
        return new BundleProp("p", 0, new int[]{(int) x, (int) y, (int) z}, new double[]{x, y, z}, rotation, scale);
    }

    @Test void findsTheTopOfAPropUnderTheFeet() {
        PropGround ground = PropGround.of(map(List.of(prop(4, 2, 4, new double[]{0, 0, 0, 1}, 1))));
        PropGround.Standing standing = ground.under(4.2, 3.0, 3.8);
        assertEquals(3.0, standing.top(), 1e-9);
        assertEquals("wood_crate", standing.surfaceProp());
        assertNull(ground.under(5.6, 3.0, 4), "beside it");
        assertNull(ground.under(4, 4.5, 4), "well above it");
    }

    @Test void turnedAndScaledPropsCoverTheirTurnedBox() {
        double half = Math.sqrt(0.5);
        // Half a turn about Z puts the crate upside down: it now reaches down from its origin.
        PropGround flipped = PropGround.of(map(List.of(prop(4, 2, 4, new double[]{0, 0, 1, 0}, 2))));
        assertEquals(2.0, flipped.under(4, 2, 4).top(), 1e-9);
        // A quarter turn about Y keeps the top and widens nothing for a square crate.
        PropGround turned = PropGround.of(map(List.of(prop(4, 2, 4, new double[]{0, half, 0, half}, 1))));
        assertEquals(3.0, turned.under(4.45, 3, 4.45).top(), 1e-9);
    }
}
