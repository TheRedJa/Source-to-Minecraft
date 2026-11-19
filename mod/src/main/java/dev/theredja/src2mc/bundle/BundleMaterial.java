package dev.theredja.src2mc.bundle;

/**
 * Validated map-local material entry addressed by surface and mesh records. {@code doubleSided}
 * is Source's {@code $nocull}: the surface is seen from both sides, as a single-sheet fence is.
 * {@code surfaceProp} is the material's {@code $surfaceprop}, or null when it declares none.
 */
public record BundleMaterial(String sourceMaterial, RenderClass renderClass, TextureReference texture, boolean doubleSided,
                             String surfaceProp, float reflectR, float reflectG, float reflectB, Bump bump, Envmap envmap,
                             boolean blend, String blendModulate, Detail detail, float[] selfillum, Phong phong, Camera camera) {
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

    /** Where a phong material's specular strength comes from. */
    public enum PhongMask { NORMAL_ALPHA, BASE_ALPHA, BASE_LUMINANCE }

    /**
     * {@code $phong} of a VertexLitGeneric material, as {@code phong_ps20b.fxc} combines it. {@code
     * exponent} 0 reads the exponent from the red of the texture's phong layer, {@code 1 + 149 * r}
     * (150 without a layer); {@code exponentTexture} says the atlas has that layer (red exponent,
     * green albedo-tint mask, alpha rim mask). With {@code albedoTint} the specular is tinted by
     * {@code lerp(1, base colour, layer green)} instead of {@code tint}. {@code warp} and {@code
     * lightWarp} are content IDs of clamped ramps in {@link DetailImages#warps()}; {@code rim} is
     * null without {@code $rimlight}.
     */
    public record Phong(float exponent, boolean exponentTexture, float boost, float[] fresnelRanges, float[] tint,
                        boolean albedoTint, PhongMask mask, boolean halfLambert, String warp, String lightWarp, Rim rim) {}

    /** {@code $rimlight}: scaled by {@code lerp(1, phong layer alpha, mask)}. */
    public record Rim(float exponent, float boost, float mask) {}

    /** The shader a camera material is drawn with. */
    public enum CameraShader { UNLIT_GENERIC, UNLIT_TWO_TEXTURE, MONITOR_SCREEN }

    /**
     * A {@code $basetexture _rt_Camera} material (format.md section 6): its faces show the shared camera target.
     * Texture coordinates are the surface table's texels divided by {@code sizeS, sizeT}. {@code color} and
     * {@code color2} are {@code $color} and {@code $color2}; {@code sines} by channel, null where a channel has
     * none. {@code texture2} is the content ID of a {@link DetailImages#images()} entry, null for none (white);
     * {@code transform} null is the identity. {@code contrast}, {@code saturation} and {@code tint} are
     * MonitorScreen's (0, 1, white for the others).
     */
    public record Camera(CameraShader shader, int sizeS, int sizeT, boolean additive, float[] color, float[] color2,
                         Sine[] sines, String texture2, Texture2Transform transform, float contrast, float saturation, float[] tint) {
        public Camera {
            color = color.clone(); color2 = color2.clone(); sines = sines.clone(); tint = tint.clone();
            if (sines.length != 3) throw new IllegalArgumentException("one sine slot per channel");
        }
        @Override public float[] color() { return color.clone(); }
        @Override public float[] color2() { return color2.clone(); }
        @Override public Sine[] sines() { return sines.clone(); }
        @Override public float[] tint() { return tint.clone(); }

        /**
         * {@code $color * $color2} at time {@code t} seconds, each channel its sine's value if it has one
         * ({@code CSineProxy::OnBind}, game/client/mathproxy.cpp:399-416).
         */
        public float[] modulation(double t) {
            float[] out = new float[3];
            for (int i = 0; i < 3; i++) {
                Sine sine = sines[i];
                float value = sine == null ? color[i] : sine.value(t);
                out[i] = value * color2[i];
            }
            return out;
        }
    }

    /** {@code (max - min) * (sin(2 pi (t - offset) / period) * 0.5 + 0.5) + min}. */
    public record Sine(float period, float min, float max, float offset) {
        public float value(double t) {
            double period = this.period == 0 ? 1 : this.period;
            float unit = (float) (Math.sin(2.0 * Math.PI * (t - offset) / period) * 0.5 + 0.5);
            return (max - min) * unit + min;
        }
    }

    /** {@code $texture2}'s coordinates: {@code uv * scale + offset + velocity * t}. */
    public record Texture2Transform(float scale, float offsetS, float offsetT, float velocityS, float velocityT) {}

    /** Whether the material needs a row of surface settings in the baked shader. */
    public boolean surfaceEffects() {
        return envmap != null || blend || detail != null || selfillum != null || phong != null;
    }

    public BundleMaterial {
        if (bump == null) bump = Bump.NONE;
    }

    public BundleMaterial(String sourceMaterial, RenderClass renderClass, TextureReference texture, boolean doubleSided,
                          String surfaceProp, float reflectR, float reflectG, float reflectB) {
        this(sourceMaterial, renderClass, texture, doubleSided, surfaceProp, reflectR, reflectG, reflectB, Bump.NONE, null,
            false, null, null, null, null, null);
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
