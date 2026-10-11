package dev.theredja.src2mc.client.menu;

import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.bundle.BundleGeneration;
import dev.theredja.src2mc.client.audio.MenuSounds;
import dev.theredja.src2mc.menu.KvTree;
import dev.theredja.src2mc.menu.MenuNetwork;
import dev.theredja.src2mc.menu.MenuState;
import dev.theredja.src2mc.menu.ScreensData;
import dev.theredja.src2mc.menu.ViewPath;
import dev.theredja.src2mc.network.PlacementNetwork;
import dev.theredja.src2mc.world.MapPlacement;
import dev.theredja.src2mc.world.PropStates;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.options.SoundOptionsScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.client.gui.screens.options.controls.ControlsScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.SelectMusicEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The client half of INFRA's in-world main menu: the menu state each map's server sends ({@link MenuNetwork}),
 * the camera it puts on the {@code point_viewcontrol}'s path, the player held still behind it, the clicks on the
 * panels of its {@code vgui_screen}s and the menu music.
 *
 * <p>While a viewcontrol is enabled Minecraft's own HUD is hidden: Source's HUD is not Minecraft's, and the menu
 * map's {@code drawhud 1} means Source's.
 */
@EventBusSubscriber(modid = Src2mc.MOD_ID, value = Dist.CLIENT)
public final class MenuClient {
    private MenuClient() {}

    private static final Map<PropStates.Key, MenuState> STATES = new HashMap<>();
    private static MenuContext context;
    private static net.minecraft.client.multiplayer.ClientLevel contextLevel;

    /** The vertical field of view the camera used last frame, for the cursor's ray. */
    private static double fovY = 70;
    private static MenuSounds.Handle music;
    private static long musicStarted;
    /** {@code Music.Menu}'s volume. */
    private static final double MUSIC_VOLUME = 0.2;

    // ---- State from the server.

    public static void receive(MenuNetwork.StatePayload payload) {
        PropStates.Key key = new PropStates.Key(payload.dimension(), payload.anchor());
        if (payload.state() == null) STATES.remove(key);
        else STATES.put(key, payload.state());
    }

    /** Forgets everything, for leaving a world. */
    static void clear() {
        STATES.clear();
        context = null;
        contextLevel = null;
        stopMusic();
        VguiImages.clear();
    }

