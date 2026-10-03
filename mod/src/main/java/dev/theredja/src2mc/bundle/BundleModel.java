package dev.theredja.src2mc.bundle;

/**
 * Immutable map-local binding of a shared runtime mesh to material slots. {@code surfaceProp} is
 * the model's {@code $surfaceprop}, or null when it declares none. {@code bounds} is the mesh's
 * own box in model space, min XYZ then max XYZ, or null before the mesh has been validated.
 * {@code color} is the tint the model is drawn with, {@code 0xRRGGBB}; white is none.
 */
public record BundleModel(String contentId, String sourceModel, int[] materialIds, String surfaceProp, float[] bounds, int color) {
    public static final int WHITE = 0xFFFFFF;

    public BundleModel {
        materialIds = materialIds.clone();
        bounds = bounds == null ? null : bounds.clone();
    }
    public BundleModel(String contentId, String sourceModel, int[] materialIds) { this(contentId, sourceModel, materialIds, null, null, WHITE); }
    public BundleModel(String contentId, String sourceModel, int[] materialIds, String surfaceProp) { this(contentId, sourceModel, materialIds, surfaceProp, null, WHITE); }
    public BundleModel(String contentId, String sourceModel, int[] materialIds, String surfaceProp, float[] bounds) { this(contentId, sourceModel, materialIds, surfaceProp, bounds, WHITE); }
    @Override public float[] bounds() { return bounds == null ? null : bounds.clone(); }
    BundleModel withBounds(float[] meshBounds) { return new BundleModel(contentId, sourceModel, materialIds, surfaceProp, meshBounds, color); }
    @Override public int[] materialIds() { return materialIds.clone(); }
    public int materialSlotCount() { return materialIds.length; }
}
