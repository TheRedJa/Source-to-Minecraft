package dev.theredja.src2mc.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.theredja.src2mc.bundle.BundleMap;
import dev.theredja.src2mc.bundle.LogicTable;
import dev.theredja.src2mc.bundle.SurfaceTable;
import dev.theredja.src2mc.world.MapPlacement;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

/** env_message, env_hudhint, env_zoom, env_starfield and env_credits against the SDK's rules, run server-less. */
final class ScreenHudEntitiesTest {
    private static final MapPlacement PLACEMENT = new MapPlacement("c", "m", BlockPos.ZERO, BlockPos.ZERO, BlockPos.ZERO, new BlockPos(15, 15, 15));

    private static LogicTable.Entity entity(String classname, String... pairs) {
        List<String[]> keyvalues = new ArrayList<>();
        keyvalues.add(new String[]{"classname", classname});
        for (int i = 0; i + 1 < pairs.length; i += 2) keyvalues.add(new String[]{pairs[i], pairs[i + 1]});
        return new LogicTable.Entity(classname, keyvalues, List.of(), null, -1, -1);
    }

    private static MapLogic map(Map<String, String> strings, List<LogicTable.Title> titles, LogicTable.Credits credits) {
        LogicTable table = new LogicTable(new double[]{0, 0, 0}, List.of(entity("worldspawn")), List.of(), List.of(), Map.of(), strings,
            List.of(), List.of(), titles, credits);
        BundleMap bundle = new BundleMap("m", "m.bsp", new int[]{0, 0, 0}, new int[]{15, 15, 15}, new int[]{0, 0, 0},
            List.of(), List.of(), List.of(), false, new SurfaceTable(List.of(), Map.of()), java.util.Set.of(), null, null, null, null, null, table);
        MapLogic logic = new MapLogic(PLACEMENT, bundle);
        logic.spawn(MapLogic.LoadType.NEW_GAME);
        return logic;
    }

    private static <T extends LogicEntity> T spawned(T entity) {
        entity.spawn();
        return entity;
    }

    @Test void messageSendsItsTitleThroughHudTextAndDropsAnUnknownOne() {
        LogicTable.Title title = new LogicTable.Title("GAMEOVER", -1, 0.3f, 1, 0xFFFF8000, 0x80102030, 0.5f, 1.5f, 4, 0.25f, "Line one\nLine two");
        MapLogic logic = map(Map.of(), List.of(title), null);
        // Title names are matched without case and without the leading #.
        var shown = spawned(new ScreenEffects.Message(logic, 1, entity("env_message", "message", "#gameover", "spawnflags", "2")));
        shown.accept("showmessage", null, null, null);
        var missing = spawned(new ScreenEffects.Message(logic, 2, entity("env_message", "message", "nothing_here")));
        missing.accept("showmessage", null, null, null);
        List<ScreenEffects.Sent> sent = ScreenEffects.drain(logic);
        assertEquals(1, sent.size(), "a message the titles lack is dropped silently");
        var text = (ScreenEffects.TextPayload) sent.get(0).payload();
        assertEquals(ScreenEffects.CHANNEL_HUDTEXT, text.channel());
        assertEquals("Line one\nLine two", text.text());
        assertEquals(0xFF8000FF, text.color1(), "0xAARRGGBB becomes 0xRRGGBBAA");
        assertEquals(0x10203080, text.color2());
        assertEquals(-1f, text.x());
        assertEquals(4f, text.holdtime());
        assertEquals(1, text.effect());
    }

    @Test void messageWithTheOnceFlagRemovesItselfAfterFiring() {
        LogicTable.Title title = new LogicTable.Title("T", 0, 0, 0, 0, 0, 0, 0, 1, 0, "x");
        MapLogic logic = map(Map.of(), List.of(title), null);
        var message = spawned(new ScreenEffects.Message(logic, 1, entity("env_message", "message", "T", "spawnflags", "1")));
        message.accept("showmessage", null, null, null);
        assertTrue(message.removed);
    }

