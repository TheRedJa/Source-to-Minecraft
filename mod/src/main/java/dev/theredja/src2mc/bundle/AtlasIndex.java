package dev.theredja.src2mc.bundle;

import java.util.List;
import java.util.Map;

/** Immutable lookup for validated mod-owned atlas pages and logical textures. */
public record AtlasIndex(int pageSize, int maxMipLevel, int gutter, List<Page> pages, Map<String, Texture> textures) {
    public AtlasIndex { pages = List.copyOf(pages); textures = Map.copyOf(textures); }
    /** {@code bumpMips}: the page's bump layer, the same layout as {@code mips}; empty when no texture on it has one. */
    /** {@code blendMips}: the blend layer (blended displacements' second texture), the same layout; empty without one. */
    public record Page(int page, List<Mip> mips, List<Mip> bumpMips, List<Mip> blendMips) {
        public Page { mips = List.copyOf(mips); bumpMips = List.copyOf(bumpMips); blendMips = List.copyOf(blendMips); }
        public Page(int page, List<Mip> mips) { this(page, mips, List.of(), List.of()); }
        public boolean bumped() { return !bumpMips.isEmpty(); }
        public boolean blended() { return !blendMips.isEmpty(); }
    }
    public record Mip(int level, String path, int width, int height) {}
    public record Texture(String contentId, int width, int height, List<Region> regions) {
        public Texture { regions = List.copyOf(regions); }
    }
    public record Region(int[] source, int page, int[] allocation) {
        public Region { source = source.clone(); allocation = allocation.clone(); }
        @Override public int[] source() { return source.clone(); }
        @Override public int[] allocation() { return allocation.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof Region region && page == region.page
                && java.util.Arrays.equals(source, region.source)
                && java.util.Arrays.equals(allocation, region.allocation);
        }
        @Override public int hashCode() {
            return 31 * (31 * page + java.util.Arrays.hashCode(source)) + java.util.Arrays.hashCode(allocation);
        }
    }
}
