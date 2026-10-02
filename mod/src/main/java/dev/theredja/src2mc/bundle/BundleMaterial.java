package dev.theredja.src2mc.bundle;

/**
 * Validated map-local material entry addressed by surface and mesh records. {@code doubleSided}
 * is Source's {@code $nocull}: the surface is seen from both sides, as a single-sheet fence is.
 * {@code surfaceProp} is the material's {@code $surfaceprop}, or null when it declares none.
 */
public record BundleMaterial(String sourceMaterial, RenderClass renderClass, TextureReference texture, boolean doubleSided,
                             String surfaceProp) {
    public enum RenderClass { SOLID, CUTOUT, TRANSLUCENT, FALLBACK }

    public BundleMaterial(String sourceMaterial, RenderClass renderClass, TextureReference texture, boolean doubleSided) {
        this(sourceMaterial, renderClass, texture, doubleSided, null);
    }

    public boolean textured() {
        return texture != null;
    }

    public record TextureReference(String contentId, int originalWidth, int originalHeight,
                                   int outputWidth, int outputHeight) {}
}
