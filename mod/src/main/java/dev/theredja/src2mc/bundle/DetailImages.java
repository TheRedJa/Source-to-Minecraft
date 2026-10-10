package dev.theredja.src2mc.bundle;

import java.util.Map;

/**
 * The repeating textures a map's materials use besides the atlas (format.md section 6): detail
 * textures and blend modulation textures, by content ID, decoded to RGBA rows from the top.
 * Sides are powers of two up to 512.
 */
public record DetailImages(Map<String, Image> images) {
    public DetailImages { images = Map.copyOf(images); }

    public static final int MAX_SIDE = 512;

    public record Image(int width, int height, byte[] rgba) {}
}
