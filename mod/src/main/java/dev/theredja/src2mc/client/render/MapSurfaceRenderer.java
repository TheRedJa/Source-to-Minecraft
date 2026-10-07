package dev.theredja.src2mc.client.render;

import static net.minecraft.commands.Commands.literal;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.bundle.AtlasIndex;
import dev.theredja.src2mc.bundle.BundleGeneration;
import dev.theredja.src2mc.bundle.BundleManifest;
import dev.theredja.src2mc.bundle.BundleMaterial;
import dev.theredja.src2mc.bundle.BundleMap;
import dev.theredja.src2mc.bundle.SurfaceTable;
import dev.theredja.src2mc.network.PlacementNetwork;
import dev.theredja.src2mc.world.MapPlacement;
import dev.theredja.src2mc.world.Src2mcWorldContent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import org.joml.Matrix4f;

/** Mod-owned static section/page renderer; independent of Sodium terrain internals. */
@EventBusSubscriber(modid = Src2mc.MOD_ID, value = Dist.CLIENT)
public final class MapSurfaceRenderer {
    private static final int REGION_SECTIONS = 4;
    /** Render-thread time per frame for copying the world into new builds' snapshots. */
    private static final long DISPATCH_BUDGET_NANOS = 3_000_000L;
    /** Render-thread time per frame for filling and uploading finished builds; one always goes. */
    private static final long UPLOAD_BUDGET_NANOS = 4_000_000L;
    private static final int MAX_IN_FLIGHT = MeshBuildPool.THREADS * 2;
    /** How long a region waits for its chunks' light before it is built with what is there. */
    private static final long LIGHT_WAIT_NANOS = 3_000_000_000L;
    /** A region's snapshot reaches this far past its bounds: a smooth sample reads up to one and a
     * half blocks out, and an owned fragment's block is one cell away. */
    private static final int SNAPSHOT_MARGIN = 2;
    private static final long MESH_GRACE_FRAMES = 600;
    private static final AtlasPageResidency PAGES = new AtlasPageResidency();
    private static final Map<MeshKey, Mesh> MESHES = new LinkedHashMap<>();
    /** The same meshes by region, so per-region work does not walk every mesh. */
    private static final Map<RegionKey, Map<PageClass, Mesh>> REGION_MESHES = new HashMap<>();
    private static final Map<RegionKey, Long> BUILT_REGIONS = new HashMap<>();
    /** Bumped by every invalidation; a build finishing against an older value is thrown away. */
    private static final Map<RegionKey, Integer> REGION_VERSIONS = new HashMap<>();
    /** When each unbuilt region in range was first held back for its chunks' light. */
    private static final Map<RegionKey, Long> WAITING_SINCE = new HashMap<>();
    /** Each placement's regions with their world bounds, worked out once. */
    private static final Map<MapPlacement, List<RegionEntry>> PLACEMENT_REGIONS = new HashMap<>();
    /** Identity-keyed: {@link BundleMap#hashCode()}/{@code equals} deep-hash the whole surface
     * table, so a regular HashMap would redo that work on every lookup; the same instance is
     * reused for a generation's lifetime, so identity is both correct and cheap. */
    private static final Map<BundleMap, Map<RegionCoord, RegionGroup>> REGION_GROUPS = new java.util.IdentityHashMap<>();
    private static ClientLevel level;
    private static long generationSequence = -1;
    private static List<MapPlacement> placementSnapshot = List.of();
    private static long frame;
    private static long pvsRejectedRegions;
    private static boolean stillLoading = true;
    private static long relightsQueued;
    /** Build pipeline counters, read back through {@code /src2mc_render_status}. */
    private static int waitingLast, uploadsLast;
    private static long dispatchNanosLast, uploadNanosLast, worstUploadNanos, buildsDiscarded, buildsFailed;
    /** Region rebuilds caused by surface blocks appearing or vanishing, and the hidden-fragment
     * count of the most recent build; read back through {@code /src2mc_render_status}. */
    private static long surfaceRebuildsQueued;
    private static int lastHiddenFragments;
    private static long shadowPassCallsSinceMainPass;
    private static long shadowPassCallsLastFrame;
    /** Shadow-pass draw-set accounting, accumulated across a frame's shadow invocations and latched
     * when the next main pass runs. Without it the only visible fact about the shadow map is how
     * many times we were called, which says nothing about what reached it. */
    private static long shadowConsidered, shadowRejectedUnbuilt, shadowRejectedDistance, shadowDrawn;
    private static long shadowConsideredLast, shadowRejectedUnbuiltLast, shadowRejectedDistanceLast, shadowDrawnLast;
    /** Which stage the opaque draw runs in. Switchable at runtime because Iris picks a shaderpack
     * program from the rendering phase it is in, so the stage a mod draws from decides which
     * gbuffers program its geometry lands in — and the wrong one produces geometry that is drawn
     * but contributes nothing usable to the deferred pass. */
    private static RenderLevelStageEvent.Stage opaqueStage = RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS;
    private static final java.util.Set<String> SHADOW_STAGES_SEEN = new java.util.LinkedHashSet<>();
    // One light per vertex rather than one per face. Kept as a toggle because
    // lighting is judged by looking at it, and the flat build is the only
    // honest comparison.
    private static boolean smoothLighting = true;
    /** Whether smooth light is kept from sampling cells behind the map's own surfaces. */
    private static boolean lightOcclusion = true;
    /** Neighbour cells tested and dropped for being out of sight, by the last finished build. */
    private static long occlusionTestedLast, occlusionBlockedLast;
    private static boolean pvsCulling = true;
    /** Whether translucent surfaces and props write depth. Off: vanilla's entity translucent
     * type writes depth, so whichever translucent layer drew first hid every one behind it --
     * a glass prop behind a glass surface vanished. Kept as a toggle to compare by eye. */
    private static boolean translucentDepthWrite = false;
    /** Whether opaque surfaces and props are drawn grouped by render state and nearest first, or in
     * whatever order they are stored; a toggle so the frame-time effect can be compared. */
    private static boolean sortedDraws = true;
    /** Whether meshes go up as distinct vertices plus an index buffer rather than three fresh
     * vertices per triangle; a toggle because Iris's per-triangle attributes are then shared. */
    private static boolean indexedMeshes = true;
    /** Whether atlas pages are sampled through their mip levels. The entity render types turn
     * mipmapping off every time they are set up (their texture shard says mipmap=false), so the
     * converter's mips 1-4 went unused and distant surfaces sampled the full page: shimmering
     * noise, and far worse at full texture quality. A toggle to compare. */
    private static boolean atlasMipmaps = true;
    /** Draw-side counters for the main pass, latched each frame; see {@link #frameStats}. */
    private static final DrawStats STATS = new DrawStats();
    private static DrawStats.Frame lastStats = DrawStats.Frame.EMPTY;
    private static boolean frustumCulling = true;
    /** Whether shadow-pass draws are tested against Iris's own shadow frustum. */
    private static boolean shadowCulling = true;
    /** Shadow-pass meshes rejected by that frustum, accumulated and latched like the others. */
    private static long shadowRejectedFrustum, shadowRejectedFrustumLast;
    /** Shadow-caster cutoff in blocks. The shaderpack's own shadow-distance setting only culls
     * Sodium's terrain, never mod-owned geometry, so without this the whole map is rasterized into
     * the shadow map every frame. */
    private static double shadowDistance = 75.0;
    /** Whether to zero Iris's captured entity/block-entity/item ids while a mesh is uploaded. On,
     * because Iris stamps whatever was rendering at that moment into every vertex of the extended
     * entity format, and the label sticks for the life of the buffer -- a pack with entity shadows
     * disabled then keeps our geometry out of the shadow map. The format extension itself cannot be
     * skipped: Iris rewrites the attribute layout for every NEW_ENTITY buffer while a pack is in
     * use, so a buffer built without the extra elements is read with the wrong stride. */
    private static boolean neutralEntityId = true;

    /** Region builds running on {@link MeshBuildPool}, oldest first. */
    private static final Map<RegionKey, InFlight> IN_FLIGHT = new LinkedHashMap<>();

    /**
     * Everything one region build reads, fixed when it is started: the world through a snapshot
     * (or, for a build that failed on a worker and is retried on the render thread, the live
     * level), and the lighting switches as they were then.
     */
    private record BuildInput(BundleManifest bundle, BundleMap map, MapPlacement placement, RegionGroup group,
                              net.minecraft.world.level.BlockAndTintGetter world, SurfaceOcclusion.Presence presence,
                              boolean smooth, boolean occlude, long bakeEpoch, boolean bakeCovered) {}

    /** A finished build, still to be uploaded. */
    private static final class BuildResult {
        final Map<PageClass, PackedVertices> meshes = new HashMap<>();
        /** Owned fragments left out because their cell's surface block is gone. */
        int hiddenFragments;
        int drawnFragments;
        /** The distinct owner cells found missing, map-local, packed by {@link #packCell}. */
        final it.unimi.dsi.fastutil.longs.LongArrayList hiddenOwners = new it.unimi.dsi.fastutil.longs.LongArrayList();
        // Light provenance: what sky values this build captured, and which client bake they came
        // from. A mesh holds its light until it is rebuilt, so a build that ran before the bake
        // reached its sections stays wrong for the session.
        int skyMin = 15, skyMax = 0;
        long occlusionTested, occlusionBlocked;
    }

    private record InFlight(BuildInput input, int version, java.util.concurrent.CompletableFuture<BuildResult> result,
                            long startedNanos, boolean lightTimedOut, int unloadedChunks) {}

    /** What a region's last finished build saw, for {@code /src2mc_region_probe}. */
    private record BuildInfo(long finishedNanos, int version, int drawnFragments, int hiddenFragments,
                             boolean lightTimedOut, int unloadedChunks, int meshes) {}
    private static final Map<RegionKey, BuildInfo> BUILD_INFO = new HashMap<>();

    /**
     * Owner cells each built region found without their surface block, so their fragments were
     * left out. They are re-checked against the live world a few thousand per frame, and a region
     * is rebuilt as soon as one of them has its block again.
     *
     * Needed because a block can reach the client with nothing telling the renderer: pasting a
     * map registers its placement while the placer is still writing, regions build at once, and
     * blocks written after that arrived with no rebuild following (seen on INFRA: whole regions
     * built with every owner missing, all present a minute later). Breaking a block by hand is
     * the only other way to hide fragments, so the list is normally short.
     */
    private static final Map<RegionKey, long[]> HIDDEN_OWNERS = new LinkedHashMap<>();
    private static final int OWNER_CHECKS_PER_FRAME = 4096;
    private static List<RegionKey> ownerCheckOrder = List.of();
    private static int ownerCheckRegion, ownerCheckCell;
    private static long ownerRechecks;

    private static long packCell(int x, int y, int z) {
        return ((long) x & 0x1FFFFFL) << 42 | ((long) y & 0x1FFFFFL) << 21 | ((long) z & 0x1FFFFFL);
    }

