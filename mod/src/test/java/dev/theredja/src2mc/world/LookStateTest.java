package dev.theredja.src2mc.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.theredja.src2mc.bundle.LogicTable;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class LookStateTest {
    private static LogicTable.Entity entity(String classname, String... pairs) {
        List<String[]> keyvalues = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) keyvalues.add(new String[]{pairs[i], pairs[i + 1]});
        return new LogicTable.Entity(classname, keyvalues, List.of(), new double[]{64, 32, 0}, -1, -1);
    }

    private static LogicTable table(LogicTable.Entity... entities) {
        return new LogicTable(new double[]{0, 0, 0}, List.of(entities), List.of(), List.of(), Map.of());
    }

    @Test
    void theFirstControllerLeadsUnlessALaterOneIsTheMaster() {
        LookState.View view = LookState.initial(table(
            entity("env_fog_controller", "fogenable", "1"),
            entity("env_tonemap_controller"),
            entity("env_fog_controller", "spawnflags", "1"),
            entity("env_fog_controller"),
            entity("env_tonemap_controller")));
        assertEquals(2, view.masterFog());
        assertEquals(1, view.masterTonemap());
        assertEquals(3, view.fogs().size());
        assertEquals(2, view.tonemaps().size());
        assertEquals(-1, view.tonemapInUse());
    }

    @Test
    void aFogControllerSpawnsWithItsKeys() {
        LookState.View view = LookState.initial(table(entity("env_fog_controller",
            "fogenable", "1", "fogblend", "1", "fogcolor", "255 128 0", "fogcolor2", "0 0 255",
            "fogstart", "256", "fogend", "1024.5", "fogmaxdensity", ".8", "foglerptime", "2", "fogdir", "1 0 0")));
        LookState.Fog fog = view.fogs().get(0);
        assertTrue(fog.enable());
        assertTrue(fog.blend());
        assertEquals(0xFF8000, fog.color());
        assertEquals(0x0000FF, fog.color2());
        assertEquals(256f, fog.start());
        assertEquals(1024.5f, fog.end());
        assertEquals(0.8f, fog.maxDensity(), 1e-6);
        assertEquals(2f, fog.duration());
        // Source +X is Minecraft +X.
        assertEquals(1f, fog.dirX());
    }

    @Test
    void useAnglesPointsTheFogAgainstTheEntitysForward() {
        LookState.Fog fog = LookState.initial(table(entity("env_fog_controller",
            "use_angles", "1", "angles", "0 90 0"))).fogs().get(0);
        // Yaw 90 faces Source +Y, which is Minecraft -Z; the fog direction is the opposite.
        assertEquals(0f, fog.dirX(), 1e-6);
        assertEquals(1f, fog.dirZ(), 1e-6);
    }

    @Test
    void aColorCorrectionStartsAtFullWeightUnlessDisabled() {
        LookState.View view = LookState.initial(table(
            entity("color_correction", "filename", "scripts/cc.raw", "minfalloff", "128", "maxfalloff", "512"),
            entity("color_correction", "filename", "scripts/off.raw", "StartDisabled", "1")));
        assertEquals(1f, view.corrections().get(0).weight());
        assertTrue(view.corrections().get(0).enabled());
        assertEquals(128f, view.corrections().get(0).minFalloff());
        assertEquals(0f, view.corrections().get(1).weight());
        assertEquals(64, view.corrections().get(0).x());
    }

    @Test
    void theViewSurvivesTheWire() {
        LookState.View view = LookState.initial(table(
            entity("env_fog_controller", "fogenable", "1", "fogcolor", "10 20 30", "fogend", "900"),
            entity("env_tonemap_controller"),
            entity("color_correction", "filename", "scripts/cc.raw")));
        LookState.View withPlayer = new LookState.View(view.tonemaps(), 1, 0.5f, view.fogs(), view.masterFog(), view.masterTonemap(),
            view.corrections(), Map.of(new UUID(1, 2), new LookState.Player(0, 1)));
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        LookState.write(buf, withPlayer);
        assertEquals(withPlayer, LookState.read(buf));
        assertEquals(0, buf.readableBytes());
    }
}
