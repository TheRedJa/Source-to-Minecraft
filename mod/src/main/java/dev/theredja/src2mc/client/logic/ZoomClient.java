package dev.theredja.src2mc.client.logic;

import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.logic.ScreenEffects;
import dev.theredja.src2mc.menu.ViewPath;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * The field of view an {@code env_zoom} asks for, as {@code CBasePlayer::GetFOV} works it out:
 * from the field of view the player has now to the target over the zoom's rate, eased with
 * {@code SimpleSplineRemapVal} (a smoothstep), and an instant change at a rate of 0. A target of 0
 * is the player's own field of view, which here is Minecraft's. Source's angle is horizontal at
 * 4:3 and Minecraft's vertical, so the blend runs in Source's angle and {@link ViewPath#verticalFov}
 * converts it as the world is drawn.
 */
@EventBusSubscriber(modid = Src2mc.MOD_ID, value = Dist.CLIENT)
public final class ZoomClient {
    private ZoomClient() {}

    /** Whether a zoom is held, including one on its way back to the player's own. */
    private static boolean active;
    /** Source's angle where the zoom began, and where it goes; 0 is the player's own. */
    private static double start, target;
    /** Game seconds when it began and how long it takes. */
    private static double began, rate;

    /** {@code SetFOV}: a new zoom from wherever the view is now. */
    public static void zoom(ScreenEffects.ZoomPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        double time = time(0);
        double normal = fromVertical(minecraft.options.fov().get());
        double current = value(time, normal);
        start = Double.isNaN(current) ? normal : current;
        target = payload.fov();
        began = time;
        rate = payload.rate();
        active = target != 0 || start != normal;
        if (target == 0 && rate <= 0) active = false;
    }

    /**
     * The zoom's Source field of view now, or NaN with none (also back at the player's own). Other
     * components that take over the camera, such as the intro's, read this.
     */
    public static double sourceFov(float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return Double.NaN;
        return value(time(partialTick), fromVertical(minecraft.options.fov().get()));
    }

    public static void clear() { active = false; }

    /** Game seconds, between ticks too, as the level's clock; it stops with the game paused. */
    private static double time(float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        float tick = partialTick > 0 ? partialTick : minecraft.getTimer().getGameTimeDeltaPartialTick(false);
        return (minecraft.level.getGameTime() + tick) / 20.0;
    }

    /** {@code GetFOV}: the angle at {@code time}, with {@code normal} the player's own; NaN with no zoom. */
    private static double value(double time, double normal) {
        if (!active) return Double.NaN;
        double goal = target == 0 ? normal : target;
        if (rate <= 0) return goal;
        double fraction = Mth.clamp((time - began) / rate, 0, 1);
        if (fraction >= 1 && target == 0) return Double.NaN;
        double eased = fraction * fraction * (3 - 2 * fraction);
        return start + (goal - start) * eased;
    }

    /** The inverse of {@link ViewPath#verticalFov}: Source's angle for a vertical one. */
    private static double fromVertical(double vertical) {
        return Math.toDegrees(2 * Math.atan(Math.tan(Math.toRadians(vertical) / 2) / 0.75));
    }

    /**
     * Sets the frame's field of view. A viewcontrol camera sets its own after this, at the lowest
     * priority ({@code MenuClient.onComputeFov}), so it keeps its angle.
     */
    @SubscribeEvent
    static void onComputeFov(ViewportEvent.ComputeFov event) {
        if (!active || !event.usedConfiguredFov()) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) { active = false; return; }
        double fov = value(time((float) event.getPartialTick()), fromVertical(event.getFOV()));
        if (Double.isNaN(fov)) {
            // Back at the player's own: nothing left to hold.
            active = false;
            return;
        }
        event.setFOV(Mth.clamp(ViewPath.verticalFov(fov), 1, 170));
    }
}
