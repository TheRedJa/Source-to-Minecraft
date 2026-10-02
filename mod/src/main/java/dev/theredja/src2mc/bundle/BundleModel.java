package dev.theredja.src2mc.bundle;

/**
 * Immutable map-local binding of a shared runtime mesh to material slots. {@code surfaceProp} is
 * the model's {@code $surfaceprop}, or null when it declares none. {@code bounds} is the mesh's
 * own box in model space, min XYZ then max XYZ, or null before the mesh has been validated.
 */
public record BundleModel(String contentId, String sourceModel, int[] materialIds, String surfaceProp, float[] bounds) {
    public BundleModel {
        materialIds = materialIds.clone();
        bounds = bounds == null ? null : bounds.clone();
    }
    public BundleModel(String contentId, String sourceModel, int[] materialIds) { this(contentId, sourceModel, materialIds, null, null); }
    public BundleModel(String contentId, String sourceModel, int[] materialIds, String surfaceProp) { this(contentId, sourceModel, materialIds, surfaceProp, null); }
    @Override public float[] bounds() { return bounds == null ? null : bounds.clone(); }
    BundleModel withBounds(float[] meshBounds) { return new BundleModel(contentId, sourceModel, materialIds, surfaceProp, meshBounds); }
    @Override public int[] materialIds() { return materialIds.clone(); }
    public int materialSlotCount() { return materialIds.length; }
}
