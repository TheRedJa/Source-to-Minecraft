package dev.theredja.src2mc.bundle;

import java.util.Map;

/**
 * What the map's view needs beyond its surfaces (format section 24): whether it carries HDR
 * light, which turns on Source's auto exposure and bloom; how the game's post-processing
 * materials set bloom and the vignette up; and the colour lookups its {@code color_correction}
 * entities name, by the normalized name the entity's {@code filename} gives. A lookup is
 * {@link #CELLS} cubed RGB bytes, red fastest, then green, then blue.
 *
 * @param bloomType the downsample's {@code $bloomtype} in the Alien Swarm branch (Portal 2,
 *     INFRA); -1 for the 2013 engine's shader, which shapes each tap instead of their average
 * @param kernelX the horizontal blur's {@code $kernel}, 0 for the thirteen-tap cross
 * @param vignette the red channel of {@code engine_post}'s vignette texture, or null without one
 * @param grain the film grain the game turns on, or null where it does not
 */
public record LookTable(boolean hdr, int bloomType, int kernelX, int kernelY, Vignette vignette, Grain grain, Map<String, byte[]> lookups) {
    /** A texture of {@code width} by {@code height} red texels, rows top to bottom. */
    public record Vignette(int width, int height, byte[] red) {}

    /**
     * {@code engine_post}'s film grain: {@code scale} from {@code $noiseScale} or the video
     * setting's override, and the green channel of its noise texture, {@link #GRAIN_SIZE} squared.
     */
    public record Grain(float scale, byte[] green) {}

    /** Side of the grain texture. */
    public static final int GRAIN_SIZE = 64;
    /** Edge of a lookup's cube. */
    public static final int CELLS = 32;
    /** Bytes of one lookup. */
    public static final int LOOKUP_BYTES = CELLS * CELLS * CELLS * 3;

    public LookTable {
        lookups = Map.copyOf(lookups);
    }

    /** {@code filename} as the converter keys it: trimmed, lowercase, forward slashes. */
    public static String name(String filename) {
        return filename == null ? "" : filename.trim().toLowerCase(java.util.Locale.ROOT).replace('\\', '/');
    }

    /** The lookup an entity names, or null when the map has none by that name. */
    public byte[] lookup(String filename) {
        return lookups.get(name(filename));
    }
}
