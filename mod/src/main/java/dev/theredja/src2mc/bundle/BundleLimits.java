package dev.theredja.src2mc.bundle;

final class BundleLimits {
    static final int MAX_ENTRY_COUNT = 100_000;
    static final int MAX_ENTRY_PATH_BYTES = 240;
    static final long MAX_UNCOMPRESSED_ENTRY_BYTES = 2L * 1024 * 1024 * 1024;
    static final long MAX_UNCOMPRESSED_BUNDLE_BYTES = 64L * 1024 * 1024 * 1024;
    static final long MAX_ZIP_EXPANSION_RATIO = 200;
    static final long SMALL_ENTRY_ALLOWANCE = 1L << 20;
    // 100k fixed-shape manifest records fit comfortably; this prevents a huge
    // bootstrap allocation before its own declared digest can be consulted.
    static final int MAX_MANIFEST_BYTES = 64 * 1024 * 1024;
    static final int MAX_JSON_NESTING = 64;
    static final int MAX_MAPS = 4_096;
    static final int MAX_MATERIALS_PER_MAP = 1_000_000;
    static final int MAX_AUDIO_RECORDS = 1_000_000;
    static final int MAX_LOGIC_RECORDS = 1_000_000;
    static final int MAX_MOVERS_PER_MAP = 65_536;
    static final int MAX_LOGIC_PROPS_PER_MAP = 16_384;
    static final int MAX_LOGIC_PROP_SKINS = 1_024;
    /** One {@code .s2anim}; the largest of the test maps is a few megabytes. */
    static final long MAX_ANIMATION_BYTES = 64L << 20;
    /** A train's path is not part of its mover; a mover is the entity itself, at most a large hall's worth of cells. */
    static final long MAX_CELLS_PER_MOVER = 16_777_216;
    static final int MAX_MODELS_PER_CAMPAIGN = 1_000_000;
    static final int MAX_PROPS_PER_MAP = 10_000_000;
    static final int MAX_UV_REGIONS_PER_MAP = 10_000_000;
    static final int MAX_SECTIONS_PER_MAP = 4_000_000;
    static final int MAX_FACES_PER_MAP = 100_000_000;
    // A corruption guard, not a budget: every distinct shape in a real map is
    // far below this, and exceeding it fails the bundle loudly.
    static final int MAX_COLLISION_SHAPES_PER_MAP = 100_000_000;
    static final int MAX_VERTICES_PER_MESH = 10_000_000;
    static final int MAX_INDICES_PER_MESH = 30_000_000;
    static final int MAX_SUBMESHES_PER_MESH = 65_536;
    static final int MAX_ORIGINAL_TEXTURE_AXIS = 16_384;
    static final int MAX_OUTPUT_TEXTURE_AXIS = 4_096;
    static final long MAX_DECODED_TEXTURE_BYTES = 256L * 1024 * 1024;
    static final int MAX_PVS_CLUSTERS = 65_536;
    static final int MAX_PVS_LEAVES = 4_000_000;
    static final long MAX_PVS_BITSET_BYTES = 256L * 1024 * 1024;

    private BundleLimits() {
    }
}
