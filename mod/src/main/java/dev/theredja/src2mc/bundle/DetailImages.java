package dev.theredja.src2mc.bundle;

import java.util.Map;

/**
 * The repeating textures a map's materials use besides the atlas (format.md section 6): detail
 * textures and blend modulation textures, by content ID, decoded to RGBA rows from the top.
 * Sides are powers of two up to 512. {@code warps} are the phong warp and light warp ramps, which
 * are sampled clamped and not repeated; a content ID may be in both.
 */
public record DetailImages(Map<String, Image> images, Map<String, Image> warps) {
    public DetailImages { images = Map.copyOf(images); warps = Map.copyOf(warps); }

    public DetailImages(Map<String, Image> images) { this(images, Map.of()); }

    public static final int MAX_SIDE = 512;

    public record Image(int width, int height, byte[] rgba) {}
}
