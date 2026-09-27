package dev.theredja.src2mc.client.render;

import static net.minecraft.commands.Commands.literal;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.bundle.AtlasIndex;
import dev.theredja.src2mc.bundle.BundleGeneration;
import dev.theredja.src2mc.bundle.BundleManifest;
import dev.theredja.src2mc.bundle.BundleMaterial;
import dev.theredja.src2mc.bundle.BundleMap;
import dev.theredja.src2mc.bundle.SurfaceTable;
import dev.theredja.src2mc.network.PlacementNetwork;
import dev.theredja.src2mc.world.LightOcclusion;
import dev.theredja.src2mc.world.MapPlacement;
import dev.theredja.src2mc.world.Src2mcWorldContent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
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
    private static final long LOAD_BUILD_BUDGET_NANOS = 150_000_000L;
    private static final long STEADY_BUILD_BUDGET_NANOS = 4_000_000L;
    private static final long MESH_GRACE_FRAMES = 600;
    private static final AtlasPageResidency PAGES = new AtlasPageResidency();
    private static final Map<MeshKey, Mesh> MESHES = new LinkedHashMap<>();
    private static final Map<RegionKey, Long> BUILT_REGIONS = new HashMap<>();
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
    /** Set once a full build pass completes with nothing skipped; gates the large load budget so
     * a later relight (which always skips something at least once) never re-triggers it. */
    private static boolean firstPassComplete;
    private static long relightsQueued;
    /** Region rebuilds caused by surface blocks appearing or vanishing, and the hidden-fragment
     * count of the most recent build; read back through {@code /src2mc_render_status}. */
    private static long surfaceRebuildsQueued;
    private static int lastHiddenFragments;
    private static long lastBuildNanos;
    private static long worstBuildNanos;
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
    private static boolean frustumCulling = true;
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

    /** Region builds in flight, oldest first; bounded so the accumulated triangle lists of
     * half-built regions cannot pile up. */
    private static final Map<RegionKey, PendingBuild> PENDING_BUILDS = new LinkedHashMap<>();
    private static final int MAX_PENDING_BUILDS = 4;

    private static final class PendingBuild {
        final BundleManifest bundle;
        final BundleMap map;
        final MapPlacement placement;
        final RegionGroup group;
        final Map<PageClass, List<LitTriangle>> triangles = new HashMap<>();
        final Map<Long, Integer> lightCache = new HashMap<>();
        /** Keeps smooth light from reaching through the map's own surfaces; null when switched off. */
        final SurfaceOcclusion occlusion;
        int nextSection;
        /** Owned fragments left out because their cell's surface block is gone. */
        int hiddenFragments;
        // Light provenance: what sky values this build captured, and which client bake they came
        // from. A mesh holds its light until it is rebuilt, so a build that ran before the bake
        // reached its sections stays wrong for the session.
        int skyMin = 15, skyMax = 0;
        final long bakeEpoch = LightOcclusion.clientEpoch();
        boolean bakeCovered;

        PendingBuild(BundleManifest bundle, BundleMap map, MapPlacement placement, RegionGroup group) {
            this.bundle = bundle; this.map = map; this.placement = placement; this.group = group;
            this.occlusion = occlusionFor(placement, map);
        }
    }

    private MapSurfaceRenderer() {}

    /** Shared campaign atlas residency for every mod-owned static renderer. */
    static AtlasPageResidency atlasPages() {
        return PAGES;
    }

    @SubscribeEvent
    public static void registerCommand(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(literal("src2mc_debug_face").executes(context -> inspectFace(context.getSource())));
        event.getDispatcher().register(literal("src2mc_render_status").executes(context -> renderStatus(context.getSource())));
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
        event.getDispatcher().register(literal("src2mc_cull")
            .then(literal("pvs").then(literal("on").executes(context -> setPvsCulling(context.getSource(), true)))
                .then(literal("off").executes(context -> setPvsCulling(context.getSource(), false))))
            .then(literal("frustum").then(literal("on").executes(context -> setFrustumCulling(context.getSource(), true)))
                .then(literal("off").executes(context -> setFrustumCulling(context.getSource(), false)))));
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
        MESHES.values().forEach(Mesh::close);
        MESHES.clear();
        BUILT_REGIONS.clear();
        PENDING_BUILDS.clear();
        PropRenderer.invalidateAllLight();
        source.sendSuccess(() -> Component.literal("src2mc light occlusion " + (value ? "on" : "off")
            + ": smooth light " + (value ? "ignores" : "reads") + " cells behind the map's own surfaces"), false);
        return 1;
    }

    /** Drops every built mesh, since the light is baked into them at build time. */
    private static int setSmoothLighting(net.minecraft.commands.CommandSourceStack source, boolean value) {
        smoothLighting = value;
        MESHES.values().forEach(Mesh::close);
        MESHES.clear();
        BUILT_REGIONS.clear();
        PENDING_BUILDS.clear();
        PropRenderer.invalidateAllLight();
        source.sendSuccess(() -> Component.literal("src2mc smooth lighting " + (value ? "on" : "off")), false);
        return 1;
    }

    private static int setPvsCulling(net.minecraft.commands.CommandSourceStack source, boolean value) {
        pvsCulling = value;
        source.sendSuccess(() -> Component.literal("src2mc PVS culling " + (value ? "on" : "off")), false);
        return 1;
    }

    private static int setFrustumCulling(net.minecraft.commands.CommandSourceStack source, boolean value) {
        frustumCulling = value;
        source.sendSuccess(() -> Component.literal("src2mc frustum culling " + (value ? "on" : "off")), false);
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
        worstBuildNanos = 0;
        source.sendSuccess(() -> Component.literal("src2mc relight " + (value ? "on" : "off")), false);
        return 1;
    }

    /**
     * What light the built meshes are holding and where it came from. A mesh freezes its light at
     * build time, so a region built before the client's sky bake covered it keeps open daylight
     * until something rebuilds it -- invisible in vanilla shading, and the whole picture under a
     * shaderpack, which multiplies that sky value into its own sun term.
     */
    private static String lightProvenance() {
        long uncovered = MESHES.values().stream().filter(mesh -> !mesh.bakeCovered).count();
        long epoch = LightOcclusion.clientEpoch();
        long stale = MESHES.values().stream().filter(mesh -> mesh.bakeEpoch != epoch).count();
        int skyMin = MESHES.values().stream().mapToInt(mesh -> mesh.skyMin).min().orElse(-1);
        int skyMax = MESHES.values().stream().mapToInt(mesh -> mesh.skyMax).max().orElse(-1);
        return "light: meshes without a bake=" + uncovered + "/" + MESHES.size()
            + ", built against an older bake=" + stale
            + ", sky range=" + skyMin + ".." + skyMax
            + ", client bake epoch=" + epoch + " from generation " + LightOcclusion.clientGeneration();
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
        MESHES.values().forEach(Mesh::close);
        MESHES.clear();
        BUILT_REGIONS.clear();
        PENDING_BUILDS.clear();
        stillLoading = true;
        firstPassComplete = false;
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
            + " (unbuilt " + shadowRejectedUnbuiltLast + ", too far " + shadowRejectedDistanceLast + ")"
            + ", " + lightProvenance()
            + ", " + vertexFormats()
            + ", opaque stage=" + opaqueStage + ", shadow distance=" + shadowDistance
            + ", relight " + (LightWatcher.enabled() ? "on" : "off")
            + ": watched=" + LightWatcher.watchedSections() + ", checks/tick=" + LightWatcher.checksLastTick()
            + ", invalidated/tick=" + LightWatcher.invalidatedLastTick() + ", queued=" + relightsQueued
            + ", in-flight=" + PENDING_BUILDS.size()
            + ", surface-block rebuilds=" + surfaceRebuildsQueued + ", hidden fragments in last build=" + lastHiddenFragments
            + ", build slice last=" + formatMillis(lastBuildNanos) + " worst=" + formatMillis(worstBuildNanos)), false);
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

    static boolean pvsCulling() { return pvsCulling; }

    static boolean smoothLighting() { return smoothLighting; }

    static boolean frustumCulling() { return frustumCulling; }

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
        draw(event, Src2mc.bundles().active(), translucent, true);
    }

    private static void renderOpaque(RenderLevelStageEvent event) {
        shadowPassCallsLastFrame = shadowPassCallsSinceMainPass;
        shadowPassCallsSinceMainPass = 0;
        shadowConsideredLast = shadowConsidered; shadowRejectedUnbuiltLast = shadowRejectedUnbuilt;
        shadowRejectedDistanceLast = shadowRejectedDistance; shadowDrawnLast = shadowDrawn;
        shadowConsidered = 0; shadowRejectedUnbuilt = 0; shadowRejectedDistance = 0; shadowDrawn = 0;
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
        PAGES.pump(frame);
        invalidateReadyPages(PAGES.drainReadyPages());
        if (generation.sequence() == 0 || placements.isEmpty()) return;

        CameraVisibility.resolve(generation, minecraft);
        pvsRejectedRegions = 0;
        var camera = event.getCamera().getPosition();
        int cameraSectionX = SectionPos.blockToSectionCoord(camera.x);
        int cameraSectionZ = SectionPos.blockToSectionCoord(camera.z);
        int distance = minecraft.options.getEffectiveRenderDistance() + 1;
        long buildBudget = stillLoading && !firstPassComplete ? LOAD_BUILD_BUDGET_NANOS : STEADY_BUILD_BUDGET_NANOS;
        long buildDeadline = System.nanoTime() + buildBudget;
        boolean skippedBuild = false;
        for (MapPlacement placement : placements) {
            var located = generation.findLocatedMap(placement.campaignId(), placement.mapId()).orElse(null);
            if (located == null || located.map().atlas() == null) continue;
            BundleMap map = located.map();
            for (var groupEntry : regionGroups(map).entrySet()) {
                RegionCoord coord = groupEntry.getKey();
                RegionGroup group = groupEntry.getValue();
                AABB bounds = regionBounds(placement, group);
                int regionX = SectionPos.blockToSectionCoord((bounds.minX + bounds.maxX) * 0.5);
                int regionZ = SectionPos.blockToSectionCoord((bounds.minZ + bounds.maxZ) * 0.5);
                if (Math.abs(regionX - cameraSectionX) > distance || Math.abs(regionZ - cameraSectionZ) > distance) continue;
                RegionKey regionKey = new RegionKey(placement, coord);
                if (!BUILT_REGIONS.containsKey(regionKey)) {
                    skippedBuild = true;
                    if (!PENDING_BUILDS.containsKey(regionKey) && PENDING_BUILDS.size() < MAX_PENDING_BUILDS) {
                        PENDING_BUILDS.put(regionKey, new PendingBuild(located.bundle(), map, placement, group));
                    }
                }
                if (BUILT_REGIONS.containsKey(regionKey)) BUILT_REGIONS.put(regionKey, frame);
                if (pvsCulling && !regionPvsVisible(map, placement, group.clusters())) { pvsRejectedRegions++; continue; }
                MESHES.forEach((key, mesh) -> { if (key.region.equals(regionKey)) mesh.lastVisibleFrame = frame; });
            }
        }
        drainPendingBuilds(buildDeadline);
        stillLoading = skippedBuild;
        if (!skippedBuild && PENDING_BUILDS.isEmpty()) firstPassComplete = true;
        discardExpiredMeshes();
        prefetchNearMeshes(generation);
        PAGES.pump(frame);
        invalidateReadyPages(PAGES.drainReadyPages());

        draw(event, generation, false, false);
    }

    /** NeoForge documents AFTER_PARTICLES as the safe basic custom-translucency stage. */
    private static void renderTranslucent(RenderLevelStageEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || generationSequence < 0) return;
        draw(event, Src2mc.bundles().active(), true, false);
    }

    private static void draw(RenderLevelStageEvent event, BundleGeneration generation, boolean translucent, boolean shadowPass) {
        var camera = event.getCamera().getPosition();
        var drawItems = MESHES.entrySet().stream()
            .filter(item -> {
                if (!shadowPass) return item.getValue().lastVisibleFrame == frame;
                shadowConsidered++;
                if (BUILT_REGIONS.containsKey(item.getKey().region)) return true;
                shadowRejectedUnbuilt++;
                return false;
            })
            .filter(item -> (item.getKey().renderClass == BundleMaterial.RenderClass.TRANSLUCENT) == translucent)
            // No frustum test in the shadow pass: the frustum there is the sun's, and rejecting a
            // mesh only keeps it out of the shadow map, which shows up as sunlight leaking through
            // sealed geometry rather than as a hole the player can see.
            .filter(item -> {
                if (!shadowPass) return !frustumCulling || event.getFrustum().isVisible(item.getValue().bounds);
                if (withinShadowDistance(item.getValue().bounds, camera)) return true;
                shadowRejectedDistance++;
                return false;
            })
            .sorted(translucent ? Comparator.<Map.Entry<MeshKey, Mesh>>comparingDouble(item -> -distanceSquared(item.getValue().bounds, camera)) : (left, right) -> 0)
            .toList();
        if (shadowPass) shadowDrawn += drawItems.size();
        for (var item : drawItems) {
            Mesh mesh = item.getValue();
            ResourceLocation texture = PAGES.request(generation.sequence(), mesh.bundle, mesh.atlas, item.getKey().page, frame)
                .orElseGet(PAGES::placeholderTexture);
            RenderType renderType = translucent ? RenderType.entityTranslucent(texture)
                : item.getKey().renderClass == BundleMaterial.RenderClass.SOLID ? RenderType.entitySolid(texture) : RenderType.entityCutout(texture);
            renderType.setupRenderState();
            Matrix4f modelView = new Matrix4f(event.getModelViewMatrix()).translate(
                (float) (mesh.origin.getX() - camera.x),
                (float) (mesh.origin.getY() - camera.y),
                (float) (mesh.origin.getZ() - camera.z));
            mesh.buffer.bind();
            mesh.buffer.drawWithShader(modelView, event.getProjectionMatrix(),
                translucent ? GameRenderer.getRendertypeEntityTranslucentShader()
                    : item.getKey().renderClass == BundleMaterial.RenderClass.SOLID
                        ? GameRenderer.getRendertypeEntitySolidShader() : GameRenderer.getRendertypeEntityCutoutShader());
            renderType.clearRenderState();
        }
        VertexBuffer.unbind();
    }

    private static void prefetchNearMeshes(BundleGeneration generation) {
        for (var item : MESHES.entrySet()) {
            Mesh mesh = item.getValue();
            if (mesh.lastVisibleFrame == frame) {
                PAGES.request(generation.sequence(), mesh.bundle, mesh.atlas, item.getKey().page, frame);
            }
        }
    }

    private static void invalidateReadyPages(List<AtlasPageResidency.PageKey> ready) {
        if (ready.isEmpty()) return;
        var affected = new HashSet<RegionKey>();
        for (var item : MESHES.entrySet()) for (AtlasPageResidency.PageKey page : ready) {
            if (item.getKey().page == page.page() && item.getValue().bundle.fingerprint().equals(page.fingerprint())) {
                affected.add(item.getKey().region);
            }
        }
        if (affected.isEmpty()) return;
        affected.forEach(BUILT_REGIONS::remove);
        MESHES.entrySet().removeIf(item -> {
            if (!affected.contains(item.getKey().region)) return false;
            item.getValue().close();
            return true;
        });
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
     * Advances one region build by whole sections until {@code deadline}, returning true once every
     * section is tessellated. Region builds are split across frames because a 64-block region can
     * hold dozens of sections: run atomically, one relight after a torch placement stalled the frame
     * outright. The old meshes stay bound until the replacement uploads, so nothing flickers.
     */
    private static boolean advanceRegionBuild(PendingBuild build, long deadline) {
        BundleMap map = build.map;
        build.bakeCovered = LightOcclusion.baked(level).covers(
            SectionPos.blockToSectionCoord(build.placement.translation().getX() + build.group.minX()),
            SectionPos.blockToSectionCoord(build.placement.translation().getZ() + build.group.minZ()));
        var sections = map.surfaces().sections();
        while (build.nextSection < build.group.sections().size()) {
            if (System.nanoTime() >= deadline) return false;
            SurfaceTable.SectionPos section = build.group.sections().get(build.nextSection++);
            List<SurfaceTable.Face> faces = sections.get(section);
            if (faces == null) continue;
            int baseX = section.x() << 4, baseY = section.y() << 4, baseZ = section.z() << 4;
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
                int x = baseX + (local & 15), y = baseY + (local >> 8 & 15), z = baseZ + (local >> 4 & 15);
                if (face.owned()) {
                    int owner = ownerKey(face);
                    if (owner != checkedOwner) {
                        checkedOwner = owner;
                        cellPresent = surfacePresent(build.placement, x + face.ownerDx(), y + face.ownerDy(), z + face.ownerDz());
                    }
                    if (!cellPresent) { build.hiddenFragments++; continue; }
                }
                double[] normal = face.normal();
                float nx = (float) normal[0], ny = (float) normal[1], nz = (float) normal[2];
                for (var triangle : SurfaceTessellator.tessellate(x, y, z, face,
                    map.surfaces().uvRegions().get(face.uvRegionId()), material.texture(), texture, map.atlas().pageSize())) {
                    build.triangles.computeIfAbsent(new PageClass(triangle.page(), material.renderClass()), ignored -> new ArrayList<>())
                        .add(new LitTriangle(triangle,
                            sampleVertexLight(build, triangle.a(), nx, ny, nz),
                            sampleVertexLight(build, triangle.b(), nx, ny, nz),
                            sampleVertexLight(build, triangle.c(), nx, ny, nz)));
                }
            }
        }
        return true;
    }

    /**
     * Whether the world cell behind map-local cell (x, y, z) still holds the {@code src2mc:surface}
     * block its owned fragments hang on. A chunk the client does not have yet counts as present:
     * {@link #checkOwnedCells} re-examines it on arrival, and guessing absent would blank every
     * region the moment it came into range ahead of its chunks.
     */
    /** Identifies a fragment's owner cell within its section: its own cell plus the offset. */
    private static int ownerKey(SurfaceTable.Face face) {
        return face.localCell() * 27 + (face.ownerDx() + 1) * 9 + (face.ownerDy() + 1) * 3 + face.ownerDz() + 1;
    }

    /** The visibility test smooth light uses around {@code placement}'s own surfaces, or null when
     * {@code /src2mc_light_occlusion} is off. Shared with props, which stand among the same walls. */
    static SurfaceOcclusion occlusionFor(MapPlacement placement, BundleMap map) {
        if (!lightOcclusion || map == null) return null;
        BlockPos t = placement.translation();
        return new SurfaceOcclusion(map.surfaces(), t.getX(), t.getY(), t.getZ(), (x, y, z) -> surfacePresent(placement, x, y, z));
    }

    private static boolean surfacePresent(MapPlacement placement, int x, int y, int z) {
        int worldX = placement.translation().getX() + x, worldZ = placement.translation().getZ() + z;
        if (!level.hasChunk(SectionPos.blockToSectionCoord(worldX), SectionPos.blockToSectionCoord(worldZ))) return true;
        return level.getBlockState(new BlockPos(worldX, placement.translation().getY() + y, worldZ))
            .is(Src2mcWorldContent.SURFACE.get());
    }

    private static void finishRegionBuild(RegionKey regionKey, PendingBuild build) {
        RegionGroup group = build.group;
        BlockPos origin = build.placement.translation().offset(group.minX(), group.minY(), group.minZ());
        AABB bounds = regionBounds(build.placement, group);
        BUILT_REGIONS.put(regionKey, frame);
        lastHiddenFragments = build.hiddenFragments;
        if (build.occlusion != null) {
            occlusionTestedLast = build.occlusion.tested;
            occlusionBlockedLast = build.occlusion.blocked;
        }
        // A rebuild after a surface block was broken can leave a page class with nothing in it;
        // its old mesh would otherwise go on drawing the fragments that were just removed.
        MESHES.entrySet().removeIf(item -> {
            MeshKey key = item.getKey();
            if (!key.region.equals(regionKey) || build.triangles.containsKey(new PageClass(key.page, key.renderClass))) return false;
            item.getValue().close();
            return true;
        });
        build.triangles.forEach((pageClass, values) -> {
            MeshKey key = new MeshKey(regionKey, pageClass.page, pageClass.renderClass);
            Mesh old = MESHES.put(key, upload(build, build.map.atlas(), origin, bounds, values,
                group.minX(), group.minY(), group.minZ()));
            if (old != null) old.close();
        });
    }

    /** Drains in-flight region builds oldest-first, so a started region finishes before a new one
     * begins and no region is left half-tessellated for long. */
    private static void drainPendingBuilds(long deadline) {
        var iterator = PENDING_BUILDS.entrySet().iterator();
        while (iterator.hasNext() && System.nanoTime() < deadline) {
            var entry = iterator.next();
            long sliceStarted = System.nanoTime();
            boolean finished = advanceRegionBuild(entry.getValue(), deadline);
            lastBuildNanos = System.nanoTime() - sliceStarted;
            worstBuildNanos = Math.max(worstBuildNanos, lastBuildNanos);
            if (finished) {
                finishRegionBuild(entry.getKey(), entry.getValue());
                iterator.remove();
            }
        }
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
    private static int sampleVertexLight(PendingBuild build, SurfaceTessellator.Vertex vertex, float nx, float ny, float nz) {
        MapPlacement placement = build.placement;
        Map<Long, Integer> cache = build.lightCache;
        double worldX = placement.translation().getX() + vertex.x();
        double worldY = placement.translation().getY() + vertex.y();
        double worldZ = placement.translation().getZ() + vertex.z();
        int light = smoothLighting
            ? LightSampler.smooth(level, worldX, worldY, worldZ, nx, ny, nz, cache, build.occlusion)
            : LightSampler.sample(level, worldX, worldY, worldZ, nx, ny, nz, cache);
        int sky = light >> 20 & 0xF;
        build.skyMin = Math.min(build.skyMin, sky);
        build.skyMax = Math.max(build.skyMax, sky);
        return light;
    }

    private static Mesh upload(PendingBuild build, AtlasIndex atlas, BlockPos origin, AABB bounds,
                               List<LitTriangle> triangles, int baseX, int baseY, int baseZ) {
        int capacity = (int) Math.min(Integer.MAX_VALUE, Math.max(4096L, (long) triangles.size() * 3 * 36));
        try (var bytes = new ByteBufferBuilder(capacity)) {
            // Iris writes the captured ids into the extended format as each vertex is added, so
            // the ids have to be neutral for the whole build, not just at the upload call.
            int[] previousIds = neutralEntityId ? IrisCompat.setCapturedIds(0, 0, 0) : null;
            try {
                var builder = new BufferBuilder(bytes, VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.NEW_ENTITY);
                for (var lit : triangles) {
                    SurfaceTessellator.Triangle triangle = lit.triangle();
                    vertex(builder, triangle.a(), baseX, baseY, baseZ, triangle, lit.lightA());
                    vertex(builder, triangle.b(), baseX, baseY, baseZ, triangle, lit.lightB());
                    vertex(builder, triangle.c(), baseX, baseY, baseZ, triangle, lit.lightC());
                }
                try (var data = builder.buildOrThrow()) {
                    var buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                    buffer.bind(); buffer.upload(data); VertexBuffer.unbind();
                    return new Mesh(build.bundle, atlas, buffer, origin, bounds, frame,
                        build.bakeEpoch, build.bakeCovered, build.skyMin, build.skyMax,
                        data.drawState().format().getVertexSize(), IrisCompat.shaderPackInUse());
                }
            } finally {
                IrisCompat.restoreCapturedIds(previousIds);
            }
        }
    }

    private static void vertex(BufferBuilder builder, SurfaceTessellator.Vertex vertex, int baseX, int baseY, int baseZ,
                               SurfaceTessellator.Triangle triangle, int light) {
        double abx = triangle.b().x() - triangle.a().x(), aby = triangle.b().y() - triangle.a().y(), abz = triangle.b().z() - triangle.a().z();
        double acx = triangle.c().x() - triangle.a().x(), acy = triangle.c().y() - triangle.a().y(), acz = triangle.c().z() - triangle.a().z();
        float nx = (float) (aby * acz - abz * acy), ny = (float) (abz * acx - abx * acz), nz = (float) (abx * acy - aby * acx);
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length > 0) { nx /= length; ny /= length; nz /= length; }
        builder.addVertex((float) (vertex.x() - baseX), (float) (vertex.y() - baseY), (float) (vertex.z() - baseZ))
            .setColor(255, 255, 255, 255).setUv((float) vertex.u(), (float) vertex.v()).setOverlay(OverlayTexture.NO_OVERLAY)
            .setLight(light).setNormal(nx, ny, nz);
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
        // A build already in flight sampled the pre-change world, so it has to restart.
        PENDING_BUILDS.remove(key);
        return BUILT_REGIONS.remove(key) != null;
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
        if (chunkLevel != level || (BUILT_REGIONS.isEmpty() && PENDING_BUILDS.isEmpty())) return;
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
                        if (!BUILT_REGIONS.containsKey(key) && !PENDING_BUILDS.containsKey(key)) continue;
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
        List<RegionKey> expired = BUILT_REGIONS.entrySet().stream()
            .filter(item -> frame - item.getValue() > MESH_GRACE_FRAMES).map(Map.Entry::getKey).toList();
        for (RegionKey region : expired) {
            BUILT_REGIONS.remove(region);
            MESHES.entrySet().removeIf(item -> {
                if (!item.getKey().region.equals(region)) return false;
                item.getValue().close();
                return true;
            });
        }
    }

    private static void clear() {
        MESHES.values().forEach(Mesh::close); MESHES.clear(); BUILT_REGIONS.clear(); REGION_GROUPS.clear();
        PENDING_BUILDS.clear();
        PAGES.reset(-1);
        CameraVisibility.reset();
        level = null; generationSequence = -1; placementSnapshot = List.of(); frame = 0; pvsRejectedRegions = 0; stillLoading = true;
        firstPassComplete = false; shadowPassCallsSinceMainPass = 0; shadowPassCallsLastFrame = 0;
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

    private record LitTriangle(SurfaceTessellator.Triangle triangle, int lightA, int lightB, int lightC) {}
    private record RegionCoord(int x, int y, int z) {}
    private record RegionGroup(List<SurfaceTable.SectionPos> sections, short[] clusters,
                                int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {}
    private record RegionKey(MapPlacement placement, RegionCoord coord) {}
    private record MeshKey(RegionKey region, int page, BundleMaterial.RenderClass renderClass) {}
    private record PageClass(int page, BundleMaterial.RenderClass renderClass) {}
    private static final class Mesh implements AutoCloseable {
        final BundleManifest bundle; final AtlasIndex atlas; final VertexBuffer buffer; final BlockPos origin; final AABB bounds;
        /** The light this mesh froze in, and where that light came from. */
        final long bakeEpoch; final boolean bakeCovered; final int skyMin; final int skyMax;
        /** The stride the buffer actually went to the GPU with, and whether a pack was in use
         * then: 36 is vanilla NEW_ENTITY, anything larger is Iris's extended entity format. */
        final int vertexSize; final boolean builtWithShaders;
        long lastVisibleFrame;
        Mesh(BundleManifest bundle, AtlasIndex atlas, VertexBuffer buffer, BlockPos origin, AABB bounds, long frame,
             long bakeEpoch, boolean bakeCovered, int skyMin, int skyMax, int vertexSize, boolean builtWithShaders) {
            this.bundle = bundle; this.atlas = atlas; this.buffer = buffer; this.origin = origin; this.bounds = bounds; this.lastVisibleFrame = frame;
            this.bakeEpoch = bakeEpoch; this.bakeCovered = bakeCovered; this.skyMin = skyMin; this.skyMax = skyMax;
            this.vertexSize = vertexSize; this.builtWithShaders = builtWithShaders;
        }
        @Override public void close() { buffer.close(); }
    }
}