    @Test void hudHintLooksUpItsTokenAndHidesAnUnresolvedPlaceholder() {
        MapLogic logic = map(Map.of("valve_hint_jump", "To jump, press %+jump%."), List.of(), null);
        var hint = new ScreenEffects.HudHint(logic, 1, entity("env_hudhint", "message", "#Valve_Hint_Jump"));
        assertEquals("To jump, press %+jump%.", hint.text());
        assertEquals("", new ScreenEffects.HudHint(logic, 2, entity("env_hudhint", "message", "#Valve_Hint_Missing")).text(),
            "SetHintText refuses to show a localization placeholder");
        assertEquals("Plain words", new ScreenEffects.HudHint(logic, 3, entity("env_hudhint", "message", "Plain words")).text());
        hint.accept("hidehudhint", null, null, null);
        hint.accept("showhudhint", null, null, null);
        List<ScreenEffects.Sent> sent = ScreenEffects.drain(logic);
        assertEquals("", ((ScreenEffects.HudHintPayload) sent.get(0).payload()).text());
        assertEquals("To jump, press %+jump%.", ((ScreenEffects.HudHintPayload) sent.get(1).payload()).text());
    }

    @Test void zoomIsHeldByItsEntityAndAnotherEntitysUnzoomIsRefused() {
        MapLogic logic = map(Map.of(), List.of(), null);
        var near = spawned(new ScreenEffects.Zoom(logic, 1, entity("env_zoom", "fov", "20", "rate", "2")));
        var other = spawned(new ScreenEffects.Zoom(logic, 2, entity("env_zoom", "fov", "50", "rate", "1")));
        near.accept("zoom", null, null, null);
        other.accept("unzoom", null, null, null);
        List<ScreenEffects.Sent> sent = ScreenEffects.drain(logic);
        assertEquals(1, sent.size(), "SetFOV from an entity that is not the owner is refused without the override flag");
        var zoom = (ScreenEffects.ZoomPayload) sent.get(0).payload();
        assertEquals(20f, zoom.fov());
        assertEquals(2f, zoom.rate());
        near.accept("unzoom", null, null, null);
        var back = (ScreenEffects.ZoomPayload) ScreenEffects.drain(logic).get(0).payload();
        assertEquals(0f, back.fov());
        assertEquals(0f, back.rate(), "UnZoom is instant");
        // After an unzoom nobody holds it, so another entity may zoom and unzoom.
        other.accept("zoom", null, null, null);
        other.accept("unzoom", null, null, null);
        assertEquals(2, ScreenEffects.drain(logic).size());
    }

    @Test void starfieldStartsOffAtDensityOneAndSendsEachChange() {
        MapLogic logic = map(Map.of(), List.of(), null);
        var field = spawned(new ScreenEffects.Starfield(logic, 1, entity("env_starfield")));
        field.accept("turnon", null, null, null);
        field.accept("setdensity", "2.5", null, null);
        field.accept("turnoff", null, null, null);
        List<ScreenEffects.Sent> sent = ScreenEffects.drain(logic);
        var first = (ScreenEffects.StarfieldPayload) sent.get(0).payload();
        assertTrue(first.on());
        assertEquals(1f, first.density());
        assertEquals(2.5f, ((ScreenEffects.StarfieldPayload) sent.get(1).payload()).density());
        assertTrue(!((ScreenEffects.StarfieldPayload) sent.get(2).payload()).on());
    }

    private static LogicTable.Credits credits() {
        return new LogicTable.Credits(
            Map.of("logo", "HALF-LIFE'", "scrolltime", "158", "separation", "10", "color", "255 255 255 128", "posx", "96", "posy", "360",
                "fadeintime", "2.5", "fadeouttime", "1", "fadeholdtime", "1.0"),
            List.of(new LogicTable.CreditName("V", "WeaponIcons"), new LogicTable.CreditName("A Name", "CreditsText")),
            List.of(new LogicTable.CreditName("First", "CreditsOutroText"), new LogicTable.CreditName("Last", "CreditsOutroText")),
            null);
    }

