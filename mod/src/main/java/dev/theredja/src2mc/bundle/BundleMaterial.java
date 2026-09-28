package dev.theredja.src2mc.bundle;

/**
 * Validated map-local material entry addressed by surface and mesh records. {@code doubleSided}
 * is Source's {@code $nocull}: the surface is seen from both sides, as a single-sheet fence is.
 */
public record BundleMaterial(String sourceMaterial, RenderClass renderClass, TextureReference texture, boolean doubleSided) {
    public enum RenderClass { SOLID, CUTOUT, TRANSLUCENT, FALLBACK }

    public boolean textured() {
        return texture != null;
    }

    public record TextureReference(String contentId, int originalWidth, int originalHeight,
                                   int outputWidth, int outputHeight) {}
}
