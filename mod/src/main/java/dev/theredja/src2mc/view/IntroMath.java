package dev.theredja.src2mc.view;

/**
 * The time functions of Half-Life 2's {@code script_intro}, shared by the server (which needs the FOV mid-ramp
 * when a new ramp starts) and the clients (which evaluate them per frame). Times in any one unit.
 */
public final class IntroMath {
    private IntroMath() {}

    /**
     * {@code ScriptInfo_CalculateFOV} (game/shared/script_intro_shared.cpp): the spline ramp is Source's
     * {@code SimpleSplineRemapVal} ({@code 3t^2 - 2t^3}) and jumps to {@code next} once {@code t >= 1}; the linear
     * ramp is {@code RemapValClamped}; a ramp of no length is {@code next}.
     */
    public static double fov(double now, double start, double end, int from, int next, boolean spline) {
        if (spline) {
            double t = (now - start) / (end - start);
            if (t >= 1 || Double.isNaN(t)) return next;
            return from + (next - from) * (3 * t * t - 2 * t * t * t);
        }
        if (end - start != 0) {
            double t = Math.min(1, Math.max(0, (now - start) / (end - start)));
            return from + (next - from) * t;
        }
        return next;
    }

    /** The share of the blend into the next mode at {@code now} (C_ScriptIntro::ClientThink): 1 for a blend of no length. */
    public static double blend(double now, double start, double end) {
        if (end - start == 0) return 1;
        return Math.min(1, Math.max(0, (now - start) / (end - start)));
    }

    /**
     * The primary view's FOV, {@code IntroData_t::m_playerViewFOV}, at {@code now} in game ticks; 0 means the player's
     * own FOV. The client sets it to {@code m_iFOV} whenever that changes and to the ramp's value while the ramp runs
     * (c_script_intro.cpp:226-229, 300-308), so after a ramp it keeps the ramp's end until the next {@code SetFOV}.
     */
    public static double playerViewFov(ViewState.Intro intro, double now) {
        if (intro.fovEnd() >= 0 && now <= intro.fovEnd()) {
            return fov(now, intro.fovStart(), intro.fovEnd(), intro.startFov(), intro.nextFov(), intro.spline());
        }
        if (intro.fovEnd() >= 0 && intro.fovEnd() >= intro.fovSet()) return intro.nextFov();
        return intro.fovSet() >= 0 ? intro.fov() : 0;
    }

    /** The fade's alpha, 0..255, at {@code now} in game ticks ({@code CalculateAlpha}, a clamped linear remap). */
    public static double fade(ViewState.Intro intro, double now) {
        if (intro.fadeStart() < 0) return intro.fadeTo();
        double ticks = intro.fadeDuration() * 20.0;
        double t = ticks <= 0 ? 1 : Math.min(1, Math.max(0, (now - intro.fadeStart()) / ticks));
        return intro.fadeFrom() + (intro.fadeTo() - intro.fadeFrom()) * t;
    }
}