    @Test void creditsSendTheirNamesAndTheOutroFiresItsOutputWhenItIsOver() {
        MapLogic logic = map(Map.of(), List.of(), credits());
        var roll = spawned(new ScreenEffects.Credits(logic, 1, entity("env_credits")));
        roll.accept("setlogolength", "7", null, null);
        roll.accept("showlogo", null, null, null);
        roll.accept("rollcredits", null, null, null);
        roll.accept("rolloutrocredits", null, null, null);
        List<ScreenEffects.Sent> sent = ScreenEffects.drain(logic);
        var logo = (ScreenEffects.CreditsPayload) sent.get(0).payload();
        assertEquals(ScreenEffects.CreditsPayload.LOGO, logo.kind());
        assertEquals(7f, logo.logoLength());
        assertEquals("HALF-LIFE'", logo.params().logo());
        assertEquals(158f, logo.params().scrollTime());
        assertEquals(0xFFFFFF80, logo.params().color());
        var intro = (ScreenEffects.CreditsPayload) sent.get(1).payload();
        assertEquals(2, intro.names().size());
        assertEquals("WeaponIcons", intro.names().get(0).font());
        var outro = (ScreenEffects.CreditsPayload) sent.get(2).payload();
        assertEquals("Last", outro.names().get(1).name());
        // Two 19-pixel names: 538 pixels to go in 158 s, the last 278 pixels from where it stops, then 10 s and 192 / 316 s of fade.
        assertEquals(92.2, roll.nextThink - logic.time(), 0.1);
        assertEquals(ScreenEffects.outroSeconds(outro.params(), outro.names()), roll.nextThink - logic.time(), 1e-9);
    }

    @Test void aRestoredOutroIsRolledAgainAndTheLongLogoTextIsClamped() {
        MapLogic logic = map(Map.of(), List.of(), credits());
        var roll = spawned(new ScreenEffects.Credits(logic, 1, entity("env_credits")));
        roll.accept("rolloutrocredits", null, null, null);
        ScreenEffects.drain(logic);
        net.minecraft.nbt.CompoundTag saved = new net.minecraft.nbt.CompoundTag();
        roll.save(saved);
        // CCredits::OnRestore: the client forgot the outro, so the next think sends it again and sets when it is over.
        var restored = spawned(new ScreenEffects.Credits(logic, 2, entity("env_credits")));
        restored.load(saved);
        restored.think();
        List<ScreenEffects.Sent> sent = ScreenEffects.drain(logic);
        assertEquals(1, sent.size());
        var outro = (ScreenEffects.CreditsPayload) sent.get(0).payload();
        assertEquals(ScreenEffects.CreditsPayload.OUTRO, outro.kind());
        assertEquals(ScreenEffects.outroSeconds(outro.params(), outro.names()), restored.nextThink - logic.time(), 1e-9);
        assertEquals(256, ScreenEffects.CreditsParams.read(Map.of("logo", "x".repeat(300))).logo().length());
    }

    @Test void creditsWithNoNamesNeverFinish() {
        assertTrue(Double.isNaN(ScreenEffects.outroSeconds(ScreenEffects.CreditsParams.read(Map.of()), List.of())));
    }

    @Test void creditsAndOtherScreenPayloadsSurviveTheWire() {
        var params = ScreenEffects.CreditsParams.read(credits().params());
        var credits = new ScreenEffects.CreditsPayload(3, 0, params, List.of(new ScreenEffects.CreditsName("Name", "CreditsOutroText")), new byte[]{1, 2, 3});
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        ScreenEffects.CreditsPayload.STREAM_CODEC.encode(buffer, credits);
        var read = ScreenEffects.CreditsPayload.STREAM_CODEC.decode(buffer);
        assertEquals(credits.params(), read.params());
        assertEquals(credits.names(), read.names());
        assertEquals(3, read.logo().length);
        buffer = new FriendlyByteBuf(Unpooled.buffer());
        ScreenEffects.ZoomPayload.STREAM_CODEC.encode(buffer, new ScreenEffects.ZoomPayload(30, 1.5f));
        assertEquals(new ScreenEffects.ZoomPayload(30, 1.5f), ScreenEffects.ZoomPayload.STREAM_CODEC.decode(buffer));
        buffer = new FriendlyByteBuf(Unpooled.buffer());
        ScreenEffects.HudHintPayload.STREAM_CODEC.encode(buffer, new ScreenEffects.HudHintPayload("hint %+use%"));
        assertEquals("hint %+use%", ScreenEffects.HudHintPayload.STREAM_CODEC.decode(buffer).text());
        buffer = new FriendlyByteBuf(Unpooled.buffer());
        ScreenEffects.StarfieldPayload.STREAM_CODEC.encode(buffer, new ScreenEffects.StarfieldPayload(true, 3));
        assertEquals(new ScreenEffects.StarfieldPayload(true, 3), ScreenEffects.StarfieldPayload.STREAM_CODEC.decode(buffer));
    }
}
