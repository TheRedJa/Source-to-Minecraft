package dev.theredja.src2mc.bundle;

/**
 * Immutable map-local binding of a shared runtime mesh to material slots. {@code surfaceProp} is
 * the model's {@code $surfaceprop}, or null when it declares none. {@code bounds} is the mesh's
 * own box in model space, min XYZ then max XYZ, or null before the mesh has been validated.
 * {@code color} is the tint the model is drawn with, {@code 0xRRGGBB}; white is none.
 * {@code animationId} names the {@code .s2anim} of an animated prop's model (format.md section 19),
 * null for none; {@code animation} is it decoded, null until the bundle has been validated.
 */
public record BundleModel(String contentId, String sourceModel, int[] materialIds, String surfaceProp, float[] bounds, int color,
                          String animationId, AnimationAsset animation) {
    public static final int WHITE = 0xFFFFFF;

    public BundleModel {
        materialIds = materialIds.clone();
        bounds = bounds == null ? null : bounds.clone();
    }
    public BundleModel(String contentId, String sourceModel, int[] materialIds) { this(contentId, sourceModel, materialIds, null, null, WHITE); }
    public BundleModel(String contentId, String sourceModel, int[] materialIds, String surfaceProp) { this(contentId, sourceModel, materialIds, surfaceProp, null, WHITE); }
    public BundleModel(String contentId, String sourceModel, int[] materialIds, String surfaceProp, float[] bounds) { this(contentId, sourceModel, materialIds, surfaceProp, bounds, WHITE); }
    public BundleModel(String contentId, String sourceModel, int[] materialIds, String surfaceProp, float[] bounds, int color) {
        this(contentId, sourceModel, materialIds, surfaceProp, bounds, color, null, null);
    }
    @Override public float[] bounds() { return bounds == null ? null : bounds.clone(); }
    BundleModel withBounds(float[] meshBounds) { return new BundleModel(contentId, sourceModel, materialIds, surfaceProp, meshBounds, color, animationId, animation); }
    BundleModel withAnimation(AnimationAsset decoded) { return new BundleModel(contentId, sourceModel, materialIds, surfaceProp, bounds, color, animationId, decoded); }
    @Override public int[] materialIds() { return materialIds.clone(); }
    public int materialSlotCount() { return materialIds.length; }
}
