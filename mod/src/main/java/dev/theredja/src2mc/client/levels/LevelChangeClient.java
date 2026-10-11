package dev.theredja.src2mc.client.levels;

import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.levels.LevelNetwork;
import dev.theredja.src2mc.levels.LevelTransition;
import dev.theredja.src2mc.client.render.MapSurfaceRenderer;
import dev.theredja.src2mc.client.render.PropRenderer;
import net.minecraft.client.Minecraft;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorPresets;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Client side of the one-world-per-map level changes: on a change it closes the current
 * singleplayer world and opens (or creates, as a void world) the one holding the next map, keeping
 * {@link SourceLoadingScreen} up until the new server reports the map placed.
 */
@EventBusSubscriber(value = Dist.CLIENT, modid = Src2mc.MOD_ID)
public final class LevelChangeClient {
    private record Awaiting(String campaignId, String mapId) {}

    private static Awaiting awaiting;
    /** True from the moment the world switch starts until the new world's player has joined. */
    private static boolean switching;
    private static String failure;
    /** Placement progress 0..1 reported by the new world's server. */
    private static float progress;
    /** When the new world's player joined, or 0; with {@link #heard} it detects a server that never answers. */
    private static long loginNanos;
    private static boolean heard;
    /** A Loaded message that arrived while vanilla's level-receiving screen was still up; applied once it is gone. */
    private static LevelNetwork.LoadedPayload deferredLoaded;

    /** Nanotime when the server reported the map placed and the client began waiting for it to become visible, or 0. */
    private static long finishingSince;
    /** Consecutive client ticks with nothing pending. */
    private static int readyTicks;
    /** Largest pending count seen while finishing, the denominator of the second progress half. */
    private static int peakPending;
    private static float readiness;
    private static String lastPending = "";

    /** Longest the screen waits for the map to become visible before closing anyway. */
    private static final long READY_TIMEOUT_NANOS = 45_000_000_000L;
    /** Ticks (0.5 s) with nothing pending that count as ready. */
    private static final int READY_STABLE_TICKS = 10;

    /** A new world that says nothing for this long after the player joined has failed. */
    private static final long SERVER_SILENCE_NANOS = 60_000_000_000L;

    private LevelChangeClient() {}

    /** Server told us to change worlds: show the loading screen and switch on the next tick. */
    public static void onChange(LevelNetwork.ChangePayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (switching) return; // a switch is already under way; the trigger keeps firing while the player stands in it
        awaiting = new Awaiting(payload.campaignId(), payload.mapId());
        failure = null;
        progress = 0f;
        loginNanos = 0;
        heard = false;
        deferredLoaded = null;
        finishingSince = 0;
        switching = true;
        minecraft.setScreen(new SourceLoadingScreen(payload.mapId(), null));
        minecraft.tell(() -> switchWorld(minecraft, awaiting));
    }

    /** Placement progress from the new world's server. */
    public static void onProgress(LevelNetwork.ProgressPayload payload) {
        heard = true;
        progress = Math.max(0f, Math.min(1f, payload.progress()));
    }

    /** Placement progress 0..1 for the loading screen. */
    static float progress() {
        if (finishingSince == 0) return 0.5f * progress;
        // A map already placed in this world has no placement half: client loading fills the whole bar.
        return progress > 0f ? 0.5f + 0.5f * readiness : readiness;
    }

    /** True from the moment a level switch starts until the new world's player has joined; used by the experimental-warning mixin. */
    public static boolean switching() { return switching; }

    /** The new world's server finished placing the map (or failed to). */
    public static void onLoaded(LevelNetwork.LoadedPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        heard = true;
        if (minecraft.screen instanceof ReceivingLevelScreen || minecraft.screen instanceof LevelLoadingScreen) {
            deferredLoaded = payload;
            return;
        }
        applyLoaded(minecraft, payload);
    }

    private static void applyLoaded(Minecraft minecraft, LevelNetwork.LoadedPayload payload) {
        loginNanos = 0;
        if (payload.ok()) {
            if (awaiting != null) dev.theredja.src2mc.client.menu.MenuActions.recordArrival(awaiting.campaignId, awaiting.mapId);
            // Placed on the server; the screen stays until the client has built what is around the player.
            failure = null;
            finishingSince = System.nanoTime();
            readyTicks = 0;
            peakPending = 0;
            readiness = 0f;
            lastPending = "";
            if (!(minecraft.screen instanceof SourceLoadingScreen) && awaiting != null) minecraft.setScreen(new SourceLoadingScreen(awaiting.mapId, null));
        } else {
            showFailure(minecraft, payload.message());
        }
    }