    @SubscribeEvent
    static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) { clear(); }

    /**
     * The menu of the placed map the player is in (else the first with a menu state), or null when none.
     * Rebuilt when the world, the placement or the bundle generation changes; its screens keep their own state until then.
     */
    static MenuContext context() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || STATES.isEmpty()) return null;
        ResourceLocation dimension = minecraft.level.dimension().location();
        BlockPos at = minecraft.player == null ? BlockPos.ZERO : minecraft.player.blockPosition();
        MapPlacement chosen = null;
        for (MapPlacement placement : PlacementNetwork.clientIndex(dimension).view()) {
            if (!STATES.containsKey(new PropStates.Key(dimension, placement.anchorWorld().asLong()))) continue;
            if (chosen == null || placement.contains(at)) chosen = placement;
        }
        if (chosen == null) return null;
        BundleGeneration generation = Src2mc.bundles().active();
        if (context != null && contextLevel == minecraft.level && context.placement.equals(chosen) && context.generation == generation.sequence()) return context;
        var located = generation.findLocatedMap(chosen.campaignId(), chosen.mapId()).orElse(null);
        context = located == null ? null : MenuContext.of(chosen, located.bundle(), located.map(), generation.sequence());
        contextLevel = minecraft.level;
        return context;
    }

    /** The server's menu state for the context's map. */
    static MenuState state(MenuContext context) {
        Minecraft minecraft = Minecraft.getInstance();
        return STATES.get(new PropStates.Key(minecraft.level.dimension().location(), context.anchor()));
    }

    /** The enabled viewcontrol of the map, or null. */
    static MenuState.View activeView(MenuState state) {
        if (state == null) return null;
        for (MenuState.View view : state.views()) if (view.enabled()) return view;
        return null;
    }

    /** Whether a viewcontrol holds the camera and the player. */
    public static boolean viewActive() {
        MenuContext current = context();
        return current != null && activeView(state(current)) != null;
    }

    /** Whether the cursor may act now: a viewcontrol is enabled with its cursor and controls on (not mid-glide). */
    static boolean inputAllowed(MenuState.View view) { return view != null && view.controls() && view.cursor(); }

    // ---- The camera.

    /**
     * The viewcontrol's pose this frame as {@code x, y, z} (world) and Minecraft's {@code yaw, pitch, roll}, or null when
     * no viewcontrol is enabled. Called as the camera is set up, which is before the frame's field of view is asked for.
     */
    public static double[] cameraPose(float partialTick) {
        MenuContext current = context();
        if (current == null) return null;
        MenuState.View view = activeView(state(current));
        if (view == null) return null;
        Minecraft minecraft = Minecraft.getInstance();
        ViewPath.Pose pose = view.pose(minecraft.level.getGameTime() + partialTick);
        fovY = ViewPath.verticalFov(pose.fov());
        BlockPos translation = current.placement.translation();
        return new double[]{pose.x() + translation.getX(), pose.y() + translation.getY(), pose.z() + translation.getZ(),
            ViewPath.minecraftYaw(pose.yaw()), pose.pitch(), pose.roll()};
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void onComputeFov(ViewportEvent.ComputeFov event) {
        if (viewActive()) event.setFOV(fovY);
    }

    static double fovY() { return fovY; }

    // ---- The player behind the camera.

    private static final List<ResourceLocation> HIDDEN_LAYERS = List.of(VanillaGuiLayers.CROSSHAIR, VanillaGuiLayers.HOTBAR,
        VanillaGuiLayers.JUMP_METER, VanillaGuiLayers.EXPERIENCE_BAR, VanillaGuiLayers.PLAYER_HEALTH, VanillaGuiLayers.ARMOR_LEVEL,
        VanillaGuiLayers.FOOD_LEVEL, VanillaGuiLayers.VEHICLE_HEALTH, VanillaGuiLayers.AIR_LEVEL, VanillaGuiLayers.SELECTED_ITEM_NAME,
        VanillaGuiLayers.SPECTATOR_TOOLTIP, VanillaGuiLayers.EXPERIENCE_LEVEL, VanillaGuiLayers.EFFECTS);

    @SubscribeEvent
    static void onGuiLayer(RenderGuiLayerEvent.Pre event) {
        if (HIDDEN_LAYERS.contains(event.getName()) && viewActive()) event.setCanceled(true);
    }

    @SubscribeEvent
    static void onHand(RenderHandEvent event) {
        if (viewActive()) event.setCanceled(true);
    }

    /** FL_FROZEN: no walking, jumping or sneaking while the viewcontrol holds the player. */
    @SubscribeEvent
    static void onMovement(MovementInputUpdateEvent event) {
        if (!viewActive()) return;
        var input = event.getInput();
        input.leftImpulse = 0;
        input.forwardImpulse = 0;
        input.up = input.down = input.left = input.right = false;
        input.jumping = false;
        input.shiftKeyDown = false;
    }

    @SubscribeEvent
    static void onSelectMusic(SelectMusicEvent event) {
        if (viewActive() || music != null) event.setMusic(null);
    }

    // ---- Ticking: the cursor screen and the music.

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        MenuContext current = minecraft.level == null || minecraft.player == null ? null : context();
        tickMusic(current);
        if (current == null) {
            if (minecraft.screen instanceof MenuScreen) minecraft.setScreen(null);
            return;
        }
        MenuState state = state(current);
        MenuState.View view = activeView(state);
        boolean wanted = view != null && view.cursor() && anyInputScreen(current, state);
        tickOptions(minecraft, current, state, view);
        if (wanted && minecraft.screen == null && minecraft.getOverlay() == null) minecraft.setScreen(new MenuScreen());
        else if (!wanted && minecraft.screen instanceof MenuScreen) minecraft.setScreen(null);
    }

    // ---- Options: Minecraft's own screens stand in for INFRA's option lists (user decision 2026-10-11).

    /** The options screen of the phone the camera rests on, while Minecraft's screen for it is open. */
    private static ScreenView optionsShown;

    /**
     * Once the camera rests at a node facing an active {@code infra_menu_options_<kind>} screen, opens Minecraft's
     * screen for that kind; when the player leaves it, runs the phone layout's own {@code back_button} command, which
     * glides the camera back as INFRA's Back does.
     */
    private static void tickOptions(Minecraft minecraft, MenuContext current, MenuState state, MenuState.View view) {
        if (optionsShown != null) {
            if (minecraft.screen != null && !(minecraft.screen instanceof MenuScreen)) return;
            ScreenView phone = optionsShown;
            optionsShown = null;
            String back = backCommand(phone);
            if (back != null) run(current, phone, back);
            return;
        }
        boolean resting = view != null && inputAllowed(view) && !view.moving() && !view.reverse() && view.path().size() > 1;
        if (!resting || minecraft.screen != null && !(minecraft.screen instanceof MenuScreen)) return;
        for (ScreenView screen : current.screens) {
            MenuState.Screen shown = state.screen(screen.entity);
            if (shown == null || !shown.active() || !screen.panelName.startsWith(OPTIONS_PREFIX) || !inView(current, screen)) continue;
            Screen options = optionsScreen(minecraft, screen.panelName.substring(OPTIONS_PREFIX.length()));
            if (options == null) continue;
            optionsShown = screen;
            minecraft.setScreen(options);
            return;
        }
    }

    private static final String OPTIONS_PREFIX = "infra_menu_options_";

    /** Minecraft's counterpart of INFRA's option list {@code kind}; closing it returns to the menu's cursor screen. */
    private static Screen optionsScreen(Minecraft minecraft, String kind) {
        Screen parent = new MenuScreen();
        return switch (kind) {
            case "video" -> new VideoSettingsScreen(parent, minecraft, minecraft.options);
            case "audio" -> new SoundOptionsScreen(parent, minecraft.options);
            case "controls" -> new ControlsScreen(parent, minecraft.options);
            case "game" -> new OptionsScreen(parent, minecraft.options);
            default -> null;
        };
    }

    /** The {@code command} of the block named {@code back_button} anywhere in the screen's current layout file. */
    private static String backCommand(ScreenView screen) {
        KvTree layout = screen.data.layouts().get(KvTree.lower(screen.res()));
        return layout == null ? null : findBack(layout);
    }

    private static String findBack(KvTree tree) {
        for (KvTree.Entry entry : tree.entries()) {
            if (!entry.isBlock()) continue;
            String command = entry.key().equalsIgnoreCase("back_button") ? entry.block().get("command") : null;
            if (command != null && !command.isBlank()) return command;
            String nested = findBack(entry.block());
            if (nested != null) return nested;
        }
        return null;
    }

    /** Whether the centre of {@code screen} lies inside the camera's view cone (half the vertical field of view). */
    private static boolean inView(MenuContext current, ScreenView screen) {
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        BlockPos translation = current.placement.translation();
        double[] eye = toSource(current, camera.getPosition().x - translation.getX(), camera.getPosition().y - translation.getY(),
            camera.getPosition().z - translation.getZ());
        double[] centre = screen.basis.point(screen.pixelsWide / 2.0, screen.pixelsHigh / 2.0, screen.pixelsWide, screen.pixelsHigh);
        var look = camera.getLookVector();
        // Minecraft's direction (a, b, c) is Source's (a, -c, b).
        double[] forward = {look.x(), -look.z(), look.y()};
        double dx = centre[0] - eye[0], dy = centre[1] - eye[1], dz = centre[2] - eye[2];
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1e-6) return true;
        double cos = (dx * forward[0] + dy * forward[1] + dz * forward[2]) / length;
        return cos >= Math.cos(Math.toRadians(fovY) / 2);
    }

    private static boolean anyInputScreen(MenuContext current, MenuState state) {
        for (ScreenView screen : current.screens) {
            MenuState.Screen shown = state.screen(screen.entity);
            if (shown != null && shown.active() && screen.acceptsInput) return true;
        }
        return false;
    }

    /** Music.Menu while the player is in a map with an INFRA main-menu panel: looped, everywhere, at 0.2. */
    private static void tickMusic(MenuContext current) {
        boolean wanted = current != null && current.data.hasMainPanel();
        if (!wanted) {
            stopMusic();
            return;
        }
        ScreensData.Sound sound = current.data.sound("music/menu.wav");
        if (sound == null) return;
        long now = System.nanoTime();
        if (music != null && musicFadeStart != 0) {
            double done = (now - musicFadeStart) / 1e9 / musicFadeSeconds;
            MenuSounds.setLevel(music, (float) (MUSIC_VOLUME * Math.max(0, 1 - done)));
            return;
        }
        // A started sound takes a tick or two to count as playing; do not start it again meanwhile.
        if (music != null && (MenuSounds.active(music) || now - musicStarted < 3_000_000_000L)) return;
        music = MenuSounds.play(current.bundle, current.placement.campaignId() + "/" + current.placement.mapId() + "/" + sound.wave(),
            sound.path(), sound.loopStart(), (float) MUSIC_VOLUME, SoundSource.MUSIC);
        musicStarted = now;
        if (music != null) Minecraft.getInstance().getMusicManager().stopPlaying();
    }

    /** Fades the menu music to silence over {@code seconds}: INFRA's {@code soundfade 100 5 <seconds> 0} (music only here). */
    static void fadeMusic(double seconds) {
        musicFadeStart = System.nanoTime();
        musicFadeSeconds = Math.max(0.001, seconds);
    }

    /** Undoes {@link #fadeMusic} (a start that was abandoned). */
    static void restoreMusic() {
        musicFadeStart = 0;
        MenuSounds.setLevel(music, (float) MUSIC_VOLUME);
    }

    private static long musicFadeStart;
    private static double musicFadeSeconds;

    private static void stopMusic() {
        musicFadeStart = 0;
        if (music != null) MenuSounds.stop(music);
        music = null;
    }

    // ---- The cursor.

    /** What the cursor points at: a screen, the panel point and, if one is there, the control. */
    record Pick(ScreenView screen, VguiLayout.Widget widget, double px, double py, double distance) {}

    /**
     * The nearest shown, front-facing screen that takes input along the ray through the cursor, as the camera
     * of this frame sees it; null when the cursor is over none.
     */
    static Pick pick(double guiX, double guiY) {
        MenuContext current = context();
        Minecraft minecraft = Minecraft.getInstance();
        if (current == null) return null;
        MenuState state = state(current);
        if (state == null) return null;
        Camera camera = minecraft.gameRenderer.getMainCamera();
        var window = minecraft.getWindow();
        double tanHalf = Math.tan(Math.toRadians(fovY) / 2), aspect = window.getWidth() / (double) window.getHeight();
        double nx = guiX / window.getGuiScaledWidth() * 2 - 1, ny = 1 - guiY / window.getGuiScaledHeight() * 2;
        var look = camera.getLookVector();
        var up = camera.getUpVector();
        var left = camera.getLeftVector();
        double dx = look.x() - left.x() * nx * tanHalf * aspect + up.x() * ny * tanHalf;
        double dy = look.y() - left.y() * nx * tanHalf * aspect + up.y() * ny * tanHalf;
        double dz = look.z() - left.z() * nx * tanHalf * aspect + up.z() * ny * tanHalf;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        BlockPos translation = current.placement.translation();
        double[] eye = toSource(current, camera.getPosition().x - translation.getX(), camera.getPosition().y - translation.getY(),
            camera.getPosition().z - translation.getZ());
        // Minecraft's direction (a, b, c) is Source's (a, -c, b).
        double[] direction = {dx / length, -dz / length, dy / length};
        int unlocked = ChapterRule.unlocked(current.placement.campaignId());
        Pick best = null;
        for (ScreenView screen : current.screens) {
            MenuState.Screen shown = state.screen(screen.entity);
            if (shown == null || !shown.active() || !screen.acceptsInput || screen.basis.backfacing(eye)) continue;
            double[] hit = screen.basis.intersect(eye, direction, screen.pixelsWide, screen.pixelsHigh);
            if (hit == null || best != null && hit[2] >= best.distance()) continue;
            best = new Pick(screen, screen.hit(hit[0], hit[1], unlocked), hit[0], hit[1], hit[2]);
        }
        return best;
    }

    private static double[] toSource(MenuContext current, double x, double y, double z) {
        return new double[]{(x - current.space.ox()) * 32, -(z - current.space.oz()) * 32, (y - current.space.oy()) * 32};
    }

    /** Moves the cursor: the hovered control changes (and its focus sound plays) when the cursor reaches another. */
    static void hover(double guiX, double guiY) {
        MenuContext current = context();
        if (current == null) return;
        MenuState state = state(current);
        MenuState.View view = activeView(state);
        double now = System.nanoTime() / 1e9;
        Pick pick = inputAllowed(view) && !ScreenFade.fadingOut() ? pick(guiX, guiY) : null;
        VguiLayout.Widget over = pick == null ? null : pick.widget();
        for (ScreenView screen : current.screens) {
            VguiLayout.Widget next = pick != null && pick.screen() == screen ? over : null;
            if (next != screen.hovered) {
                if (next != null) playUi(current, "ui/menu_focus.wav");
                screen.follow(next, now);
            }
        }
    }

    /** A left click: the control under the cursor runs its command, with the accept sound. */
    static void click(double guiX, double guiY) {
        MenuContext current = context();
        if (current == null || ScreenFade.fadingOut() || !inputAllowed(activeView(state(current)))) return;
        Pick pick = pick(guiX, guiY);
        if (pick == null || pick.widget() == null) return;
        playUi(current, "ui/menu_accept.wav");
        run(current, pick.screen(), pick.widget().command);
    }

    /** A control's command: {@code switchscreen} and {@code engine} are handled here, anything else is the game's. */
    static void run(MenuContext current, ScreenView screen, String command) {
        String text = command.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.startsWith("switchscreen ")) {
            if (!screen.switchTo(text.substring("switchscreen ".length()))) {
                Src2mc.LOGGER.warn("src2mc menu: {} has no layout {}", screen.name, text);
            }
        } else if (lower.startsWith("engine ")) {
            String engine = text.substring("engine ".length()).trim();
            if (engine.length() <= MenuNetwork.MAX_COMMAND) PacketDistributor.sendToServer(new MenuNetwork.FirePayload(engine));
        } else {
            MenuActions.handle(current, screen, text);
        }
    }

    private static void playUi(MenuContext current, String wave) {
        ScreensData.Sound sound = current.data.sound(wave);
        if (sound == null) return;
        MenuSounds.play(current.bundle, current.placement.campaignId() + "/" + current.placement.mapId() + "/" + sound.wave(), sound.path(),
            -1, 1.0F, SoundSource.MASTER);
    }

    /** Esc on the menu's cursor screen opens Minecraft's pause menu. */
    static void pause() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null && minecraft.player != null) minecraft.setScreen(new PauseScreen(true));
    }

    // ---- Status.

    static String status() {
        MenuContext current = context();
        if (current == null) return "src2mc menu: no menu map in this world";
        MenuState state = state(current);
        Minecraft minecraft = Minecraft.getInstance();
        StringBuilder text = new StringBuilder("src2mc menu: ").append(current.placement.mapId());
        double ticks = minecraft.level.getGameTime();
        for (MenuState.View view : state.views()) {
            double elapsed = view.moving() ? Math.max(0, ticks - view.startTick()) / 20.0 : 0;
            double t = view.moving() ? Math.max(0, Math.min(1, view.t() + (view.reverse() ? -1 : 1) * elapsed / view.seconds())) : view.reverse() ? 0 : 1;
            text.append("\n  viewcontrol #").append(view.entity()).append(view.enabled() ? " enabled" : " disabled")
                .append(", t=").append(String.format(Locale.ROOT, "%.2f", t)).append(view.moving() ? (view.reverse() ? " (reverse)" : " (forward)") : " (resting)")
                .append(", controls ").append(view.controls() ? "on" : "off").append(", cursor ").append(view.cursor() ? "on" : "off")
                .append(", path of ").append(view.path().size());
        }
        for (ScreenView screen : current.screens) {
            MenuState.Screen shown = state.screen(screen.entity);
            text.append("\n  screen ").append(screen.name).append(" (").append(screen.panelName).append(") ")
                .append(shown != null && shown.active() ? "active" : "inactive").append(", res ").append(screen.res())
                .append(", hovered ").append(screen.hovered == null ? "none" : screen.hovered.name + " \"" + screen.hovered.text + "\"");
        }
        text.append("\n  unlocked chapters ").append(ChapterRule.unlocked(current.placement.campaignId()));
        if (ScreenFade.fadingOut()) text.append("\n  fading out");
        return text.toString();
    }
}