    private static int unpack(long packed, int shift) {
        return (int) (packed << (64 - 21 - shift) >> (64 - 21));
    }

    /** Round-robin re-check of hidden owner cells; see {@link #HIDDEN_OWNERS}. */
    private static void recheckHiddenOwners() {
        if (HIDDEN_OWNERS.isEmpty()) return;
        if (ownerCheckRegion >= ownerCheckOrder.size()) {
            ownerCheckOrder = List.copyOf(HIDDEN_OWNERS.keySet());
            ownerCheckRegion = 0;
            ownerCheckCell = 0;
        }
        int budget = OWNER_CHECKS_PER_FRAME;
        while (budget > 0 && ownerCheckRegion < ownerCheckOrder.size()) {
            RegionKey key = ownerCheckOrder.get(ownerCheckRegion);
            long[] cells = HIDDEN_OWNERS.get(key);
            if (cells == null || !BUILT_REGIONS.containsKey(key)) { ownerCheckRegion++; ownerCheckCell = 0; continue; }
            boolean appeared = false;
            while (budget > 0 && ownerCheckCell < cells.length) {
                long cell = cells[ownerCheckCell++];
                budget--;
                if (surfacePresent(key.placement(), unpack(cell, 42), unpack(cell, 21), unpack(cell, 0))) { appeared = true; break; }
            }
            if (appeared) {
                HIDDEN_OWNERS.remove(key);
                REGION_VERSIONS.merge(key, 1, Integer::sum);
                BUILT_REGIONS.remove(key);
                ownerRechecks++;
            }
            if (appeared || ownerCheckCell >= cells.length) { ownerCheckRegion++; ownerCheckCell = 0; }
        }
    }

    private MapSurfaceRenderer() {}

    /** Shared campaign atlas residency for every mod-owned static renderer. */
    public static AtlasPageResidency atlasPages() {
        return PAGES;
    }

