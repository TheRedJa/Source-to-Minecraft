package dev.theredja.src2mc.bundle;

/**
 * Validated map-local material entry addressed by surface and mesh records. {@code doubleSided}
 * is Source's {@code $nocull}: the surface is seen from both sides, as a single-sheet fence is.
 * {@code surfaceProp} is the material's {@code $surfaceprop}, or null when it declares none.
 */
public record BundleMaterial(String sourceMaterial, RenderClass renderClass, TextureReference texture, boolean doubleSided,
                             String surfaceProp, float reflectR, float reflectG, float reflectB, Bump bump, Envmap envmap,
                             boolean blend, String blendModulate, Detail detail, float[] selfillum) {
    public enum RenderClass { SOLID, CUTOUT, TRANSLUCENT, FALLBACK }

    /**
     * The texture's bump layer on the atlas's bump pages: {@code NORMAL} a tangent-space normal
     * map, {@code SSBUMP} each bump basis direction's share of the light ({@code $ssbump}).
     */
    public enum Bump { NONE, NORMAL, SSBUMP }

    /**
     * {@code $envmap} as LightmappedGeneric draws it: the cubemap, an index into the map's
     * {@link CubemapTable}, and the reflection's tint, contrast, saturation and Fresnel term. Its
     * mask is the alpha of the texture's bump layer.
     */
    public record Envmap(int cubemap, float[] tint, float contrast, float[] saturation, float fresnel) {}

    /**
     * {@code $detail}: the content ID of its {@code details/<id>.png}, repeats per base texture
     * repeat along s and t, blend factor, {@code TCOMBINE_*} mode and tint.
     */
    public record Detail(String texture, float scaleS, float scaleT, float blendFactor, int blendMode, float[] tint) {}

    /** Whether the material needs a row of surface settings in the baked shader. */
    public boolean surfaceEffects() {
        return envmap != null || blend || detail != null || selfillum != null;
    }

    public BundleMaterial {
        if (bump == null) bump = Bump.NONE;
    }

    public BundleMaterial(String sourceMaterial, RenderClass renderClass, TextureReference texture, boolean doubleSided,
                          String surfaceProp, float reflectR, float reflectG, float reflectB) {
        this(sourceMaterial, renderClass, texture, doubleSided, surfaceProp, reflectR, reflectG, reflectB, Bump.NONE, null,
            false, null, null, null);
    }

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