    /** Back button after a failure: leaves the loading screen, the player stays in the world. */
    static void dismiss() {
        Minecraft minecraft = Minecraft.getInstance();
        clear();
        minecraft.setScreen(minecraft.level == null ? new TitleScreen() : null);
    }

    /** Forgets the switch, including the server-side transition that never got taken. */
    private static void clear() {
        awaiting = null;
        failure = null;
        switching = false;
        loginNanos = 0;
        heard = false;
        deferredLoaded = null;
        finishingSince = 0;
        LevelTransition.take();
    }

    private static void showFailure(Minecraft minecraft, String message) {
        dev.theredja.src2mc.client.menu.ScreenFade.clear();
        failure = message == null || message.isEmpty() ? "Level change failed." : message;
        if (minecraft.screen instanceof SourceLoadingScreen screen) screen.fail(failure);
        else minecraft.setScreen(new SourceLoadingScreen(awaiting == null ? "" : awaiting.mapId, failure));
    }

    /**
     * Vanilla order (PauseScreen.onDisconnect "Save and Quit to Title"): leave the old world completely, then
     * open the new one with no level loaded. Runs on the render thread; Minecraft.disconnect itself waits for the
     * old integrated server to stop, so nothing else may run in between.
     */
    private static void switchWorld(Minecraft minecraft, Awaiting target) {
        if (target == null) return;
        String folder = LevelTransition.folderName(target.campaignId, target.mapId);
        try {
            if (minecraft.level != null) {
                Src2mc.LOGGER.info("level switch: disconnecting");
                boolean local = minecraft.isLocalServer();
                minecraft.level.disconnect();
                if (local) minecraft.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")));
                else minecraft.disconnect();
                Src2mc.LOGGER.info("level switch: disconnected");
            }
            minecraft.setScreen(new SourceLoadingScreen(target.mapId, null));
            var source = minecraft.getLevelSource();
            Path path = source.getLevelPath(folder);
            var flows = minecraft.createWorldOpenFlows();
            if (hasLevelData(path)) {
                Src2mc.LOGGER.info("level switch: opening {}", folder);
                flows.openWorld(folder, () -> { switching = false; LevelTransition.take(); showFailure(minecraft, "Could not open world " + folder + "."); });
                return;
            }
            if (Files.exists(path)) {
                if (!isLeftover(path)) {
                    fail(minecraft, "World folder " + path + " exists without level.dat and has unexpected contents; not touching it.");
                    return;
                }
                Src2mc.LOGGER.info("level switch: removing half-created folder {}", path);
                Files.deleteIfExists(path.resolve("session.lock"));
                Files.deleteIfExists(path.resolve("datapacks"));
                Files.delete(path);
            }
            Src2mc.LOGGER.info("level switch: creating {}", folder);
            flows.createFreshLevel(folder, freshSettings(target), new WorldOptions(System.nanoTime(), false, false),
                LevelChangeClient::voidDimensions, new SourceLoadingScreen(target.mapId, "Could not create world " + folder + "."));
        } catch (IOException | RuntimeException e) {
            Src2mc.LOGGER.error("level switch failed", e);
            fail(minecraft, "Level change failed: " + e);
        }
    }

    private static void fail(Minecraft minecraft, String message) {
        switching = false;
        LevelTransition.take();
        showFailure(minecraft, message);
    }

    private static boolean hasLevelData(Path folder) {
        return Files.isRegularFile(folder.resolve("level.dat")) || Files.isRegularFile(folder.resolve("level.dat_old"));
    }

    /** True when the folder holds nothing but session.lock and an empty datapacks directory. */
    private static boolean isLeftover(Path folder) throws IOException {
        try (var entries = Files.list(folder)) {
            return entries.allMatch(entry -> {
                String name = entry.getFileName().toString();
                if (name.equals("session.lock")) return Files.isRegularFile(entry);
                if (!name.equals("datapacks") || !Files.isDirectory(entry)) return false;
                try (var inner = Files.list(entry)) { return inner.findAny().isEmpty(); } catch (IOException e) { return false; }
            });
        }
    }

    /** Creative/survival as the old world's player had it, no day, weather or mob changes. */
    private static LevelSettings freshSettings(Awaiting target) {
        LevelTransition.Pending pending = LevelTransition.peek();
        GameType type = pending != null && pending.player() != null && pending.player().contains("playerGameType")
            ? GameType.byId(pending.player().getInt("playerGameType")) : GameType.SURVIVAL;
        GameRules rules = new GameRules();
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        return new LevelSettings(target.campaignId + " " + target.mapId, type, false, Difficulty.NORMAL, true, rules,
            WorldDataConfiguration.DEFAULT);
    }

