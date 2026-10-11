package dev.theredja.src2mc.client.menu;

import dev.theredja.src2mc.Src2mc;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * Source's screen fade to and from black over the whole window, drawn over the world (under the HUD texts, never over a loading screen):
 * the menu's transitions fade out ({@code infra_transition_fade_out_time} 0.5 s, then {@code fade_out_hold_time}
 * 0.1 s of black, then the level change starts) and an arrival fades in ({@code infra_transition_fade_in_hold_time}
 * 1.0 s of black, then {@code infra_transition_fade_in_time} 0.5 s).
 */
@EventBusSubscriber(modid = Src2mc.MOD_ID, value = Dist.CLIENT)
public final class ScreenFade {
    private ScreenFade() {}

    public static final double FADE_OUT_SECONDS = 0.5;
    public static final double FADE_OUT_HOLD_SECONDS = 0.1;
    public static final double FADE_IN_HOLD_SECONDS = 1.0;
    public static final double FADE_IN_SECONDS = 0.5;
    /** Black held after a fade-out whose action never changed the world (the server refused) is dropped after this long. */
    private static final double STUCK_SECONDS = 15;

    private enum Phase { NONE, OUT, IN }

    private static Phase phase = Phase.NONE;
    private static double started, hold, duration;
    private static Runnable after;
    private static boolean fired;

    private static double now() { return System.nanoTime() / 1e9; }

    /** Fades to black over {@link #FADE_OUT_SECONDS}, holds {@link #FADE_OUT_HOLD_SECONDS}, runs {@code action}, then stays black until the world is left or arrived in. */
    public static void fadeOut(Runnable action) {
        phase = Phase.OUT;
        started = now();
        hold = FADE_OUT_SECONDS + FADE_OUT_HOLD_SECONDS;
        duration = FADE_OUT_SECONDS;
        after = action;
        fired = false;
    }

    /** Black for {@link #FADE_IN_HOLD_SECONDS}, then fading out over {@link #FADE_IN_SECONDS}. */
    public static void fadeIn() {
        phase = Phase.IN;
        started = now();
        hold = FADE_IN_HOLD_SECONDS;
        duration = FADE_IN_SECONDS;
        after = null;
    }

    /** Whether a fade-out is running or holding: the menu ignores the mouse meanwhile. */
    public static boolean fadingOut() { return phase == Phase.OUT; }

    /** Seconds since the fade-out began, or -1 when none is running. */
    public static double outProgress() { return phase == Phase.OUT ? (now() - started) / FADE_OUT_SECONDS : -1; }

    /** Black 0..1 now. */
    public static float alpha() {
        double t = now() - started;
        return switch (phase) {
            case NONE -> 0f;
            case OUT -> (float) Math.min(1, t / duration);
            case IN -> t < hold ? 1f : (float) Math.max(0, 1 - (t - hold) / duration);
        };
    }

    public static void clear() {
        phase = Phase.NONE;
        after = null;
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        double t = now() - started;
        if (phase == Phase.OUT && !fired && t >= hold) {
            fired = true;
            Runnable action = after;
            after = null;
            if (action != null) action.run();
        } else if (phase == Phase.OUT && fired && t >= hold + STUCK_SECONDS) {
            clear();
        } else if (phase == Phase.IN && t >= hold + duration) {
            clear();
        }
    }

    /** The old world is gone: whatever was fading belongs to it. A fade-out stays black across the switch (the loading screen is black too). */
    @SubscribeEvent
    static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        if (phase == Phase.IN) clear();
    }

    /**
     * Drawn in the HUD pass before the HUD, as Source's CViewEffects fade comes before the HUD paints, and on the
     * same layer as ScreenOverlay's under-HUD fades, so game_text, the chapter title and captions stay readable
     * over the black. Only in the world, with no screen or the menu's own: a loading screen (ours or vanilla's)
     * is never covered, so its progress bar shows.
     */
    @SubscribeEvent
    static void onGuiRenderPre(RenderGuiEvent.Pre event) {
        Screen screen = Minecraft.getInstance().screen;
        if (screen != null && !(screen instanceof MenuScreen)) return;
        float alpha = alpha();
        if (alpha <= 0f) return;
        GuiGraphics graphics = event.getGuiGraphics();
        graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), (int) (alpha * 255) << 24);
    }
}
