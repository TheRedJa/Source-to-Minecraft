package dev.theredja.src2mc.bundle;

/**
 * Validated map-local material entry addressed by surface and mesh records. {@code doubleSided}
 * is Source's {@code $nocull}: the surface is seen from both sides, as a single-sheet fence is.
 * {@code surfaceProp} is the material's {@code $surfaceprop}, or null when it declares none.
 */
public record BundleMaterial(String sourceMaterial, RenderClass renderClass, TextureReference texture, boolean doubleSided,
                             String surfaceProp, float reflectR, float reflectG, float reflectB) {
    public enum RenderClass { SOLID, CUTOUT, TRANSLUCENT, FALLBACK }

    /** Without the average colour: mid grey. */
    public BundleMaterial(String sourceMaterial, RenderClass renderClass, TextureReference texture, boolean doubleSided, String surfaceProp) {
        this(sourceMaterial, renderClass, texture, doubleSided, surfaceProp, 0.5F, 0.5F, 0.5F);
    }

    /** The texture's average colour in linear light, its {@code reflectivity}. */
    public double[] reflectivity() { return new double[]{reflectR, reflectG, reflectB}; }

    public BundleMaterial(String sourceMaterial, RenderClass renderClass, TextureReference texture, boolean doubleSided) {
        this(sourceMaterial, renderClass, texture, doubleSided, null);
    }

    public boolean textured() {
        return texture != null;
    }

    public record TextureReference(String contentId, int originalWidth, int originalHeight,
                                   int outputWidth, int outputHeight) {}
}