    /** Vanilla normal dimensions with the overworld generator replaced by the vanilla "the_void" flat preset's. */
    private static WorldDimensions voidDimensions(net.minecraft.core.RegistryAccess access) {
        var settings = access.registryOrThrow(Registries.FLAT_LEVEL_GENERATOR_PRESET)
            .getHolderOrThrow(FlatLevelGeneratorPresets.THE_VOID).value().settings();
        return WorldPresets.createNormalWorldDimensions(access).replaceOverworldGenerator(access, new FlatLevelSource(settings));
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (deferredLoaded != null && !(minecraft.screen instanceof ReceivingLevelScreen || minecraft.screen instanceof LevelLoadingScreen)) {
            LevelNetwork.LoadedPayload payload = deferredLoaded;
            deferredLoaded = null;
            applyLoaded(minecraft, payload);
            return;
        }
        if (finishingSince != 0) {
            tickFinishing(minecraft);
            return;
        }
        if (awaiting != null && !switching && failure == null && !heard && loginNanos != 0
            && System.nanoTime() - loginNanos > SERVER_SILENCE_NANOS) {
            loginNanos = 0;
            LevelTransition.take();
            showFailure(minecraft, "The new world did not report back. It may not be the world made for " + awaiting.mapId() + ".");
            return;
        }
        if (awaiting == null || switching || failure != null || minecraft.level == null || minecraft.screen != null) return;
        minecraft.setScreen(new SourceLoadingScreen(awaiting.mapId, null));
    }

    /**
     * Waits for the surface and prop renderers to have nothing left to build or upload around the player
     * ({@link MapSurfaceRenderer#pendingLoads}, {@link PropRenderer#pendingLoads}); both are driven by the world
     * render pass, which keeps running under a non-pause screen. Gives up after {@link #READY_TIMEOUT_NANOS}.
     */
    private static void tickFinishing(Minecraft minecraft) {
        if (minecraft.level == null) return;
        if (!(minecraft.screen instanceof SourceLoadingScreen) && !(minecraft.screen instanceof ReceivingLevelScreen)
            && !(minecraft.screen instanceof LevelLoadingScreen) && awaiting != null) {
            minecraft.setScreen(new SourceLoadingScreen(awaiting.mapId, null));
        }
        int surfaces = MapSurfaceRenderer.pendingLoads(minecraft.level);
        int props = PropRenderer.pendingLoads(minecraft.level);
        long waited = System.nanoTime() - finishingSince;
        boolean known = surfaces >= 0 && props >= 0;
        int pending = known ? surfaces + props : -1;
        lastPending = "surface regions+atlas pages=" + (surfaces < 0 ? "no render pass yet" : surfaces)
            + ", props=" + (props < 0 ? "no render pass yet" : props);
        if (known) {
            peakPending = Math.max(peakPending, pending);
            // Work keeps arriving while builds run (new roots, light), so the raw fraction goes back
            // and forth; the bar only moves forward.
            readiness = Math.max(readiness, peakPending == 0 ? 1f : 1f - (float) pending / peakPending);
            readyTicks = pending == 0 ? readyTicks + 1 : 0;
        } else {
            readyTicks = 0;
        }
        boolean ready = readyTicks >= READY_STABLE_TICKS;
        boolean timedOut = waited >= READY_TIMEOUT_NANOS;
        if (!ready && !timedOut) return;
        if (ready) Src2mc.LOGGER.info("level switch: map visually ready {} ms after placement ({} peak pending)", waited / 1_000_000L, peakPending);
        else Src2mc.LOGGER.warn("level switch: map still not ready after {} s, closing the loading screen anyway; pending: {}", waited / 1_000_000_000L, lastPending);
        finishingSince = 0;
        awaiting = null;
        failure = null;
        minecraft.setScreen(null);
        // INFRA fades in after a load: black for infra_transition_fade_in_hold_time, then infra_transition_fade_in_time.
        dev.theredja.src2mc.client.menu.ScreenFade.fadeIn();
        // The server holds the map's clock (logic, chapter title) until this: the screen is closed now.
        if (minecraft.getConnection() != null) net.neoforged.neoforge.network.PacketDistributor.sendToServer(new LevelNetwork.ReadyPayload());
    }

    @SubscribeEvent
    static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        switching = false;
        loginNanos = System.nanoTime();
        heard = false;
    }

    /** Going back to a menu while no switch is mid-way abandons it, so the loading screen cannot come back over another world. */
    @SubscribeEvent
    static void onScreenOpening(ScreenEvent.Opening event) {
        if (switching || awaiting == null) return;
        if (event.getNewScreen() instanceof TitleScreen || event.getNewScreen() instanceof SelectWorldScreen) clear();
    }

    @SubscribeEvent
    static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        if (!switching) { awaiting = null; failure = null; finishingSince = 0; }
    }
}
