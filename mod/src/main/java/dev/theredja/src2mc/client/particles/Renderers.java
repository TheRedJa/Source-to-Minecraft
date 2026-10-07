package dev.theredja.src2mc.client.particles;

import dev.theredja.src2mc.bundle.ParticleTable;
import java.util.Locale;

/** The renderers a system draws with: what they need of their parameters. Drawing is {@link ParticleRenderer}'s. */
final class Renderers {
    private Renderers() {}

    enum Kind { SPRITES, TRAIL, ROPE }

    /**
     * One renderer. Sheet time: with {@code use animation rate as FPS} the rate is sheet frames per
     * second; with {@code animation_fit_lifetime} the sequence plays once over the particle's life;
     * otherwise the rate is sequence loops per second, which makes sense of the library's 0.1 default.
     * Source's own rule is not public.
     */
    record Renderer(Kind kind, float animationRate, boolean fitLifetime, boolean rateAsFps, int orientation, int orientationControlPoint,
                    float lengthFadeIn, float maxLength, float minLength, int subdivisions, float texelSize, float scrollRate) {}

    static Renderer create(ParticleTable.Function function) {
        ParticleTable.Params p = function.parameters();
        float rate = (float) p.number("animation rate", 0.1);
        return switch (function.name().toLowerCase(Locale.ROOT)) {
            case "render_animated_sprites" -> new Renderer(Kind.SPRITES, rate, p.bool("animation_fit_lifetime", false),
                p.bool("use animation rate as FPS", false), p.integer("orientation_type", 0), p.integer("orientation control point", -1),
                0, 0, 0, 0, 0, 0);
            case "render_sprite_trail" -> new Renderer(Kind.TRAIL, rate, false, false, 0, -1, (float) p.number("length fade in time", 0),
                (float) p.number("max length", 2000), (float) p.number("min length", 0), 0, 0, 0);
            case "render_rope" -> new Renderer(Kind.ROPE, 0, false, false, 0, -1, 0, 0, 0, Math.max(1, Math.min(16, p.integer("subdivision_count", 3))),
                (float) p.number("texel_size", 4), (float) p.number("texture_scroll_rate", 0));
            default -> null;
        };
    }
}
