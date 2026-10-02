package dev.theredja.src2mc.bundle;

/**
 * Immutable map-local binding of a shared runtime mesh to material slots. {@code surfaceProp} is
 * the model's {@code $surfaceprop}, or null when it declares none.
 */
public record BundleModel(String contentId, String sourceModel, int[] materialIds, String surfaceProp) {
    public BundleModel { materialIds = materialIds.clone(); }
    public BundleModel(String contentId, String sourceModel, int[] materialIds) { this(contentId, sourceModel, materialIds, null); }
    @Override public int[] materialIds() { return materialIds.clone(); }
    public int materialSlotCount() { return materialIds.length; }
}