    @SubscribeEvent
    public static void registerCommand(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(literal("src2mc_debug_face").executes(context -> inspectFace(context.getSource())));
        event.getDispatcher().register(literal("src2mc_render_status").executes(context -> renderStatus(context.getSource())));
        event.getDispatcher().register(literal("src2mc_region_probe").executes(context -> probeRegion(context.getSource())));
        event.getDispatcher().register(literal("src2mc_rebuild_meshes").executes(context -> rebuildMeshes(context.getSource())));
        event.getDispatcher().register(literal("src2mc_iris_entity_id")
            .then(literal("neutral").executes(context -> setNeutralEntityId(context.getSource(), true)))
            .then(literal("captured").executes(context -> setNeutralEntityId(context.getSource(), false))));
        event.getDispatcher().register(literal("src2mc_relight")
            .then(literal("on").executes(context -> setRelight(context.getSource(), true)))
            .then(literal("off").executes(context -> setRelight(context.getSource(), false))));
        var stageCommand = literal("src2mc_render_stage");
        for (RenderLevelStageEvent.Stage stage : List.of(
            RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS,
            RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS,
            RenderLevelStageEvent.Stage.AFTER_ENTITIES,
            RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES,
            RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS)) {
            stageCommand.then(literal(stage.toString()).executes(context -> setOpaqueStage(context.getSource(), stage)));
        }
        event.getDispatcher().register(stageCommand);
        event.getDispatcher().register(literal("src2mc_shadow_distance")
            .then(net.minecraft.commands.Commands.argument("blocks", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0))
                .executes(context -> setShadowDistance(context.getSource(),
                    com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(context, "blocks")))));
        event.getDispatcher().register(literal("src2mc_light_probe").executes(context -> probeLight(context.getSource())));
        event.getDispatcher().register(literal("src2mc_light_occlusion")
            .then(literal("on").executes(context -> setLightOcclusion(context.getSource(), true)))
            .then(literal("off").executes(context -> setLightOcclusion(context.getSource(), false))));
        event.getDispatcher().register(literal("src2mc_smooth_light")
            .then(literal("on").executes(context -> setSmoothLighting(context.getSource(), true)))
            .then(literal("off").executes(context -> setSmoothLighting(context.getSource(), false))));
        event.getDispatcher().register(literal("src2mc_translucent_depth")
            .then(literal("on").executes(context -> setTranslucentDepthWrite(context.getSource(), true)))
            .then(literal("off").executes(context -> setTranslucentDepthWrite(context.getSource(), false))));
        event.getDispatcher().register(literal("src2mc_mipmaps")
            .then(literal("on").executes(context -> setAtlasMipmaps(context.getSource(), true)))
            .then(literal("off").executes(context -> setAtlasMipmaps(context.getSource(), false))));
        event.getDispatcher().register(literal("src2mc_indexed")
            .then(literal("on").executes(context -> setIndexedMeshes(context.getSource(), true)))
            .then(literal("off").executes(context -> setIndexedMeshes(context.getSource(), false))));
        event.getDispatcher().register(literal("src2mc_draw_order")
            .then(literal("sorted").executes(context -> setSortedDraws(context.getSource(), true)))
            .then(literal("unsorted").executes(context -> setSortedDraws(context.getSource(), false))));
        event.getDispatcher().register(literal("src2mc_cull")
            .then(literal("pvs").then(literal("on").executes(context -> setPvsCulling(context.getSource(), true)))
                .then(literal("off").executes(context -> setPvsCulling(context.getSource(), false))))
            .then(literal("frustum").then(literal("on").executes(context -> setFrustumCulling(context.getSource(), true)))
                .then(literal("off").executes(context -> setFrustumCulling(context.getSource(), false))))
            .then(literal("shadow").then(literal("on").executes(context -> setShadowCulling(context.getSource(), true)))
                .then(literal("off").executes(context -> setShadowCulling(context.getSource(), false)))));
    }

    private static int setShadowDistance(net.minecraft.commands.CommandSourceStack source, double blocks) {
        shadowDistance = blocks;
        source.sendSuccess(() -> Component.literal("src2mc shadow caster distance = "
            + (blocks <= 0 ? "unlimited" : blocks + " blocks")), false);
        return 1;
    }

    /** Drops every built mesh, since the light is baked into them at build time. */
    private static int setLightOcclusion(net.minecraft.commands.CommandSourceStack source, boolean value) {
        lightOcclusion = value;
        dropMeshes();
        PropRenderer.invalidateAllLight();
        source.sendSuccess(() -> Component.literal("src2mc light occlusion " + (value ? "on" : "off")
            + ": smooth light " + (value ? "ignores" : "reads") + " cells behind the map's own surfaces"), false);
        return 1;
    }

    /** Drops every built mesh, since the light is baked into them at build time. */
    private static int setSmoothLighting(net.minecraft.commands.CommandSourceStack source, boolean value) {
        smoothLighting = value;
        dropMeshes();
        PropRenderer.invalidateAllLight();
        source.sendSuccess(() -> Component.literal("src2mc smooth lighting " + (value ? "on" : "off")), false);
        return 1;
    }

    private static int setPvsCulling(net.minecraft.commands.CommandSourceStack source, boolean value) {
        pvsCulling = value;
        source.sendSuccess(() -> Component.literal("src2mc PVS culling " + (value ? "on" : "off")), false);
        return 1;
    }

    private static int setTranslucentDepthWrite(net.minecraft.commands.CommandSourceStack source, boolean value) {
        translucentDepthWrite = value;
        source.sendSuccess(() -> Component.literal("src2mc translucent depth write " + (value ? "on" : "off")), false);
        return 1;
    }

    private static int setAtlasMipmaps(net.minecraft.commands.CommandSourceStack source, boolean value) {
        atlasMipmaps = value;
        source.sendSuccess(() -> Component.literal("src2mc atlas mipmaps " + (value ? "on" : "off (vanilla entity sampling)")), false);
        return 1;
    }

    /**
     * Re-enables mipmapping on an atlas page right after its render type was set up, which had
     * just turned it off. Nearest within a level and linear between levels, as vanilla terrain.
     */
    static void applyAtlasFilter(ResourceLocation texture) {
        if (atlasMipmaps) Minecraft.getInstance().getTextureManager().getTexture(texture).setFilter(false, true);
    }

    /** Rebuilds everything, since the layout is chosen when a mesh is uploaded. */
    private static int setIndexedMeshes(net.minecraft.commands.CommandSourceStack source, boolean value) {
        indexedMeshes = value;
        PackedVertices.soupVertices = 0; PackedVertices.uploadedVertices = 0;
        rebuildMeshes(source);
        source.sendSuccess(() -> Component.literal("src2mc indexed meshes " + (value ? "on" : "off")), false);
        return 1;
    }

    private static int setSortedDraws(net.minecraft.commands.CommandSourceStack source, boolean value) {
        sortedDraws = value;
        source.sendSuccess(() -> Component.literal("src2mc opaque draw order " + (value ? "sorted (state groups, nearest first)" : "unsorted")), false);
        return 1;
    }

    private static int setFrustumCulling(net.minecraft.commands.CommandSourceStack source, boolean value) {
        frustumCulling = value;
        source.sendSuccess(() -> Component.literal("src2mc frustum culling " + (value ? "on" : "off")), false);
        return 1;
    }

    private static int setShadowCulling(net.minecraft.commands.CommandSourceStack source, boolean value) {
        shadowCulling = value;
        source.sendSuccess(() -> Component.literal("src2mc shadow-pass frustum culling " + (value ? "on" : "off")
            + (IrisCompat.shadowFrustum() == null && value ? " (no Iris shadow frustum yet; draws everything until one exists)" : "")), false);
        return 1;
    }

    private static int setOpaqueStage(net.minecraft.commands.CommandSourceStack source, RenderLevelStageEvent.Stage stage) {
        opaqueStage = stage;
        source.sendSuccess(() -> Component.literal("src2mc opaque draw stage = " + stage), false);
        return 1;
    }

    private static int setRelight(net.minecraft.commands.CommandSourceStack source, boolean value) {
        LightWatcher.setEnabled(value);
        relightsQueued = 0;
        worstUploadNanos = 0;
        source.sendSuccess(() -> Component.literal("src2mc relight " + (value ? "on" : "off")), false);
        return 1;
    }

    /** The sky light the built meshes' vertices hold; the baked-light shader does not use it. */
    private static String lightProvenance() {
        int skyMin = MESHES.values().stream().mapToInt(mesh -> mesh.skyMin).min().orElse(-1);
        int skyMax = MESHES.values().stream().mapToInt(mesh -> mesh.skyMax).max().orElse(-1);
        return "light: " + MESHES.size() + " meshes, sky range " + skyMin + ".." + skyMax + " (unused: Source's baked light)";
    }

    /** Which vertex layouts the built meshes actually hold, and how many were built with a pack in
     * use. A stride other than 36 means Iris extended the format behind us. */
    private static String vertexFormats() {
        Map<Integer, Long> strides = new java.util.TreeMap<>();
        long withShaders = 0;
        for (Mesh mesh : MESHES.values()) {
            strides.merge(mesh.vertexSize, 1L, Long::sum);
            if (mesh.builtWithShaders) withShaders++;
        }
        int[] ids = IrisCompat.capturedIds();
        return "vertex strides=" + strides + ", built with a pack in use=" + withShaders + "/" + MESHES.size()
            + ", iris entity id " + (neutralEntityId ? "zeroed" : "as captured")
            + (IrisCompat.canSetCapturedIds() ? "" : " (no hook)")
            + ", captured now=" + ids[0] + "/" + ids[1] + "/" + ids[2];
    }

    /**
     * Drops every built mesh so the next frames rebuild them against the light that exists now.
     * Unlike {@code /src2mc reload} this changes nothing else -- same generation, same bake, same
     * atlas residency -- which is what makes it a usable experiment.
     */
    private static int rebuildMeshes(net.minecraft.commands.CommandSourceStack source) {
        int meshes = MESHES.size();
        dropMeshes();
        stillLoading = true;
        int props = PropRenderer.rebuildMeshes();
        source.sendSuccess(() -> Component.literal("src2mc: dropped " + meshes + " surface mesh(es) and "
            + props + " prop batch(es); both rebuild against the current light"), false);
        return 1;
    }

    /** Rebuilds everything, since the ids are written into the vertices at build time. */
    private static int setNeutralEntityId(net.minecraft.commands.CommandSourceStack source, boolean value) {
        neutralEntityId = value;
        rebuildMeshes(source);
        source.sendSuccess(() -> Component.literal("src2mc iris entity id "
            + (value ? "zeroed while building" : "left as captured")
            + (IrisCompat.canSetCapturedIds() ? "" : " (Iris exposes no hook here; setting has no effect)")), false);
        return 1;
    }

    private static int renderStatus(net.minecraft.commands.CommandSourceStack source) {
        AtlasPageResidency.Stats stats = PAGES.stats();
        source.sendSuccess(() -> Component.literal("src2mc render: smooth-light " + (smoothLighting ? "on" : "off")
            + ", light-occlusion " + (lightOcclusion ? "on (last build: " + occlusionBlockedLast + "/" + occlusionTestedLast
                + " neighbour cells out of sight)" : "off")
            + ", regions=" + BUILT_REGIONS.size()
            + ", meshes=" + MESHES.size() + ", atlas resident=" + stats.residentPages() + "/" + stats.trackedPages()
            + ", vram=" + formatBytes(stats.residentVramBytes()) + ", decode-pending=" + formatBytes(stats.pendingRamBytes())
            + ", requests=" + stats.requests() + " (hit=" + stats.hits() + ", miss=" + stats.misses()
            + ", denied=" + stats.denied() + ", evicted=" + stats.evictions() + ", failed=" + stats.decodeFailures() + ")"
            + ", PVS " + (CameraVisibility.row() != null ? "cluster " + CameraVisibility.cluster() + ", " + pvsRejectedRegions + " rejected" : "off")
            + (stillLoading ? ", loading" : "")
            + ", shaderpack=" + (IrisCompat.shaderPackInUse() ? "on" : "off")
            + ", shadow-pass invocations/frame=" + shadowPassCallsLastFrame + " (stages " + SHADOW_STAGES_SEEN + ")"
            + ", shadow draw set=" + shadowDrawnLast + "/" + shadowConsideredLast
            + " (unbuilt " + shadowRejectedUnbuiltLast + ", too far " + shadowRejectedDistanceLast
            + ", outside shadow frustum " + shadowRejectedFrustumLast + ")"
            + ", " + lightProvenance()
            + ", " + vertexFormats()
            + ", opaque stage=" + opaqueStage + ", shadow distance=" + shadowDistance
            + ", relight " + (LightWatcher.enabled() ? "on" : "off")
            + ": watched=" + LightWatcher.watchedSections() + ", checks/tick=" + LightWatcher.checksLastTick()
            + ", invalidated/tick=" + LightWatcher.invalidatedLastTick() + ", queued=" + relightsQueued
            + ", surface-block rebuilds=" + surfaceRebuildsQueued + ", hidden fragments in last build=" + lastHiddenFragments
            + ", builds: " + MeshBuildPool.THREADS + " worker(s), in-flight=" + IN_FLIGHT.size()
            + ", waiting for chunk light=" + waitingLast + " (" + ChunkLightTracker.pending() + " chunk(s) pending)"
            + ", discarded=" + buildsDiscarded + ", failed on a worker=" + buildsFailed
            + ", worker time=" + formatMillis(MeshBuildPool.WORKER_NANOS.get()) + " over " + MeshBuildPool.JOBS.get() + " job(s)"
            + ", sections copied=" + WorldSnapshot.SHARED.copied + " reused=" + WorldSnapshot.SHARED.reused
            + ", last frame: dispatch " + formatMillis(dispatchNanosLast) + ", " + uploadsLast + " upload(s) " + formatMillis(uploadNanosLast)
            + ", worst upload frame=" + formatMillis(worstUploadNanos)), false);
        return 1;
    }

    /**
     * The state of every surface region around the camera: the one it stands in and its
     * neighbours on the same level. For each: whether it is built, building or waiting, what its
     * last build saw, and how many owned fragments the live world would hide now. A region that
     * draws nothing while the live count says it should is a build that saw the wrong world.
     */
    private static int probeRegion(net.minecraft.commands.CommandSourceStack source) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || level == null) { source.sendFailure(Component.literal("No client level")); return 0; }
        var camera = minecraft.gameRenderer.getMainCamera().getPosition();
        BundleGeneration generation = Src2mc.bundles().active();
        long now = System.nanoTime();
        long notDone = IN_FLIGHT.values().stream().filter(flight -> !flight.result.isDone()).count();
        long oldest = IN_FLIGHT.values().stream().mapToLong(flight -> now - flight.startedNanos).max().orElse(0);
        source.sendSuccess(() -> Component.literal("src2mc regions: built=" + BUILT_REGIONS.size() + ", in flight=" + IN_FLIGHT.size()
            + " (" + notDone + " running, oldest " + formatMillis(oldest) + "), waiting=" + WAITING_SINCE.size()
            + ", chunks pending light=" + ChunkLightTracker.pending()
            + ", regions with hidden owners=" + HIDDEN_OWNERS.size() + ", rebuilt because an owner reappeared=" + ownerRechecks), false);
        int shown = 0;
        for (MapPlacement placement : placementSnapshot) {
            BundleMap map = generation.findMap(placement.campaignId(), placement.mapId()).orElse(null);
            if (map == null) continue;
            BlockPos local = placement.toLocal(BlockPos.containing(camera));
            RegionCoord at = regionCoord(Math.floorDiv(local.getX(), 16), Math.floorDiv(local.getY(), 16), Math.floorDiv(local.getZ(), 16));
            var groups = regionGroups(map);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                RegionCoord coord = new RegionCoord(at.x() + dx, at.y(), at.z() + dz);
                RegionGroup group = groups.get(coord);
                if (group == null) continue;
                RegionKey key = new RegionKey(placement, coord);
                int liveHidden = 0, liveOwned = 0;
                for (SurfaceTable.SectionPos section : group.sections()) {
                    List<SurfaceTable.Face> faces = map.surfaces().sections().get(section);
                    if (faces == null) continue;
                    for (SurfaceTable.Face face : faces) {
                        if (!face.owned()) continue;
                        int cell = face.localCell();
                        int x = (section.x() << 4) + (cell & 15), y = (section.y() << 4) + (cell >> 8 & 15), z = (section.z() << 4) + (cell >> 4 & 15);
                        liveOwned++;
                        if (!surfacePresent(placement, x + face.ownerDx(), y + face.ownerDy(), z + face.ownerDz())) liveHidden++;
                    }
                }
                BuildInfo info = BUILD_INFO.get(key);
                InFlight flight = IN_FLIGHT.get(key);
                Long waiting = WAITING_SINCE.get(key);
                Map<PageClass, Mesh> meshes = REGION_MESHES.get(key);
                String state = BUILT_REGIONS.containsKey(key) ? "built" : flight != null ? (flight.result.isDone() ? "finished, not uploaded" : "building")
                    : waiting != null ? "waiting for light " + formatMillis(now - waiting) : "not built";
                String line = String.format(java.util.Locale.ROOT, "  %s region %d,%d,%d%s: %s, version %d, meshes drawn %d%s; live owned fragments %d, hidden now %d",
                    placement.mapId(), coord.x(), coord.y(), coord.z(), dx == 0 && dz == 0 ? " (camera)" : "", state,
                    REGION_VERSIONS.getOrDefault(key, 0), meshes == null ? 0 : meshes.size(),
                    info == null ? ", never finished" : String.format(java.util.Locale.ROOT,
                        ", last build %s ago (version %d): drawn %d, hidden %d, light %s, unloaded chunks %d, meshes %d",
                        formatMillis(now - info.finishedNanos), info.version, info.drawnFragments, info.hiddenFragments,
                        info.lightTimedOut ? "timed out" : "ready", info.unloadedChunks, info.meshes),
                    liveOwned, liveHidden);
                source.sendSuccess(() -> Component.literal(line), false);
                Src2mc.LOGGER.info("src2mc region probe: {}", line);
                shown++;
            }
        }
        if (shown == 0) source.sendFailure(Component.literal("No surface region of a placed map around the camera"));
        return 1;
    }

    /**
     * Finds the surface fragment under the crosshair by casting the view ray through the map's own
     * geometry, and writes how every one of its vertices was lit to config/src2mc/light-probe.txt:
     * each smooth-light corner cell, why it was used or dropped, and what it held.
     */
    private static int probeLight(net.minecraft.commands.CommandSourceStack source) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) { source.sendFailure(Component.literal("No client level")); return 0; }
        var camera = minecraft.gameRenderer.getMainCamera();
        net.minecraft.world.phys.Vec3 eye = camera.getPosition();
        org.joml.Vector3f look = camera.getLookVector();
        double lx = look.x(), ly = look.y(), lz = look.z();
        BundleGeneration generation = Src2mc.bundles().active();
        double best = 32;
        MapPlacement bestPlacement = null; BundleMap bestMap = null; SurfaceTable.Face bestFace = null;
        int[] bestCell = null;
        for (MapPlacement placement : PlacementNetwork.clientIndex(minecraft.level.dimension().location()).view()) {
            BundleMap map = generation.findMap(placement.campaignId(), placement.mapId()).orElse(null);
            if (map == null) continue;
            BlockPos t = placement.translation();
            double ox = eye.x - t.getX(), oy = eye.y - t.getY(), oz = eye.z - t.getZ();
            // Every cell within reach of the ray; a probe runs once, so brute force is fine.
            for (double step = 0; step < best; step += 0.25) {
                int cx = net.minecraft.util.Mth.floor(ox + lx * step), cy = net.minecraft.util.Mth.floor(oy + ly * step), cz = net.minecraft.util.Mth.floor(oz + lz * step);
                for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                    int x = cx + dx, y = cy + dy, z = cz + dz;
                    for (SurfaceTable.Face face : map.surfaces().facesAt(x, y, z)) {
                        if (face.owned() && !surfacePresent(placement, x + face.ownerDx(), y + face.ownerDy(), z + face.ownerDz())) continue;
                        double[] n = face.normal();
                        double facing = n[0] * lx + n[1] * ly + n[2] * lz;
                        if (facing >= 0) continue;
                        double d = n[0] * (x + face.coordinate(0, 0)) + n[1] * (y + face.coordinate(0, 1)) + n[2] * (z + face.coordinate(0, 2));
                        double distance = (d - (n[0] * ox + n[1] * oy + n[2] * oz)) / facing;
                        if (distance <= 0 || distance >= best) continue;
                        double hx = ox + lx * distance, hy = oy + ly * distance, hz = oz + lz * distance;
                        int count = face.vertexCount();
                        boolean inside = true;
                        for (int i = 0; i < count && inside; i++) {
                            int j = (i + 1) % count;
                            double ax = x + face.coordinate(i, 0), ay = y + face.coordinate(i, 1), az = z + face.coordinate(i, 2);
                            double ex = x + face.coordinate(j, 0) - ax, ey = y + face.coordinate(j, 1) - ay, ez = z + face.coordinate(j, 2) - az;
                            double wx = hx - ax, wy = hy - ay, wz = hz - az;
                            inside = (ey * wz - ez * wy) * n[0] + (ez * wx - ex * wz) * n[1] + (ex * wy - ey * wx) * n[2] >= -1e-9;
                        }
                        if (!inside) continue;
                        best = distance; bestPlacement = placement; bestMap = map; bestFace = face; bestCell = new int[]{x, y, z};
                    }
                }
            }
        }
        if (bestFace == null) { source.sendFailure(Component.literal("No src2mc surface under the crosshair within 32 blocks")); return 0; }
        MapPlacement placement = bestPlacement; SurfaceTable.Face face = bestFace; int[] cell = bestCell;
        BlockPos t = placement.translation();
        ClientLevel probeLevel = minecraft.level;
        SurfaceOcclusion occlusion = lightOcclusion
            ? new SurfaceOcclusion(bestMap.surfaces(), t.getX(), t.getY(), t.getZ(), (x, y, z) -> surfacePresent(placement, x, y, z))
            : null;
        double[] n = face.normal();
        StringBuilder out = new StringBuilder();
        out.append(String.format(java.util.Locale.ROOT, "=== %s  map %s  cell (%d,%d,%d)  world cell (%d,%d,%d)  distance %.2f%n",
            java.time.LocalDateTime.now(), placement.mapId(), cell[0], cell[1], cell[2],
            cell[0] + t.getX(), cell[1] + t.getY(), cell[2] + t.getZ(), best));
        out.append(String.format(java.util.Locale.ROOT, "fragment %s owner=(%d,%d,%d) kind=%d source=%d/%d normal=(%.3f,%.3f,%.3f) smooth=%s occlusion=%s%n",
            face.owned() ? "owned" : "unowned", face.ownerDx(), face.ownerDy(), face.ownerDz(), face.provenance(),
            face.sourcePrimary(), face.sourceSecondary(), n[0], n[1], n[2], smoothLighting, lightOcclusion));
        Map<Long, Integer> cache = new HashMap<>();
        for (int i = 0; i < face.vertexCount(); i++) {
            double wx = t.getX() + cell[0] + face.coordinate(i, 0);
            double wy = t.getY() + cell[1] + face.coordinate(i, 1);
            double wz = t.getZ() + cell[2] + face.coordinate(i, 2);
            out.append(String.format(java.util.Locale.ROOT, "  vertex %d world (%.4f,%.4f,%.4f)%n", i, wx, wy, wz));
            out.append(LightSampler.describe(probeLevel, wx, wy, wz, (float) n[0], (float) n[1], (float) n[2], cache, occlusion));
        }
        java.nio.file.Path file = minecraft.gameDirectory.toPath().resolve("config/src2mc/light-probe.txt");
        try {
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.writeString(file, out.toString(), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (java.io.IOException exception) {
            source.sendFailure(Component.literal("Could not write " + file + ": " + exception.getMessage()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("src2mc light probe: " + face.vertexCount() + " vertices of a "
            + (face.owned() ? "owned" : "unowned") + " fragment written to config/src2mc/light-probe.txt"), false);
        return 1;
    }

    private static int inspectFace(net.minecraft.commands.CommandSourceStack source) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.hitResult == null
            || minecraft.hitResult.getType() != HitResult.Type.BLOCK) {
            source.sendFailure(Component.literal("Look directly at a src2mc surface block first"));
            return 0;
        }
        BlockHitResult hit = (BlockHitResult) minecraft.hitResult;
        BlockPos world = hit.getBlockPos();
        var placement = PlacementNetwork.clientIndex(minecraft.level.dimension().location()).at(world).orElse(null);
        if (placement == null) {
            source.sendFailure(Component.literal("No unambiguous src2mc placement contains " + world.toShortString()));
            return 0;
        }
        BundleMap map = Src2mc.bundles().active().findMap(placement.campaignId(), placement.mapId()).orElse(null);
        if (map == null) {
            source.sendFailure(Component.literal("The placement's map is absent from the active generation"));
            return 0;
        }
        BlockPos local = placement.toLocal(world);
        // The fragments lying in this cell, and those in neighbouring cells that this block owns.
        List<SurfaceTable.Face> faces = new ArrayList<>(map.surfaces().facesAt(local.getX(), local.getY(), local.getZ()));
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            if ((dx | dy | dz) == 0) continue;
            for (SurfaceTable.Face face : map.surfaces().facesAt(local.getX() + dx, local.getY() + dy, local.getZ() + dz)) {
                if (face.owned() && face.ownerDx() == -dx && face.ownerDy() == -dy && face.ownerDz() == -dz) faces.add(face);
            }
        }
        source.sendSuccess(() -> Component.literal("src2mc face debug: world=" + world.toShortString()
            + ", local=" + local.toShortString() + ", hit=" + hit.getDirection() + ", records=" + faces.size()), false);
        for (SurfaceTable.Face face : faces) {
            BundleMaterial material = map.materials().get(face.materialId());
            String texture = material.texture() == null ? "none" : material.texture().contentId().substring(0, 12);
            SurfaceTable.UvRegion region = map.surfaces().uvRegions().get(face.uvRegionId());
            double[] uv = region.values();
            String projection = String.format(java.util.Locale.ROOT,
                "uv-rate=[%.4f,%.4f] output=%s",
                Math.sqrt(uv[0] * uv[0] + uv[1] * uv[1] + uv[2] * uv[2]),
                Math.sqrt(uv[4] * uv[4] + uv[5] * uv[5] + uv[6] * uv[6]),
                material.texture() == null ? "none" : material.texture().originalWidth() + "x" + material.texture().originalHeight()
                    + "->" + material.texture().outputWidth() + "x" + material.texture().outputHeight());
            double[] normal = face.normal();
            String geometry = String.format(java.util.Locale.ROOT, "vertices=%d normal=(%.3f,%.3f,%.3f)",
                face.vertexCount(), normal[0], normal[1], normal[2]);
            source.sendSuccess(() -> Component.literal("  " + (face.owned()
                    ? "owned offset=(" + face.ownerDx() + "," + face.ownerDy() + "," + face.ownerDz() + ")" : "unowned")
                + " kind=" + provenanceName(face.provenance()) + " " + geometry
                + " material=" + face.materialId() + " " + material.sourceMaterial()
                + " class=" + material.renderClass() + " texture=" + texture
                + " source=" + face.provenance() + ":" + Long.toUnsignedString(face.sourcePrimary())
                + ":" + Long.toUnsignedString(face.sourceSecondary()) + " " + projection), false);
        }
        return 1;
    }

    private static String provenanceName(int kind) {
        return switch (kind) {
            case 0 -> "brush-side"; case 1 -> "displacement"; case 2 -> "draw-face"; default -> "invalid";
        };
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void render(RenderLevelStageEvent event) {
        boolean shadowPass = IrisCompat.renderingShadowPass();
        if (shadowPass) SHADOW_STAGES_SEEN.add(event.getStage().toString());
        if (event.getStage() == opaqueStage) {
            if (shadowPass) drawShadowPass(event, false); else renderOpaque(event);
        } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            // Translucent geometry is skipped in the shadow pass; it would cast an opaque shadow.
            if (!shadowPass) renderTranslucent(event);
        }
    }

    static RenderLevelStageEvent.Stage opaqueStage() { return opaqueStage; }

    /** The frame count atlas page residency is stamped with; other renderers sharing the pages use it too. */
    public static long currentFrame() { return frame; }

    static boolean pvsCulling() { return pvsCulling; }

    static boolean smoothLighting() { return smoothLighting; }

    static boolean frustumCulling() { return frustumCulling; }

    /**
     * The frustum to test shadow casters with, or null to draw every caster in range. Only Iris's
     * own shadow frustum: the frustum in the shadow pass's stage events is the player's, and
     * culling with it dropped casters behind the player, so sunlight leaked through sealed rooms.
     */
    static net.minecraft.client.renderer.culling.Frustum shadowFrustum() {
        return shadowCulling ? IrisCompat.shadowFrustum() : null;
    }

    static long shadowRejectedFrustumLast() { return shadowRejectedFrustumLast; }

    /** Shared with {@link PropRenderer}; see {@link #translucentDepthWrite}. */
    static boolean translucentDepthWrite() { return translucentDepthWrite; }

    /** Shared with {@link PropRenderer}; see {@link #sortedDraws}. */
    static boolean sortedDraws() { return sortedDraws; }

    /** Shared with {@link PropRenderer}; see {@link #indexedMeshes}. */
    static boolean indexedMeshes() { return indexedMeshes; }

    /** The surface renderer's last finished main-pass frame. */
    static DrawStats.Frame frameStats() { return lastStats; }

    /** Shared with {@link PropRenderer}: both renderers upload the same way and have to agree. */
    static boolean neutralEntityId() { return neutralEntityId; }

    /** True when {@code bounds} is close enough to the camera to be worth casting a shadow. */
    static boolean withinShadowDistance(AABB bounds, net.minecraft.world.phys.Vec3 camera) {
        if (shadowDistance <= 0) return true;
        double dx = Math.max(0, Math.max(bounds.minX - camera.x, camera.x - bounds.maxX));
        double dy = Math.max(0, Math.max(bounds.minY - camera.y, camera.y - bounds.maxY));
        double dz = Math.max(0, Math.max(bounds.minZ - camera.z, camera.z - bounds.maxZ));
        return dx * dx + dy * dy + dz * dz <= shadowDistance * shadowDistance;
    }

    /** Shadow pass: draw only, from whatever the main pass already built, frustum-tested against
     * the sun's frustum. No bookkeeping — advancing {@code frame}, building regions, evicting
     * meshes, or resolving PVS all assume a player camera, which this is not. */
    private static void drawShadowPass(RenderLevelStageEvent event, boolean translucent) {
        shadowPassCallsSinceMainPass++;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || generationSequence < 0) return;
        GpuTimer.begin(GpuTimer.Phase.SURFACES_SHADOW);
        draw(event, Src2mc.bundles().active(), translucent, true);
        GpuTimer.end(GpuTimer.Phase.SURFACES_SHADOW);
    }

    private static void renderOpaque(RenderLevelStageEvent event) {
        shadowPassCallsLastFrame = shadowPassCallsSinceMainPass;
        shadowPassCallsSinceMainPass = 0;
        shadowConsideredLast = shadowConsidered; shadowRejectedUnbuiltLast = shadowRejectedUnbuilt;
        shadowRejectedDistanceLast = shadowRejectedDistance; shadowDrawnLast = shadowDrawn;
        shadowConsidered = 0; shadowRejectedUnbuilt = 0; shadowRejectedDistance = 0; shadowDrawn = 0;
        shadowRejectedFrustumLast = shadowRejectedFrustum; shadowRejectedFrustum = 0;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) { clear(); return; }
        BundleGeneration generation = Src2mc.bundles().active();
        List<MapPlacement> placements = PlacementNetwork.clientIndex(minecraft.level.dimension().location()).view();
        if (minecraft.level != level || generation.sequence() != generationSequence || !placements.equals(placementSnapshot)) {
            clear();
            level = minecraft.level;
            generationSequence = generation.sequence();
            placementSnapshot = placements;
        }
        frame++;
        lastStats = STATS.latch(pvsRejectedRegions);
        GpuTimer.endFrame();
        // PropRenderer draws later in this same event and shares this frame's copies.
        WorldSnapshot.SHARED.newFrame();
        PAGES.pump(frame);
        // A mesh never depends on whether its page has loaded: the draw picks the placeholder or
        // the page each frame, and the geometry is the same either way. Rebuilding the regions
        // whose page just arrived only repeated every build on joining.
        PAGES.drainReadyPages();
        if (generation.sequence() == 0 || placements.isEmpty()) return;

        CameraVisibility.resolve(generation, minecraft);
        pvsRejectedRegions = 0;
        var camera = event.getCamera().getPosition();
        int cameraSectionX = SectionPos.blockToSectionCoord(camera.x);
        int cameraSectionZ = SectionPos.blockToSectionCoord(camera.z);
        int distance = minecraft.options.getEffectiveRenderDistance() + 1;
        List<BuildCandidate> candidates = new ArrayList<>();
        for (MapPlacement placement : placements) {
            var located = generation.findLocatedMap(placement.campaignId(), placement.mapId()).orElse(null);
            if (located == null || located.map().atlas() == null) continue;
            BundleMap map = located.map();
            for (RegionEntry entry : regionEntries(placement, map)) {
                // Regions span several sections: keep one while any of its sections is in range.
                if (sectionGap(cameraSectionX, entry.minSectionX, entry.maxSectionX) > distance
                    || sectionGap(cameraSectionZ, entry.minSectionZ, entry.maxSectionZ) > distance) continue;
                RegionKey regionKey = entry.key;
                if (BUILT_REGIONS.containsKey(regionKey)) {
                    BUILT_REGIONS.put(regionKey, frame);
                } else if (!IN_FLIGHT.containsKey(regionKey)) {
                    candidates.add(new BuildCandidate(located.bundle(), map, entry, distanceSquared(entry.bounds, camera)));
                }
                if (pvsCulling && !regionPvsVisible(map, placement, entry.group.clusters())) { pvsRejectedRegions++; continue; }
                Map<PageClass, Mesh> meshes = REGION_MESHES.get(regionKey);
                if (meshes != null) for (Mesh mesh : meshes.values()) mesh.lastVisibleFrame = frame;
            }
        }
        completeBuilds();
        recheckHiddenOwners();
        dispatchBuilds(minecraft, candidates, distance);
        stillLoading = !candidates.isEmpty() || !IN_FLIGHT.isEmpty();
        discardExpiredMeshes();
        prefetchNearMeshes(generation);
        PAGES.pump(frame);
        PAGES.drainReadyPages();

        GpuTimer.begin(GpuTimer.Phase.SURFACES_OPAQUE);
        draw(event, generation, false, false);
        GpuTimer.end(GpuTimer.Phase.SURFACES_OPAQUE);
    }

    private record BuildCandidate(BundleManifest bundle, BundleMap map, RegionEntry entry, double distanceSquared) {}

    /**
     * Starts builds for the nearest unbuilt regions, as long as workers are free and this frame's
     * copying budget lasts. A region whose chunks are still waiting for their light is held back,
     * for at most {@link #LIGHT_WAIT_NANOS}: built now, it would be rebuilt as soon as that light
     * landed.
     */
    private static void dispatchBuilds(Minecraft minecraft, List<BuildCandidate> candidates, int distance) {
        long started = System.nanoTime();
        candidates.sort(Comparator.comparingDouble(BuildCandidate::distanceSquared));
        var camera = minecraft.gameRenderer.getMainCamera().getPosition();
        int cameraChunkX = SectionPos.blockToSectionCoord(camera.x), cameraChunkZ = SectionPos.blockToSectionCoord(camera.z);
        int waiting = 0;
        Set<RegionKey> inRange = new HashSet<>();
        for (BuildCandidate candidate : candidates) {
            RegionEntry entry = candidate.entry;
            inRange.add(entry.key);
            if (IN_FLIGHT.size() >= MAX_IN_FLIGHT || System.nanoTime() - started >= DISPATCH_BUDGET_NANOS) continue;
            int[] box = snapshotBox(entry);
            boolean timedOut = false;
            if (!ChunkLightTracker.settled(level, box[0], box[2], box[3], box[5], cameraChunkX, cameraChunkZ, distance)) {
                long since = WAITING_SINCE.computeIfAbsent(entry.key, ignored -> System.nanoTime());
                if (System.nanoTime() - since < LIGHT_WAIT_NANOS) { waiting++; continue; }
                timedOut = true;
            }
            int unloaded = 0;
            for (int cx = box[0] >> 4; cx <= box[3] >> 4; cx++) for (int cz = box[2] >> 4; cz <= box[5] >> 4; cz++) {
                if (!level.hasChunk(cx, cz)) unloaded++;
            }
            WAITING_SINCE.remove(entry.key);
            WorldSnapshot world = WorldSnapshot.capture(level, box[0], box[1], box[2], box[3], box[4], box[5]);
            MapPlacement placement = entry.key.placement();
            BuildInput input = new BuildInput(candidate.bundle, candidate.map, placement, entry.group, world,
                (x, y, z) -> snapshotSurfacePresent(world, placement, x, y, z), smoothLighting, lightOcclusion, 0L, true);
            int version = REGION_VERSIONS.getOrDefault(entry.key, 0);
            IN_FLIGHT.put(entry.key, new InFlight(input, version, MeshBuildPool.submit(() -> buildRegion(input)),
                System.nanoTime(), timedOut, unloaded));
        }
        WAITING_SINCE.keySet().retainAll(inRange);
        waitingLast = waiting;
        dispatchNanosLast = System.nanoTime() - started;
    }

    /** World block box a region build reads, inclusive: min x, y, z then max x, y, z. */
    private static int[] snapshotBox(RegionEntry entry) {
        BlockPos t = entry.key.placement().translation();
        RegionGroup group = entry.group;
        return new int[] {
            t.getX() + group.minX() - SNAPSHOT_MARGIN, t.getY() + group.minY() - SNAPSHOT_MARGIN, t.getZ() + group.minZ() - SNAPSHOT_MARGIN,
            t.getX() + group.maxX() + SNAPSHOT_MARGIN, t.getY() + group.maxY() + SNAPSHOT_MARGIN, t.getZ() + group.maxZ() + SNAPSHOT_MARGIN};
    }

    /**
     * Uploads finished builds, oldest first, until this frame's upload budget is spent; the first
     * always goes, so a large region cannot stall the queue. A build that an invalidation
     * overtook is thrown away and its region started again. A build that threw on its worker --
     * most likely a modded block that expects a real level -- is retried here against the live
     * level, the way every build ran before.
     */
    private static void completeBuilds() {
        long started = System.nanoTime();
        int uploads = 0;
        var iterator = IN_FLIGHT.entrySet().iterator();
        while (iterator.hasNext()) {
            if (uploads > 0 && System.nanoTime() - started >= UPLOAD_BUDGET_NANOS) break;
            var entry = iterator.next();
            InFlight flight = entry.getValue();
            if (!flight.result.isDone()) continue;
            iterator.remove();
            if (flight.version != REGION_VERSIONS.getOrDefault(entry.getKey(), 0)) { buildsDiscarded++; continue; }
            BuildResult result;
            try {
                result = flight.result.join();
            } catch (RuntimeException exception) {
                if (buildsFailed++ == 0) Src2mc.LOGGER.warn("src2mc: a surface build failed on a worker; retrying on the render thread", exception);
                BuildInput live = flight.input;
                result = buildRegion(new BuildInput(live.bundle, live.map, live.placement, live.group, level,
                    (x, y, z) -> surfacePresent(live.placement, x, y, z), live.smooth, live.occlude, live.bakeEpoch, live.bakeCovered));
            }
            finishRegionBuild(entry.getKey(), flight.input, result);
            if (result.hiddenOwners.isEmpty()) HIDDEN_OWNERS.remove(entry.getKey());
            else HIDDEN_OWNERS.put(entry.getKey(), result.hiddenOwners.toLongArray());
            BUILD_INFO.put(entry.getKey(), new BuildInfo(System.nanoTime(), flight.version, result.drawnFragments, result.hiddenFragments,
                flight.lightTimedOut, flight.unloadedChunks, result.meshes.size()));
            uploads++;
        }
        uploadsLast = uploads;
        uploadNanosLast = System.nanoTime() - started;
        worstUploadNanos = Math.max(worstUploadNanos, uploadNanosLast);
    }

    /** NeoForge documents AFTER_PARTICLES as the safe basic custom-translucency stage. */
    private static void renderTranslucent(RenderLevelStageEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || generationSequence < 0) return;
        GpuTimer.begin(GpuTimer.Phase.SURFACES_TRANSLUCENT);
        draw(event, Src2mc.bundles().active(), true, false);
        GpuTimer.end(GpuTimer.Phase.SURFACES_TRANSLUCENT);
    }

    private static void draw(RenderLevelStageEvent event, BundleGeneration generation, boolean translucent, boolean shadowPass) {
        if (!BakedLighting.ready()) return;
        long started = System.nanoTime();
        var camera = event.getCamera().getPosition();
        List<DrawItem> drawItems = new ArrayList<>();
        var casterFrustum = shadowPass ? shadowFrustum() : null;
        for (var item : MESHES.entrySet()) {
            MeshKey key = item.getKey();
            Mesh mesh = item.getValue();
            if ((key.renderClass == BundleMaterial.RenderClass.TRANSLUCENT) != translucent) continue;
            if (shadowPass) {
                shadowConsidered++;
                if (!BUILT_REGIONS.containsKey(key.region)) { shadowRejectedUnbuilt++; continue; }
                if (!withinShadowDistance(mesh.bounds, camera)) { shadowRejectedDistance++; continue; }
                // Never the event's frustum here; see shadowFrustum().
                if (casterFrustum != null && !casterFrustum.isVisible(mesh.bounds)) { shadowRejectedFrustum++; continue; }
            } else {
                if (mesh.lastVisibleFrame != frame) continue;
                if (frustumCulling && !event.getFrustum().isVisible(mesh.bounds)) { STATS.frustumRejected++; continue; }
            }
            drawItems.add(new DrawItem(key, mesh, nearestDistanceSquared(mesh.bounds, camera)));
        }
        if (translucent) {
            drawItems.sort(Comparator.comparingDouble(item -> -distanceSquared(item.mesh.bounds, camera)));
        } else if (sortedDraws) {
            // Grouped by render state so consecutive meshes share one setup, and nearest first within
            // a group so the depth test rejects hidden pixels before the (shaderpack's) fragment
            // shader runs on them.
            drawItems.sort(Comparator.<DrawItem>comparingInt(item -> item.key.renderClass.ordinal())
                .thenComparing(item -> item.mesh.bundle.fingerprint())
                .thenComparingInt(item -> item.key.page)
                .thenComparingDouble(DrawItem::distanceSquared));
        }
        if (shadowPass) shadowDrawn += drawItems.size();
        boolean suppressDepthWrite = translucent && !translucentDepthWrite;
        RenderType activeType = null;
        Mesh activeMesh = null;
        BundleMaterial.RenderClass activeClass = null;
        int activePage = -1;
        long triangles = 0;
        for (DrawItem item : drawItems) {
            Mesh mesh = item.mesh;
            MeshKey key = item.key;
            if (activeType == null || key.renderClass != activeClass || key.page != activePage
                || mesh.atlas != activeMesh.atlas || !mesh.bundle.fingerprint().equals(activeMesh.bundle.fingerprint())) {
                if (activeType != null) {
                    activeType.clearRenderState();
                    if (suppressDepthWrite) RenderSystem.depthMask(true);
                }
                ResourceLocation texture = PAGES.request(generation.sequence(), mesh.bundle, mesh.atlas, key.page, frame)
                    .orElseGet(PAGES::placeholderTexture);
                activeType = translucent ? RenderType.entityTranslucent(texture)
                    : key.renderClass == BundleMaterial.RenderClass.SOLID ? RenderType.entitySolid(texture) : RenderType.entityCutout(texture);
                activeType.setupRenderState();
                applyAtlasFilter(texture);
                // entityTranslucent writes depth, so the nearest translucent surface drawn first would
                // hide translucent geometry behind it; keep the depth test but skip the write.
                if (suppressDepthWrite) RenderSystem.depthMask(false);
                activeMesh = mesh;
                activeClass = key.renderClass;
                activePage = key.page;
                if (!shadowPass) STATS.stateSwitches++;
            }
            Matrix4f modelView = new Matrix4f(event.getModelViewMatrix()).translate(
                (float) (mesh.origin.getX() - camera.x),
                (float) (mesh.origin.getY() - camera.y),
                (float) (mesh.origin.getZ() - camera.z));
            mesh.buffer.bind();
            mesh.buffer.drawWithShader(modelView, event.getProjectionMatrix(), BakedLighting.prepare(mesh.light, key.renderClass, null, null));
            triangles += mesh.triangles;
        }
        if (activeType != null) {
            activeType.clearRenderState();
            if (suppressDepthWrite) RenderSystem.depthMask(true);
        }
        VertexBuffer.unbind();
        if (shadowPass) {
            STATS.shadowTriangles += triangles;
        } else {
            STATS.draws += drawItems.size();
            STATS.triangles += triangles;
            STATS.cpuNanos += System.nanoTime() - started;
        }
    }

    private record DrawItem(MeshKey key, Mesh mesh, double distanceSquared) {}

    /** Squared distance from the camera to the nearest point of {@code bounds}; 0 inside. */
    static double nearestDistanceSquared(AABB bounds, net.minecraft.world.phys.Vec3 camera) {
        double dx = Math.max(0, Math.max(bounds.minX - camera.x, camera.x - bounds.maxX));
        double dy = Math.max(0, Math.max(bounds.minY - camera.y, camera.y - bounds.maxY));
        double dz = Math.max(0, Math.max(bounds.minZ - camera.z, camera.z - bounds.maxZ));
        return dx * dx + dy * dy + dz * dz;
    }

    private static void prefetchNearMeshes(BundleGeneration generation) {
        for (var item : MESHES.entrySet()) {
            Mesh mesh = item.getValue();
            if (mesh.lastVisibleFrame == frame) {
                PAGES.request(generation.sequence(), mesh.bundle, mesh.atlas, item.getKey().page, frame);
            }
        }
    }

    private static double distanceSquared(AABB bounds, net.minecraft.world.phys.Vec3 camera) {
        double x = (bounds.minX + bounds.maxX) * 0.5 - camera.x;
        double y = (bounds.minY + bounds.maxY) * 0.5 - camera.y;
        double z = (bounds.minZ + bounds.maxZ) * 0.5 - camera.z;
        return x * x + y * y + z * z;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KiB";
        return String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0));
    }

    private static String formatMillis(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.1fms", nanos / 1.0e6);
    }

    /**
     * Tessellates and lights one whole region, on a worker. Reads nothing but its input: the
     * bundle's immutable tables and the world snapshot taken when the build was started.
     */
    private static BuildResult buildRegion(BuildInput in) {
        BundleMap map = in.map;
        BuildResult result = new BuildResult();
        SurfaceOcclusion occlusion = in.occlude
            ? new SurfaceOcclusion(map.surfaces(), in.placement.translation().getX(), in.placement.translation().getY(),
                in.placement.translation().getZ(), in.presence)
            : null;
        Map<Long, Integer> lightCache = new HashMap<>();
        int baseX = in.group.minX(), baseY = in.group.minY(), baseZ = in.group.minZ();
        var sections = map.surfaces().sections();
        for (SurfaceTable.SectionPos section : in.group.sections()) {
            List<SurfaceTable.Face> faces = sections.get(section);
            if (faces == null) continue;
            int sectionX = section.x() << 4, sectionY = section.y() << 4, sectionZ = section.z() << 4;
            // Fragments are sorted by cell and a cell's fragments usually share one owner, so one
            // lookup serves a run of them.
            int checkedOwner = -1;
            boolean cellPresent = true;
            for (SurfaceTable.Face face : faces) {
                if (face.materialId() < 0 || face.materialId() >= map.materials().size()
                    || face.uvRegionId() < 0 || face.uvRegionId() >= map.surfaces().uvRegions().size()) continue;
                BundleMaterial material = map.materials().get(face.materialId());
                if (!material.textured() || material.renderClass() == BundleMaterial.RenderClass.FALLBACK) continue;
                AtlasIndex.Texture texture = map.atlas().textures().get(material.texture().contentId());
                if (texture == null) continue;
                int local = face.localCell();
                int x = sectionX + (local & 15), y = sectionY + (local >> 8 & 15), z = sectionZ + (local >> 4 & 15);
                if (face.owned()) {
                    int owner = ownerKey(face);
                    if (owner != checkedOwner) {
                        checkedOwner = owner;
                        cellPresent = in.presence.present(x + face.ownerDx(), y + face.ownerDy(), z + face.ownerDz());
                        if (!cellPresent) result.hiddenOwners.add(packCell(x + face.ownerDx(), y + face.ownerDy(), z + face.ownerDz()));
                    }
                    if (!cellPresent) { result.hiddenFragments++; continue; }
                }
                result.drawnFragments++;
                double[] normal = face.normal();
                float nx = (float) normal[0], ny = (float) normal[1], nz = (float) normal[2];
                float[] baked = new float[4];
                for (var triangle : SurfaceTessellator.tessellate(x, y, z, face,
                    map.surfaces().uvRegions().get(face.uvRegionId()), material.texture(), texture, map.atlas().pageSize())) {
                    PackedVertices out = result.meshes.computeIfAbsent(new PageClass(triangle.page(), material.renderClass()), ignored -> new PackedVertices());
                    // The drawn normal is the triangle's own, which a fan of a clipped polygon
                    // shares with its face; light follows the face's.
                    double abx = triangle.b().x() - triangle.a().x(), aby = triangle.b().y() - triangle.a().y(), abz = triangle.b().z() - triangle.a().z();
                    double acx = triangle.c().x() - triangle.a().x(), acy = triangle.c().y() - triangle.a().y(), acz = triangle.c().z() - triangle.a().z();
                    float tx = (float) (aby * acz - abz * acy), ty = (float) (abz * acx - abx * acz), tz = (float) (abx * acy - aby * acx);
                    float length = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
                    if (length > 0) { tx /= length; ty /= length; tz /= length; }
                    for (SurfaceTessellator.Vertex vertex : List.of(triangle.a(), triangle.b(), triangle.c())) {
                        int light = sampleVertexLight(in, result, vertex, nx, ny, nz, lightCache, occlusion);
                        BakedLighting.surfaceLight(map.light(), map.surfaces(), face, vertex.x(), vertex.y(), vertex.z(), nx, ny, nz, baked);
                        out.add((float) (vertex.x() - baseX), (float) (vertex.y() - baseY), (float) (vertex.z() - baseZ),
                            (float) vertex.u(), (float) vertex.v(), tx, ty, tz, light, 0xFFFFFF, baked);
                    }
                    // $nocull: the back is its own triangle, wound the other way and lit from the
                    // side it faces, rather than a render state that would draw it with the
                    // front's light and normal.
                    if (material.doubleSided()) {
                        for (SurfaceTessellator.Vertex vertex : List.of(triangle.a(), triangle.c(), triangle.b())) {
                            int light = sampleVertexLight(in, result, vertex, -nx, -ny, -nz, lightCache, occlusion);
                            // One lightmap for both sides, as Source lights a $nocull face.
                            BakedLighting.surfaceLight(map.light(), map.surfaces(), face, vertex.x(), vertex.y(), vertex.z(), -nx, -ny, -nz, baked);
                            out.add((float) (vertex.x() - baseX), (float) (vertex.y() - baseY), (float) (vertex.z() - baseZ),
                                (float) vertex.u(), (float) vertex.v(), -tx, -ty, -tz, light, 0xFFFFFF, baked);
                        }
                    }
                }
            }
        }
        if (occlusion != null) { result.occlusionTested = occlusion.tested; result.occlusionBlocked = occlusion.blocked; }
        for (PackedVertices vertices : result.meshes.values()) vertices.index();
        return result;
    }

    /** Identifies a fragment's owner cell within its section: its own cell plus the offset. */
    private static int ownerKey(SurfaceTable.Face face) {
        return face.localCell() * 27 + (face.ownerDx() + 1) * 9 + (face.ownerDy() + 1) * 3 + face.ownerDz() + 1;
    }

    /** The visibility test smooth light uses around {@code placement}'s own surfaces, or null when
     * {@code /src2mc_light_occlusion} is off. Shared with props, which stand among the same walls. */
    static SurfaceOcclusion occlusionFor(MapPlacement placement, BundleMap map, SurfaceOcclusion.Presence presence) {
        if (!lightOcclusion || map == null) return null;
        BlockPos t = placement.translation();
        return new SurfaceOcclusion(map.surfaces(), t.getX(), t.getY(), t.getZ(), presence);
    }

    /**
     * Whether the world cell behind map-local cell (x, y, z) still holds the {@code src2mc:surface}
     * block its owned fragments hang on. A chunk the client does not have yet counts as present:
     * {@link #checkOwnedCells} re-examines it on arrival, and guessing absent would blank every
     * region the moment it came into range ahead of its chunks.
     */
    private static boolean surfacePresent(MapPlacement placement, int x, int y, int z) {
        int worldX = placement.translation().getX() + x, worldZ = placement.translation().getZ() + z;
        if (!level.hasChunk(SectionPos.blockToSectionCoord(worldX), SectionPos.blockToSectionCoord(worldZ))) return true;
        return level.getBlockState(new BlockPos(worldX, placement.translation().getY() + y, worldZ))
            .is(Src2mcWorldContent.SURFACE.get());
    }

    /** {@link #surfacePresent} for the prop renderer's render-thread fallback. */
    static boolean surfacePresentLive(MapPlacement placement, int x, int y, int z) {
        return surfacePresent(placement, x, y, z);
    }

    /** {@link #surfacePresent} against a snapshot, for a build on a worker. */
    static boolean snapshotSurfacePresent(WorldSnapshot world, MapPlacement placement, int x, int y, int z) {
        int worldX = placement.translation().getX() + x, worldZ = placement.translation().getZ() + z;
        if (!world.hasChunkAt(worldX, worldZ)) return true;
        return world.getBlockState(new BlockPos(worldX, placement.translation().getY() + y, worldZ))
            .is(Src2mcWorldContent.SURFACE.get());
    }

    private static void finishRegionBuild(RegionKey regionKey, BuildInput input, BuildResult build) {
        RegionGroup group = input.group;
        BlockPos origin = input.placement.translation().offset(group.minX(), group.minY(), group.minZ());
        AABB bounds = regionBounds(input.placement, group);
        BUILT_REGIONS.put(regionKey, frame);
        lastHiddenFragments = build.hiddenFragments;
        if (input.occlude) {
            occlusionTestedLast = build.occlusionTested;
            occlusionBlockedLast = build.occlusionBlocked;
        }
        Map<PageClass, Mesh> meshes = REGION_MESHES.computeIfAbsent(regionKey, ignored -> new HashMap<>());
        // A rebuild after a surface block was broken can leave a page class with nothing in it;
        // its old mesh would otherwise go on drawing the fragments that were just removed.
        meshes.entrySet().removeIf(item -> {
            if (build.meshes.containsKey(item.getKey())) return false;
            MESHES.remove(new MeshKey(regionKey, item.getKey().page, item.getKey().renderClass));
            item.getValue().close();
            return true;
        });
        build.meshes.forEach((pageClass, vertices) -> {
            var uploaded = vertices.upload(neutralEntityId, indexedMeshes);
            // The mesh's own vertices, not the whole region: a page class often fills a corner of it.
            double[] b = vertices.bounds();
            AABB meshBounds = b == null ? bounds
                : new AABB(b[0], b[1], b[2], b[3], b[4], b[5]).move(origin.getX(), origin.getY(), origin.getZ()).inflate(0.01);
            Mesh mesh = new Mesh(input.bundle, input.map.atlas(), uploaded.buffer(), origin, meshBounds, vertices.triangles(), frame,
                input.bakeEpoch, input.bakeCovered, build.skyMin, build.skyMax, uploaded.vertexSize(), IrisCompat.shaderPackInUse());
            mesh.light = input.map.light();
            MESHES.put(new MeshKey(regionKey, pageClass.page, pageClass.renderClass), mesh);
            Mesh old = meshes.put(pageClass, mesh);
            if (old != null) old.close();
        });
        if (meshes.isEmpty()) REGION_MESHES.remove(regionKey);
    }

    /**
     * One sample per vertex, at the vertex's own world position and along its
     * fragment's unit normal (nx, ny, nz), which need not be axis-aligned.
     *
     * One sample per face is what vanilla calls flat lighting, and it steps in
     * whole blocks -- a wall lit by one torch went from cell to cell rather
     * than fading. The eight cells behind a smooth sample all come out of the
     * build's own cache, so the extra cost is lookups in a hash map, not light
     * computations, and nothing changes per frame.
     */
    private static int sampleVertexLight(BuildInput in, BuildResult result, SurfaceTessellator.Vertex vertex, float nx, float ny, float nz,
                                         Map<Long, Integer> cache, SurfaceOcclusion occlusion) {
        MapPlacement placement = in.placement;
        double worldX = placement.translation().getX() + vertex.x();
        double worldY = placement.translation().getY() + vertex.y();
        double worldZ = placement.translation().getZ() + vertex.z();
        int light = in.smooth
            ? LightSampler.smooth(in.world, worldX, worldY, worldZ, nx, ny, nz, cache, occlusion)
            : LightSampler.sample(in.world, worldX, worldY, worldZ, nx, ny, nz, cache);
        int sky = light >> 20 & 0xF;
        result.skyMin = Math.min(result.skyMin, sky);
        result.skyMax = Math.max(result.skyMax, sky);
        return light;
    }

    /** Removes the built mesh for the region overlapping {@code worldSection} so the next frame's
     * build loop rebuilds it with fresh light. Covers the section itself and all 26 neighbours:
     * a smooth sample reads the eight cells around a point up to half a block outside the face,
     * so a change diagonally across a section corner does reach this region's vertices. */
    static void invalidateLight(MapPlacement placement, SectionPos worldSection) {
        BlockPos local = placement.toLocal(new BlockPos(SectionPos.sectionToBlockCoord(worldSection.x()),
            SectionPos.sectionToBlockCoord(worldSection.y()), SectionPos.sectionToBlockCoord(worldSection.z())));
        int sx = Math.floorDiv(local.getX(), 16), sy = Math.floorDiv(local.getY(), 16), sz = Math.floorDiv(local.getZ(), 16);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) invalidateRegionAt(placement, sx + dx, sy + dy, sz + dz);
            }
        }
    }

    private static void invalidateRegionAt(MapPlacement placement, int sectionX, int sectionY, int sectionZ) {
        if (invalidateRegion(placement, sectionX, sectionY, sectionZ)) relightsQueued++;
    }

    /** Drops the built region holding map-local section (x, y, z); true if one was built. */
    private static boolean invalidateRegion(MapPlacement placement, int sectionX, int sectionY, int sectionZ) {
        RegionKey key = new RegionKey(placement, regionCoord(sectionX, sectionY, sectionZ));
        // A build already in flight sampled the pre-change world: bumping the version throws its
        // result away when it lands, and the region is started again.
        REGION_VERSIONS.merge(key, 1, Integer::sum);
        return BUILT_REGIONS.remove(key) != null || IN_FLIGHT.containsKey(key);
    }

    private static RegionCoord regionCoord(int sectionX, int sectionY, int sectionZ) {
        return new RegionCoord(Math.floorDiv(sectionX, REGION_SECTIONS),
            Math.floorDiv(sectionY, REGION_SECTIONS), Math.floorDiv(sectionZ, REGION_SECTIONS));
    }

    /**
     * Rebuilds the regions whose owned fragments may hang on blocks in {@code worldSection},
     * after its {@code src2mc:surface} blocks changed. Narrower than {@link #invalidateLight}: an
     * owner is at most one cell from its fragment, so only map sections overlapping the world
     * section grown by one block can hold one -- a few per axis, since the placement need not be
     * section-aligned, and most of them fall in the same region.
     */
    public static void invalidateSurfaceSection(MapPlacement placement, SectionPos worldSection) {
        BlockPos local = placement.toLocal(new BlockPos(worldSection.minBlockX(), worldSection.minBlockY(), worldSection.minBlockZ()));
        for (int sx = Math.floorDiv(local.getX() - 1, 16); sx <= Math.floorDiv(local.getX() + 16, 16); sx++) {
            for (int sy = Math.floorDiv(local.getY() - 1, 16); sy <= Math.floorDiv(local.getY() + 16, 16); sy++) {
                for (int sz = Math.floorDiv(local.getZ() - 1, 16); sz <= Math.floorDiv(local.getZ() + 16, 16); sz++) {
                    if (invalidateRegion(placement, sx, sy, sz)) surfaceRebuildsQueued++;
                }
            }
        }
    }

    /**
     * A chunk just arrived: a region built or building before it assumed every owner cell in it
     * present ({@link #surfacePresent}). Rebuilds such a region only when some owner cell in the
     * chunk turns out to have lost its block, and only looks at sections whose region is built or
     * in flight, so an ordinary chunk load costs a few map lookups. The column is grown by one
     * block: a fragment bucketed in the neighbouring chunk can be owned by a block in this one.
     */
    public static void checkOwnedCells(ClientLevel chunkLevel, net.minecraft.world.level.chunk.LevelChunk chunk) {
        if (chunkLevel != level || (BUILT_REGIONS.isEmpty() && IN_FLIGHT.isEmpty())) return;
        int chunkMinX = chunk.getPos().getMinBlockX(), chunkMinZ = chunk.getPos().getMinBlockZ();
        BundleGeneration generation = Src2mc.bundles().active();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (MapPlacement placement : placementSnapshot) {
            if (placement.worldMax().getX() < chunkMinX || placement.worldMin().getX() > chunkMinX + 15
                || placement.worldMax().getZ() < chunkMinZ || placement.worldMin().getZ() > chunkMinZ + 15) continue;
            BundleMap map = generation.findMap(placement.campaignId(), placement.mapId()).orElse(null);
            if (map == null) continue;
            var sections = map.surfaces().sections();
            int[] cellMin = map.cellMin(), cellMax = map.cellMax();
            BlockPos translation = placement.translation();
            int localMinX = chunkMinX - translation.getX(), localMinZ = chunkMinZ - translation.getZ();
            for (int sx = Math.floorDiv(localMinX - 1, 16); sx <= Math.floorDiv(localMinX + 16, 16); sx++) {
                for (int sz = Math.floorDiv(localMinZ - 1, 16); sz <= Math.floorDiv(localMinZ + 16, 16); sz++) {
                    for (int sy = Math.floorDiv(cellMin[1] - 1, 16); sy <= Math.floorDiv(cellMax[1] + 1, 16); sy++) {
                        List<SurfaceTable.Face> faces = sections.get(new SurfaceTable.SectionPos(sx, sy, sz));
                        if (faces == null) continue;
                        RegionKey key = new RegionKey(placement, regionCoord(sx, sy, sz));
                        if (!BUILT_REGIONS.containsKey(key) && !IN_FLIGHT.containsKey(key)) continue;
                        if (ownedCellMissing(chunk, faces, translation, sx, sy, sz, chunkMinX, chunkMinZ, cursor)
                            && invalidateRegion(placement, sx, sy, sz)) surfaceRebuildsQueued++;
                    }
                }
            }
        }
    }

    private static boolean ownedCellMissing(net.minecraft.world.level.chunk.LevelChunk chunk, List<SurfaceTable.Face> faces,
                                            BlockPos translation, int sx, int sy, int sz, int chunkMinX, int chunkMinZ,
                                            BlockPos.MutableBlockPos cursor) {
        int checkedOwner = -1;
        for (SurfaceTable.Face face : faces) {
            if (!face.owned()) continue;
            int owner = ownerKey(face);
            if (owner == checkedOwner) continue;
            checkedOwner = owner;
            int local = face.localCell();
            int worldX = translation.getX() + (sx << 4) + (local & 15) + face.ownerDx();
            int worldZ = translation.getZ() + (sz << 4) + (local >> 4 & 15) + face.ownerDz();
            if (worldX < chunkMinX || worldX > chunkMinX + 15 || worldZ < chunkMinZ || worldZ > chunkMinZ + 15) continue;
            cursor.set(worldX, translation.getY() + (sy << 4) + (local >> 8 & 15) + face.ownerDy(), worldZ);
            if (!chunk.getBlockState(cursor).is(Src2mcWorldContent.SURFACE.get())) return true;
        }
        return false;
    }

    /** Groups a map's static sections into {@link #REGION_SECTIONS}-wide cubes, once per map
     * (surface geometry never changes after load), so building/culling/PVS work operates on far
     * fewer, larger units than one-section-at-a-time. */
    private static Map<RegionCoord, RegionGroup> regionGroups(BundleMap map) {
        return REGION_GROUPS.computeIfAbsent(map, MapSurfaceRenderer::buildRegionGroups);
    }

    private static Map<RegionCoord, RegionGroup> buildRegionGroups(BundleMap map) {
        Map<RegionCoord, List<SurfaceTable.SectionPos>> grouped = new HashMap<>();
        for (SurfaceTable.SectionPos section : map.surfaces().sections().keySet()) {
            RegionCoord coord = new RegionCoord(Math.floorDiv(section.x(), REGION_SECTIONS),
                Math.floorDiv(section.y(), REGION_SECTIONS), Math.floorDiv(section.z(), REGION_SECTIONS));
            grouped.computeIfAbsent(coord, ignored -> new ArrayList<>()).add(section);
        }
        Map<RegionCoord, RegionGroup> result = new HashMap<>();
        grouped.forEach((coord, sections) -> {
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (SurfaceTable.SectionPos section : sections) {
                minX = Math.min(minX, section.x() << 4); minY = Math.min(minY, section.y() << 4); minZ = Math.min(minZ, section.z() << 4);
                maxX = Math.max(maxX, (section.x() << 4) + 16); maxY = Math.max(maxY, (section.y() << 4) + 16); maxZ = Math.max(maxZ, (section.z() << 4) + 16);
            }
            result.put(coord, new RegionGroup(sections, unionSectionClusters(map, sections), minX, minY, minZ, maxX, maxY, maxZ));
        });
        return result;
    }

    /** Union of every member section's visible clusters; fails open (returns null, meaning
     * "always visible") if any member section lacks PVS coverage, matching
     * {@link dev.theredja.src2mc.bundle.PropVisibility#visible} treating null/empty as visible. */
    private static short[] unionSectionClusters(BundleMap map, List<SurfaceTable.SectionPos> sections) {
        var pvs = map.pvs();
        if (pvs == null) return null;
        var union = new java.util.TreeSet<Short>();
        for (SurfaceTable.SectionPos section : sections) {
            short[] clusters = pvs.sectionClusters(section.x(), section.y(), section.z());
            if (clusters == null || clusters.length == 0) return null;
            for (short cluster : clusters) union.add(cluster);
        }
        short[] result = new short[union.size()];
        int index = 0;
        for (short cluster : union) result[index++] = cluster;
        return result;
    }

    private static AABB regionBounds(MapPlacement placement, RegionGroup group) {
        BlockPos min = placement.translation().offset(group.minX(), group.minY(), group.minZ());
        BlockPos max = placement.translation().offset(group.maxX(), group.maxY(), group.maxZ());
        return new AABB(min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ()).inflate(0.01);
    }

    private static void discardExpiredMeshes() {
        if (frame % 20 != 0) return;
        List<RegionKey> expired = BUILT_REGIONS.entrySet().stream()
            .filter(item -> frame - item.getValue() > MESH_GRACE_FRAMES).map(Map.Entry::getKey).toList();
        for (RegionKey region : expired) {
            BUILT_REGIONS.remove(region);
            HIDDEN_OWNERS.remove(region);
            Map<PageClass, Mesh> meshes = REGION_MESHES.remove(region);
            if (meshes == null) continue;
            meshes.forEach((pageClass, mesh) -> {
                MESHES.remove(new MeshKey(region, pageClass.page, pageClass.renderClass));
                mesh.close();
            });
        }
    }

    /** Drops every built mesh and every build in flight; the regions build again from scratch. */
    private static void dropMeshes() {
        MESHES.values().forEach(Mesh::close);
        MESHES.clear();
        REGION_MESHES.clear();
        BUILT_REGIONS.clear();
        HIDDEN_OWNERS.clear();
        ownerCheckOrder = List.of(); ownerCheckRegion = 0; ownerCheckCell = 0;
        // Orphaned builds finish on their workers and are never looked at again.
        IN_FLIGHT.clear();
        WAITING_SINCE.clear();
    }

    /** Forwarded from {@link dev.theredja.src2mc.client.ClientLightRefresh}; see {@link ChunkLightTracker}. */
    public static void chunkLoaded(ClientLevel chunkLevel, net.minecraft.world.level.chunk.LevelChunk chunk) {
        ChunkLightTracker.onChunkLoad(chunkLevel, chunk.getPos());
    }

    public static void chunkUnloaded(ClientLevel chunkLevel, net.minecraft.world.level.ChunkPos pos) {
        ChunkLightTracker.onChunkUnload(chunkLevel, pos);
    }

    /** Sections between {@code section} and the nearest section of {@code [min, max]}; 0 inside. */
    private static int sectionGap(int section, int min, int max) {
        return section < min ? min - section : section > max ? section - max : 0;
    }

    /** The placement's regions, with their bounds and section extent worked out once rather than per frame. */
    private static List<RegionEntry> regionEntries(MapPlacement placement, BundleMap map) {
        return PLACEMENT_REGIONS.computeIfAbsent(placement, ignored -> {
            List<RegionEntry> entries = new ArrayList<>();
            regionGroups(map).forEach((coord, group) -> {
                BlockPos translation = placement.translation();
                entries.add(new RegionEntry(new RegionKey(placement, coord), group, regionBounds(placement, group),
                    SectionPos.blockToSectionCoord(translation.getX() + group.minX()),
                    SectionPos.blockToSectionCoord(translation.getX() + group.maxX() - 1),
                    SectionPos.blockToSectionCoord(translation.getZ() + group.minZ()),
                    SectionPos.blockToSectionCoord(translation.getZ() + group.maxZ() - 1)));
            });
            return List.copyOf(entries);
        });
    }

    private static void clear() {
        dropMeshes();
        BakedLighting.clear();
        REGION_GROUPS.clear(); PLACEMENT_REGIONS.clear(); REGION_VERSIONS.clear(); BUILD_INFO.clear();
        WorldSnapshot.SHARED.clear();
        PAGES.reset(-1);
        CameraVisibility.reset();
        level = null; generationSequence = -1; placementSnapshot = List.of(); frame = 0; pvsRejectedRegions = 0; stillLoading = true;
        shadowPassCallsSinceMainPass = 0; shadowPassCallsLastFrame = 0;
        shadowConsidered = 0; shadowRejectedUnbuilt = 0; shadowRejectedDistance = 0; shadowDrawn = 0;
        shadowConsideredLast = 0; shadowRejectedUnbuiltLast = 0; shadowRejectedDistanceLast = 0; shadowDrawnLast = 0;
    }

    /** Only rejects regions in the placement the camera is currently inside; other placements
     * keep today's distance+frustum-only behavior, matching how {@link PropRenderer} treats
     * props belonging to a placement other than the camera's. */
    private static boolean regionPvsVisible(BundleMap map, MapPlacement placement, short[] clusters) {
        if (CameraVisibility.row() == null || !placement.equals(CameraVisibility.placement())) return true;
        var pvs = map.pvs();
        if (pvs == null) return true;
        return pvs.visible(CameraVisibility.row(), clusters);
    }

    private record RegionEntry(RegionKey key, RegionGroup group, AABB bounds,
                               int minSectionX, int maxSectionX, int minSectionZ, int maxSectionZ) {}
    private record RegionCoord(int x, int y, int z) {}
    private record RegionGroup(List<SurfaceTable.SectionPos> sections, short[] clusters,
                                int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {}
    private record RegionKey(MapPlacement placement, RegionCoord coord) {}
    private record MeshKey(RegionKey region, int page, BundleMaterial.RenderClass renderClass) {}
    private record PageClass(int page, BundleMaterial.RenderClass renderClass) {}
    private static final class Mesh implements AutoCloseable {
        final BundleManifest bundle; final AtlasIndex atlas; final VertexBuffer buffer; final BlockPos origin; final AABB bounds;
        final long triangles;
        /** The map's baked light, whose lightmap the mesh's vertices point into; null for none. */
        dev.theredja.src2mc.bundle.LightTable light;
        /** The light this mesh froze in, and where that light came from. */
        final long bakeEpoch; final boolean bakeCovered; final int skyMin; final int skyMax;
        /** The stride the buffer actually went to the GPU with, and whether a pack was in use
         * then: 36 is vanilla NEW_ENTITY, anything larger is Iris's extended entity format. */
        final int vertexSize; final boolean builtWithShaders;
        long lastVisibleFrame;
        Mesh(BundleManifest bundle, AtlasIndex atlas, VertexBuffer buffer, BlockPos origin, AABB bounds, long triangles, long frame,
             long bakeEpoch, boolean bakeCovered, int skyMin, int skyMax, int vertexSize, boolean builtWithShaders) {
            this.bundle = bundle; this.atlas = atlas; this.buffer = buffer; this.origin = origin; this.bounds = bounds; this.triangles = triangles; this.lastVisibleFrame = frame;
            this.bakeEpoch = bakeEpoch; this.bakeCovered = bakeCovered; this.skyMin = skyMin; this.skyMax = skyMax;
            this.vertexSize = vertexSize; this.builtWithShaders = builtWithShaders;
        }
        @Override public void close() { buffer.close(); }
    }
}
