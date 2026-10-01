package dev.theredja.src2mc.client.render;

import static net.minecraft.commands.Commands.literal;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.bundle.AtlasIndex;
import dev.theredja.src2mc.bundle.BundleGeneration;
import dev.theredja.src2mc.bundle.BundleManifest;
import dev.theredja.src2mc.bundle.BundleMap;
import dev.theredja.src2mc.bundle.BundleMaterial;
import dev.theredja.src2mc.bundle.BundleModel;
import dev.theredja.src2mc.bundle.BundleProp;
import dev.theredja.src2mc.bundle.RuntimeMesh;
import dev.theredja.src2mc.network.PlacementNetwork;
import dev.theredja.src2mc.world.MapPlacement;
import dev.theredja.src2mc.world.Src2mcDataBlockEntity;
import dev.theredja.src2mc.world.Src2mcWorldContent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import org.joml.Matrix4f;

/**
 * Static prop VBOs derived from loaded prop-root blocks. Geometry is merged
 * into one aggregate batch per map placement, section, atlas page, and render
 * class so the draw count does not scale with prop placements. This
 * deliberately uses neither entities nor block-entity renderers, and never
 * touches Sodium's terrain pipeline.
 */
@EventBusSubscriber(modid = Src2mc.MOD_ID, value = Dist.CLIENT)
public final class PropRenderer {
    private static final int PROP_DECODE_LOOKAHEAD = 256;
    /** Props being tessellated on workers to learn which batches they join. */
    private static final int MAX_REGISTERING = 64;
    private static final int MAX_SECTION_BUILDS = MeshBuildPool.THREADS * 2;
    /** Render-thread time per frame for copying the world into new section rebuilds. */
    private static final long DISPATCH_BUDGET_NANOS = 3_000_000L;
    /** Render-thread time per frame for filling and uploading finished sections; one always goes. */
    private static final long UPLOAD_BUDGET_NANOS = 4_000_000L;
    /** Longest a dirty section waits for registration to go quiet or its chunks' light to land. */
    private static final long DIRTY_WAIT_NANOS = 3_000_000_000L;
    private static final int SNAPSHOT_MARGIN = 2;
    private static final int ROOT_RECHECK_FRAMES = 10;
    private static final long MESH_GRACE_FRAMES = 600;
    private static final RuntimeMeshResidency RUNTIME_MESHES = new RuntimeMeshResidency();
    private static final PropBatchAggregator<AggregateKey, PropKey, Mesh> AGGREGATES = new PropBatchAggregator<>();
    private static final Map<PropKey, PropSource> ROOT_DATA = new HashMap<>();
    private static final Map<PropKey, Set<AggregateKey>> PROP_CONTRIBUTIONS = new HashMap<>();
    private static final Map<PropKey, Boolean> ROOTS = new HashMap<>();
    private static final Map<PropKey, RootStatus> ROOT_STATUS = new HashMap<>();
    private static final Set<PropKey> BUILT_PROPS = new HashSet<>();
    private static final Map<PropKey, Long> LAST_VISIBLE = new HashMap<>();
    private static final Map<PropKey, Registration> REGISTERING = new java.util.LinkedHashMap<>();
    /** Every batch key by section, so a section rebuild finds its batches without a scan. */
    private static final Map<SectionKey, Set<AggregateKey>> SECTION_KEYS = new HashMap<>();
    /** Bumped whenever a batch in the section is dirtied; a rebuild against an older value leaves it dirty. */
    private static final Map<SectionKey, Integer> SECTION_VERSIONS = new HashMap<>();
    private static final Map<SectionKey, SectionFlight> SECTION_BUILDS = new java.util.LinkedHashMap<>();
    private static final Map<SectionKey, Long> DIRTY_SINCE = new HashMap<>();
    private static int registrationsStartedLast, sectionsWaitingLast;
    private static long registrationsFailed, sectionsFailed, sectionsOvertaken;
    private static int lastScanSectionX = Integer.MIN_VALUE, lastScanSectionZ = Integer.MIN_VALUE;
    private static ClientLevel level;
    private static long generationSequence = -1;
    private static List<MapPlacement> placementSnapshot = List.of();
    private static long frame;
    private static int nearbyProps;
    private static int nearbyUnbuilt;
    private static int buildsLastFrame;
    private static boolean renderingEnabled = true;
    private static final PropRenderPerf PERF = new PropRenderPerf();
    private static final OcclusionCuller<AggregateKey> OCCLUSION = new OcclusionCuller<>();
    private static long shadowPassCallsSinceMainPass;
    private static long shadowPassCallsLastFrame;
    /** Shadow-pass draw-set accounting, latched by the next main pass. */
    private static long shadowConsidered, shadowRejectedDistance, shadowRejectedFrustum, shadowDrawn;
    private static long shadowConsideredLast, shadowRejectedDistanceLast, shadowRejectedFrustumLast, shadowDrawnLast;

    private PropRenderer() {}

    @SubscribeEvent
    public static void registerCommand(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(literal("src2mc_prop_status").executes(context -> {
            for (Component line : statusLines()) context.getSource().sendSuccess(() -> line, false);
            return 1;
        }));
        event.getDispatcher().register(literal("src2mc_prop_toggle").executes(context -> {
            renderingEnabled = !renderingEnabled;
            context.getSource().sendSuccess(() -> Component.literal("src2mc prop rendering "
                + (renderingEnabled ? "enabled" : "disabled — built geometry is kept warm; run again to re-enable")), false);
            return 1;
        }));
        event.getDispatcher().register(literal("src2mc_prop_occlusion_toggle").executes(context -> {
            boolean enabled = OCCLUSION.toggleEnabled();
            context.getSource().sendSuccess(() -> Component.literal("src2mc prop occlusion "
                + (enabled ? "enabled" : "disabled — props remain rendered, PVS and frustum culling remain active")), false);
            return 1;
        }));
        event.getDispatcher().register(literal("src2mc_prop_overlay_toggle").executes(context -> {
            boolean enabled = PropStatusOverlay.toggle();
            context.getSource().sendSuccess(() -> Component.literal("src2mc prop status overlay " + (enabled ? "enabled" : "disabled")), false);
            return 1;
        }));
    }

    static List<Component> statusLines() {
        long active = ROOT_STATUS.values().stream().filter(status -> status == RootStatus.ACTIVE).count();
        long unloaded = ROOT_STATUS.values().stream().filter(status -> status == RootStatus.UNLOADED).count();
        long missing = ROOT_STATUS.values().stream().filter(status -> status == RootStatus.MISSING).count();
        long schema = ROOT_STATUS.values().stream().filter(status -> status == RootStatus.SCHEMA).count();
        long campaign = ROOT_STATUS.values().stream().filter(status -> status == RootStatus.CAMPAIGN).count();
        long map = ROOT_STATUS.values().stream().filter(status -> status == RootStatus.MAP).count();
        long identity = ROOT_STATUS.values().stream().filter(status -> status == RootStatus.IDENTITY).count();
        RuntimeMeshResidency.Stats runtimeMeshes = RUNTIME_MESHES.stats();
        PropRenderPerf.Snapshot perf = PERF.snapshot();
        PropRenderPerf.Frame last = perf.frame();
        PropRenderPerf.Frame average = perf.windowAverage();
        AtlasPageResidency.Stats atlas = MapSurfaceRenderer.atlasPages().stats();
        long propVbo = estimatedVboBytes();
        OcclusionCuller.Stats occlusion = OCCLUSION.stats();
        long invalidRoots = unloaded + missing + schema + campaign + map + identity;
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("[src2mc] Prop renderer").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
        lines.add(statusLine("Status", renderingEnabled ? "ON" : "OFF", renderingEnabled ? ChatFormatting.GREEN : ChatFormatting.RED)
            .append(detail("  Roots " + active + "/" + ROOTS.size() + "  Batches " + AGGREGATES.size() + "  Built " + BUILT_PROPS.size())));
        lines.add(statusLine("Render (last frame)", last.drawCalls() + " draws  " + formatMillions(last.triangles()) + " triangles  " + formatMs(last.renderMs()), ChatFormatting.YELLOW)
            .append(detail("  PVS " + (CameraVisibility.row() != null ? "cluster " + CameraVisibility.cluster() + ", " + last.pvsRejected() + " rejected" : "off")
                + "  shaderpack=" + (IrisCompat.shaderPackInUse() ? "on" : "off") + " shadow-pass/frame=" + shadowPassCallsLastFrame
                + "  shadow draw set=" + shadowDrawnLast + "/" + shadowConsideredLast + " (too far " + shadowRejectedDistanceLast
                + ", outside shadow frustum " + shadowRejectedFrustumLast + (MapSurfaceRenderer.shadowFrustum() == null ? ", shadow culling off" : "") + ")")));
        String occlusionState = !occlusion.enabled() ? "OFF" : occlusion.supported() ? "ON" : "UNSUPPORTED";
        ChatFormatting occlusionColor = !occlusion.enabled() ? ChatFormatting.RED : occlusion.supported() ? ChatFormatting.GREEN : ChatFormatting.RED;
        lines.add(statusLine("GPU occlusion", occlusionState, occlusionColor)
            .append(detail("  Last: " + occlusion.last().rejectedDraws() + " draws rejected, " + formatMillions(occlusion.last().rejectedTriangles()) + " triangles saved"
                + "  |  Queries " + occlusion.last().issued() + " issued, " + occlusion.pending() + " pending, " + occlusion.last().cameraInside() + " skipped (camera inside)")));
        lines.add(statusLine("120-frame average", occlusion.average().rejectedDraws() + " occlusion-rejected draws/frame", ChatFormatting.GOLD)
            .append(detail("  " + formatMillions(occlusion.average().rejectedTriangles()) + " triangles/frame saved"
                + "  |  " + average.drawCalls() + " draws, " + formatMillions(average.triangles()) + " triangles, " + formatMs(average.renderMs()) + " render")));
        DrawStats.Frame surfaces = MapSurfaceRenderer.frameStats();
        lines.add(statusLine("Surfaces (last frame)", surfaces.draws() + " draws  " + formatMillions(surfaces.triangles()) + " triangles  "
                + formatMs(surfaces.cpuMs()) + " CPU", ChatFormatting.YELLOW)
            .append(detail("  avg " + String.format(java.util.Locale.ROOT, "%.0f", surfaces.averageDraws()) + " draws, " + formatMs(surfaces.averageCpuMs())
                + "  |  frustum-rejected " + surfaces.frustumRejected() + ", PVS-rejected regions " + surfaces.pvsRejectedRegions()
                + ", state switches " + surfaces.stateSwitches() + ", shadow " + formatMillions(surfaces.shadowTriangles()) + " triangles"
                + (MapSurfaceRenderer.sortedDraws() ? "" : "  (draw order UNSORTED)")
                + "  |  indexed " + (MapSurfaceRenderer.indexedMeshes() ? "on" : "off") + ", uploaded vertices "
                + (PackedVertices.soupVertices == 0 ? "-" : String.format(java.util.Locale.ROOT, "%.0f%%", 100.0 * PackedVertices.uploadedVertices / PackedVertices.soupVertices))
                + " of triangle corners")));
        lines.add(statusLine("GPU time (120-frame avg)", GpuTimer.supported() ? "surfaces " + gpuMs(GpuTimer.Phase.SURFACES_OPAQUE)
                + " + " + gpuMs(GpuTimer.Phase.SURFACES_TRANSLUCENT) + ", props " + gpuMs(GpuTimer.Phase.PROPS_OPAQUE) + " + " + gpuMs(GpuTimer.Phase.PROPS_TRANSLUCENT)
                : "unsupported", ChatFormatting.GOLD)
            .append(detail("  (opaque + translucent)  |  shadow pass: surfaces " + gpuMs(GpuTimer.Phase.SURFACES_SHADOW)
                + ", props " + gpuMs(GpuTimer.Phase.PROPS_SHADOW) + " (surfaces outside shadow frustum " + MapSurfaceRenderer.shadowRejectedFrustumLast() + ")  |  prop root scan " + formatMs(average.rootScanMs()) + " avg")));
        lines.add(statusLine("Memory", "Props " + formatBytes(propVbo) + "  |  Atlas " + formatBytes(atlas.residentVramBytes()), ChatFormatting.LIGHT_PURPLE)
            .append(detail("  Meshes " + runtimeMeshes.ready() + " ready, " + runtimeMeshes.pending() + " loading")));
        lines.add(statusLine("Builds", REGISTERING.size() + " registering, " + SECTION_BUILDS.size() + " sections building", ChatFormatting.YELLOW)
            .append(detail("  " + sectionsWaitingLast + " waiting (registration or chunk light), " + AGGREGATES.dirtyCount() + " dirty batches"
                + ", " + sectionsOvertaken + " overtaken, failed " + registrationsFailed + "/" + sectionsFailed
                + "  |  " + MeshBuildPool.THREADS + " workers, " + String.format(java.util.Locale.ROOT, "%.0f", MeshBuildPool.WORKER_NANOS.get() / 1.0e6)
                + " ms worker time over " + MeshBuildPool.JOBS.get() + " jobs (surfaces too)")));
        if (invalidRoots != 0 || runtimeMeshes.failed() != 0) lines.add(statusLine("Warning", invalidRoots + " unavailable roots, " + runtimeMeshes.failed() + " failed meshes", ChatFormatting.RED)
            .append(detail(" (unloaded " + unloaded + ", missing " + missing + ", schema " + schema + ")")));
        return List.copyOf(lines);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void render(RenderLevelStageEvent event) {
        boolean shadowPass = IrisCompat.renderingShadowPass();
        if (event.getStage() == MapSurfaceRenderer.opaqueStage()) {
            if (shadowPass) drawShadowPass(event, false); else renderOpaque(event);
        } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            // Translucent geometry is skipped in the shadow pass; it would cast an opaque shadow.
            if (!shadowPass) renderTranslucent(event);
        }
    }

    /** Shadow pass: draw only, from already-built aggregates. No root scan, prop build, aggregate
     * rebuild, {@code frame} advance, or {@code LAST_VISIBLE} stamping — all of that assumes a
     * player camera driving residency, which the sun's camera is not. */
    private static void drawShadowPass(RenderLevelStageEvent event, boolean translucent) {
        shadowPassCallsSinceMainPass++;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || generationSequence < 0) return;
        if (!renderingEnabled) return;
        GpuTimer.begin(GpuTimer.Phase.PROPS_SHADOW);
        draw(event, Src2mc.bundles().active(), translucent, true);
        GpuTimer.end(GpuTimer.Phase.PROPS_SHADOW);
    }

    private static void renderOpaque(RenderLevelStageEvent event) {
        shadowPassCallsLastFrame = shadowPassCallsSinceMainPass;
        shadowPassCallsSinceMainPass = 0;
        shadowConsideredLast = shadowConsidered; shadowRejectedDistanceLast = shadowRejectedDistance;
        shadowDrawnLast = shadowDrawn; shadowRejectedFrustumLast = shadowRejectedFrustum;
        shadowConsidered = 0; shadowRejectedDistance = 0; shadowRejectedFrustum = 0; shadowDrawn = 0;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) { clear(); return; }
        BundleGeneration generation = Src2mc.bundles().active();
        List<MapPlacement> placements = PlacementNetwork.clientIndex(minecraft.level.dimension().location()).view();
        if (minecraft.level != level || generation.sequence() != generationSequence || !placements.equals(placementSnapshot)) {
            clear(); level = minecraft.level; generationSequence = generation.sequence(); placementSnapshot = placements;
        }
        frame++;
        PERF.beginFrame();
        MapSurfaceRenderer.atlasPages().pump(frame);
        if (generation.sequence() == 0 || placements.isEmpty()) return;
        long scanStart = System.nanoTime();
        int scannedRoots = updateRoots(generation, minecraft.level, placements);
        if (scannedRoots >= 0) {
            PERF.add(PropRenderPerf.M_ROOT_SCAN_NANOS, System.nanoTime() - scanStart);
            PERF.add(PropRenderPerf.M_ROOT_SCAN_PROPS, scannedRoots);
        }
        long buildStart = System.nanoTime();
        completeRegistrations();
        buildVisibleProps(generation, minecraft, placements);
        PERF.add(PropRenderPerf.M_BUILD_NANOS, System.nanoTime() - buildStart);
        discardExpiredMeshes();
        completeSectionBuilds();
        dispatchSectionBuilds(generation, minecraft);
        CameraVisibility.resolve(generation, minecraft);
        OCCLUSION.beginFrame(frame);
        if (!renderingEnabled) return;
        GpuTimer.begin(GpuTimer.Phase.PROPS_OPAQUE);
        draw(event, generation, false, false);
        GpuTimer.end(GpuTimer.Phase.PROPS_OPAQUE);
    }

    private static void renderTranslucent(RenderLevelStageEvent event) {
        if (Minecraft.getInstance().level == null || generationSequence < 0 || !renderingEnabled) return;
        GpuTimer.begin(GpuTimer.Phase.PROPS_TRANSLUCENT);
        draw(event, Src2mc.bundles().active(), true, false);
        GpuTimer.end(GpuTimer.Phase.PROPS_TRANSLUCENT);
    }

    /** @return the number of roots inspected, or -1 when this frame skipped the periodic recheck. */
    private static int updateRoots(BundleGeneration generation, ClientLevel clientLevel, List<MapPlacement> placements) {
        if (frame % ROOT_RECHECK_FRAMES != 1) return -1;
        Set<PropKey> seen = new HashSet<>();
        for (MapPlacement placement : placements) {
            BundleMap map = generation.findMap(placement.campaignId(), placement.mapId()).orElse(null);
            if (map == null) continue;
            for (BundleProp prop : map.props()) {
                PropKey key = new PropKey(placement, prop.stableId()); seen.add(key);
                RootStatus status = rootStatus(clientLevel, placement, map, prop);
                boolean active = status == RootStatus.ACTIVE;
                Boolean wasActive = ROOTS.put(key, active);
                ROOT_STATUS.put(key, status);
                if (wasActive != null && wasActive != active) removeProp(key);
            }
        }
        ROOTS.keySet().removeIf(key -> {
            if (seen.contains(key)) return false;
            removeProp(key); ROOT_STATUS.remove(key); return true;
        });
        return seen.size();
    }

    private static RootStatus rootStatus(ClientLevel clientLevel, MapPlacement placement, BundleMap map, BundleProp prop) {
        int[] root = prop.rootCell();
        BlockPos position = placement.translation().offset(root[0], root[1], root[2]);
        // hasChunkAt avoids forcing a client chunk load merely to render a prop.
        if (!clientLevel.hasChunkAt(position)) return RootStatus.UNLOADED;
        if (!clientLevel.getBlockState(position).is(Src2mcWorldContent.PROP_ROOT.get())) return RootStatus.MISSING;
        BlockEntity blockEntity = clientLevel.getBlockEntity(position);
        if (!(blockEntity instanceof Src2mcDataBlockEntity data)) return RootStatus.SCHEMA;
        CompoundTag tag = data.payload();
        // The immutable, hash-validated bundle table is the authority for the
        // transform and model reference. WorldEdit preserves the identity but
        // may rewrite numeric NBT representation during its Sponge-v3 paste.
        // Requiring an exact re-serialization here would reject an otherwise
        // valid root. Its expected world cell still prevents moved/copied roots
        // from rendering as the original placement.
        if (tag.getInt("schema_version") != 1) return RootStatus.SCHEMA;
        if (!placement.campaignId().equals(tag.getString("campaign_id"))) return RootStatus.CAMPAIGN;
        if (!map.mapId().equals(tag.getString("map_id"))) return RootStatus.MAP;
        return prop.stableId().equals(tag.getString("stable_id")) ? RootStatus.ACTIVE : RootStatus.IDENTITY;
    }

    private static void buildVisibleProps(BundleGeneration generation, Minecraft minecraft, List<MapPlacement> placements) {
        var camera = minecraft.gameRenderer.getMainCamera().getPosition();
        int cameraSectionX = SectionPos.blockToSectionCoord(camera.x), cameraSectionZ = SectionPos.blockToSectionCoord(camera.z);
        // With nothing left to register and the camera in the same section, the walk over every
        // prop of every placement only restamps residency, which has 600 frames of grace. It
        // still runs on the frames the roots are rechecked, which is when new roots appear.
        boolean moved = cameraSectionX != lastScanSectionX || cameraSectionZ != lastScanSectionZ;
        if (!moved && nearbyUnbuilt == 0 && REGISTERING.isEmpty() && frame % ROOT_RECHECK_FRAMES != 1) return;
        lastScanSectionX = cameraSectionX; lastScanSectionZ = cameraSectionZ;
        int distance = minecraft.options.getEffectiveRenderDistance() + 1;
        List<BuildCandidate> candidates = new ArrayList<>();
        int nearbyBuilt = 0;
        for (MapPlacement placement : placements) {
            var located = generation.findLocatedMap(placement.campaignId(), placement.mapId()).orElse(null);
            if (located == null || located.map().atlas() == null) continue;
            BundleMap map = located.map();
            for (BundleProp prop : map.props()) {
                PropKey key = new PropKey(placement, prop.stableId());
                if (!ROOTS.getOrDefault(key, false)) continue;
                double[] translation = prop.translation();
                int sx = SectionPos.blockToSectionCoord(placement.translation().getX() + translation[0]);
                int sz = SectionPos.blockToSectionCoord(placement.translation().getZ() + translation[2]);
                if (Math.abs(sx - cameraSectionX) > distance || Math.abs(sz - cameraSectionZ) > distance) continue;
                // Residency follows render distance, not the camera frustum. Looking away must not
                // make a nearby prop expire and slowly rebuild when the player turns around.
                if (BUILT_PROPS.contains(key)) { LAST_VISIBLE.put(key, frame); nearbyBuilt++; continue; }
                if (REGISTERING.containsKey(key)) continue;
                double x = placement.translation().getX() + translation[0] - camera.x;
                double y = placement.translation().getY() + translation[1] - camera.y;
                double z = placement.translation().getZ() + translation[2] - camera.z;
                candidates.add(new BuildCandidate(new PropSource(located.bundle(), map, placement, prop), x * x + y * y + z * z));
            }
        }
        candidates.sort(Comparator.comparingDouble(BuildCandidate::distanceSquared));
        nearbyProps = nearbyBuilt + candidates.size() + REGISTERING.size();
        nearbyUnbuilt = candidates.size() + REGISTERING.size();
        int started = 0;
        for (int index = 0; index < Math.min(candidates.size(), PROP_DECODE_LOOKAHEAD) && REGISTERING.size() < MAX_REGISTERING; index++) {
            PropSource source = candidates.get(index).source();
            if (source.prop().modelIndex() < 0 || source.prop().modelIndex() >= source.map().models().size()) continue;
            RuntimeMesh mesh = RUNTIME_MESHES.request(generation.sequence(), source.bundle(), source.map().models().get(source.prop().modelIndex()).contentId()).orElse(null);
            if (mesh == null) continue;
            REGISTERING.put(new PropKey(source.placement(), source.prop().stableId()),
                new Registration(source, MeshBuildPool.submit(() -> batchesOf(source, mesh))));
            started++;
        }
        registrationsStartedLast = started;
    }

    /** The batches a prop's triangles land in; tessellated on a worker, where the geometry is thrown away again. */
    private static Set<BatchKey> batchesOf(PropSource source, RuntimeMesh mesh) {
        return Set.copyOf(tessellateProp(source, mesh).keySet());
    }

    /** Registers the props whose batches are known on the aggregates they contribute to. */
    private static void completeRegistrations() {
        int builds = 0;
        var iterator = REGISTERING.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            Registration registration = entry.getValue();
            if (!registration.batches.isDone()) continue;
            iterator.remove();
            PropKey propKey = entry.getKey();
            Set<BatchKey> batches;
            try {
                batches = registration.batches.join();
            } catch (RuntimeException exception) {
                if (registrationsFailed++ == 0) Src2mc.LOGGER.warn("src2mc: tessellating a prop failed; it is left out", exception);
                batches = Set.of();
            }
            PropSource source = registration.source;
            ROOT_DATA.put(propKey, source);
            Set<AggregateKey> contributions = PROP_CONTRIBUTIONS.computeIfAbsent(propKey, ignored -> new HashSet<>());
            for (BatchKey batch : batches) {
                AggregateKey key = new AggregateKey(source.placement(), batch.section().x(), batch.section().y(), batch.section().z(), batch.page(), batch.renderClass());
                if (contributions.add(key)) {
                    AGGREGATES.register(key, propKey);
                    SECTION_KEYS.computeIfAbsent(sectionOf(key), ignored -> new HashSet<>()).add(key);
                    touch(key);
                }
            }
            BUILT_PROPS.add(propKey);
            LAST_VISIBLE.put(propKey, frame);
            builds++;
        }
        buildsLastFrame = builds;
        PERF.add(PropRenderPerf.M_BUILDS, builds);
    }

    /** Tessellates one prop into map-local (section, page, render class) triangle batches. */
    private static Map<BatchKey, List<PropTessellator.Triangle>> tessellateProp(PropSource source, RuntimeMesh mesh) {
        BundleMap map = source.map();
        BundleProp prop = source.prop();
        BundleModel model = map.models().get(prop.modelIndex());
        Map<BatchKey, List<PropTessellator.Triangle>> triangles = new HashMap<>();
        for (RuntimeMesh.Submesh submesh : mesh.submeshes()) {
            if (submesh.materialSlot() < 0 || submesh.materialSlot() >= model.materialSlotCount()) continue;
            int materialId = model.materialIds()[submesh.materialSlot()];
            if (materialId < 0 || materialId >= map.materials().size()) continue;
            BundleMaterial material = map.materials().get(materialId);
            if (!material.textured() || material.renderClass() == BundleMaterial.RenderClass.FALLBACK) continue;
            AtlasIndex.Texture texture = map.atlas().textures().get(material.texture().contentId());
            if (texture == null) continue;
            List<PropTessellator.Triangle> tessellated = PropTessellator.tessellate(mesh, submesh, prop, material.texture(), texture, map.atlas().pageSize());
            // $nocull: the back is its own triangle, wound the other way with the normal turned
            // round, so it is culled, lit and shaded from the side it faces.
            if (material.doubleSided()) tessellated = PropTessellator.withBackFaces(tessellated);
            for (PropTessellator.Triangle triangle : tessellated) {
                for (Section section : coveredSections(triangle)) {
                    for (PropTessellator.Triangle clipped : PropTessellator.clipSection(triangle, section.x << 4, section.y << 4, section.z << 4)) {
                        triangles.computeIfAbsent(new BatchKey(section, clipped.page(), material.renderClass()), ignored -> new ArrayList<>()).add(clipped);
                    }
                }
            }
        }
        return triangles;
    }

    /**
     * Starts rebuilds of sections with dirty batches, nearest first. A section is rebuilt whole --
     * every page and render class in it -- so each contributing prop is tessellated once per
     * rebuild rather than once per batch.
     *
     * While props are still being registered, rebuilding would only be repeated as the next ones
     * join the same sections, so rebuilds wait until registration goes quiet -- or a section has
     * been dirty for {@link #DIRTY_WAIT_NANOS}. A section whose chunks' light has not landed yet
     * waits for that too, as surface regions do.
     */
    private static void dispatchSectionBuilds(BundleGeneration generation, Minecraft minecraft) {
        long started = System.nanoTime();
        boolean registering = !REGISTERING.isEmpty() || registrationsStartedLast > 0;
        var camera = minecraft.gameRenderer.getMainCamera().getPosition();
        int cameraChunkX = SectionPos.blockToSectionCoord(camera.x), cameraChunkZ = SectionPos.blockToSectionCoord(camera.z);
        int distance = minecraft.options.getEffectiveRenderDistance() + 1;
        Map<SectionKey, Double> dirty = new HashMap<>();
        for (AggregateKey key : AGGREGATES.dirtyKeys()) {
            SectionKey section = sectionOf(key);
            if (dirty.containsKey(section) || SECTION_BUILDS.containsKey(section)) continue;
            BlockPos center = section.placement().translation().offset((section.x() << 4) + 8, (section.y() << 4) + 8, (section.z() << 4) + 8);
            double dx = center.getX() - camera.x, dy = center.getY() - camera.y, dz = center.getZ() - camera.z;
            dirty.put(section, dx * dx + dy * dy + dz * dz);
        }
        DIRTY_SINCE.keySet().retainAll(dirty.keySet());
        List<Map.Entry<SectionKey, Double>> ordered = new ArrayList<>(dirty.entrySet());
        ordered.sort(Map.Entry.comparingByValue());
        int waiting = 0;
        for (var item : ordered) {
            if (SECTION_BUILDS.size() >= MAX_SECTION_BUILDS || System.nanoTime() - started >= DISPATCH_BUDGET_NANOS) break;
            SectionKey section = item.getKey();
            long since = DIRTY_SINCE.computeIfAbsent(section, ignored -> System.nanoTime());
            boolean overdue = System.nanoTime() - since >= DIRTY_WAIT_NANOS;
            if (registering && !overdue) { waiting++; continue; }
            BlockPos t = section.placement().translation();
            int minX = t.getX() + (section.x() << 4) - SNAPSHOT_MARGIN, minY = t.getY() + (section.y() << 4) - SNAPSHOT_MARGIN;
            int minZ = t.getZ() + (section.z() << 4) - SNAPSHOT_MARGIN;
            int maxX = minX + 15 + 2 * SNAPSHOT_MARGIN, maxY = minY + 15 + 2 * SNAPSHOT_MARGIN, maxZ = minZ + 15 + 2 * SNAPSHOT_MARGIN;
            if (!overdue && !ChunkLightTracker.settled(level, minX, minZ, maxX, maxZ, cameraChunkX, cameraChunkZ, distance)) { waiting++; continue; }
            List<Contributor> contributors = new ArrayList<>();
            Set<PropKey> seen = new HashSet<>();
            for (AggregateKey key : SECTION_KEYS.getOrDefault(section, Set.of())) {
                for (PropKey contributor : AGGREGATES.contributors(key)) {
                    if (!seen.add(contributor)) continue;
                    PropSource source = ROOT_DATA.get(contributor);
                    if (source == null || source.prop().modelIndex() < 0 || source.prop().modelIndex() >= source.map().models().size()) continue;
                    // Decoded meshes are cached per generation with no eviction, so a prop built
                    // once can always be re-tessellated for a rebuild.
                    RuntimeMesh mesh = RUNTIME_MESHES.request(generation.sequence(), source.bundle(),
                        source.map().models().get(source.prop().modelIndex()).contentId()).orElse(null);
                    if (mesh != null) contributors.add(new Contributor(source, mesh));
                }
            }
            WorldSnapshot world = WorldSnapshot.capture(level, minX, minY, minZ, maxX, maxY, maxZ);
            MapPlacement placement = section.placement();
            BundleMap map = generation.findMap(placement.campaignId(), placement.mapId()).orElse(null);
            SectionInput input = new SectionInput(section, List.copyOf(contributors), world,
                MapSurfaceRenderer.occlusionFor(placement, map, (x, y, z) -> MapSurfaceRenderer.snapshotSurfacePresent(world, placement, x, y, z)),
                MapSurfaceRenderer.smoothLighting());
            SECTION_BUILDS.put(section, new SectionFlight(input, SECTION_VERSIONS.getOrDefault(section, 0),
                MeshBuildPool.submit(() -> buildSection(input))));
            DIRTY_SINCE.remove(section);
        }
        sectionsWaitingLast = waiting;
        PERF.add(PropRenderPerf.M_REBUILD_NANOS, System.nanoTime() - started);
    }

    /** Tessellates and lights every batch of one section, on a worker. */
    private static Map<PageClass, PackedVertices> buildSection(SectionInput in) {
        SectionKey section = in.section;
        Section at = new Section(section.x(), section.y(), section.z());
        int baseX = section.x() << 4, baseY = section.y() << 4, baseZ = section.z() << 4;
        Map<Long, Integer> lightCache = new HashMap<>();
        Map<PageClass, PackedVertices> result = new HashMap<>();
        for (Contributor contributor : in.contributors) {
            for (var batch : tessellateProp(contributor.source, contributor.mesh).entrySet()) {
                if (!batch.getKey().section().equals(at)) continue;
                PackedVertices out = result.computeIfAbsent(new PageClass(batch.getKey().page(), batch.getKey().renderClass()), ignored -> new PackedVertices());
                for (PropTessellator.Triangle triangle : batch.getValue()) {
                    for (PropTessellator.Vertex vertex : List.of(triangle.a(), triangle.b(), triangle.c())) {
                        int light = sampleVertexLight(in, section.placement(), vertex, lightCache);
                        out.add((float) (vertex.x() - baseX), (float) (vertex.y() - baseY), (float) (vertex.z() - baseZ),
                            (float) vertex.u(), (float) vertex.v(), (float) vertex.nx(), (float) vertex.ny(), (float) vertex.nz(), light);
                    }
                }
            }
        }
        for (PackedVertices vertices : result.values()) vertices.index();
        return result;
    }

    /**
     * Uploads finished section rebuilds, oldest first, within this frame's budget; the first always
     * goes. A rebuild that a later change overtook still goes up -- it is closer to the truth than
     * what is drawn -- but its batches stay dirty and are rebuilt again. One that threw on its
     * worker is redone here against the live level.
     */
    private static void completeSectionBuilds() {
        long started = System.nanoTime();
        int uploaded = 0;
        var iterator = SECTION_BUILDS.entrySet().iterator();
        while (iterator.hasNext()) {
            if (uploaded > 0 && System.nanoTime() - started >= UPLOAD_BUDGET_NANOS) break;
            var entry = iterator.next();
            SectionFlight flight = entry.getValue();
            if (!flight.result.isDone()) continue;
            iterator.remove();
            SectionKey section = entry.getKey();
            Map<PageClass, PackedVertices> built;
            try {
                built = flight.result.join();
            } catch (RuntimeException exception) {
                if (sectionsFailed++ == 0) Src2mc.LOGGER.warn("src2mc: a prop batch build failed on a worker; retrying on the render thread", exception);
                SectionInput in = flight.input;
                MapPlacement placement = section.placement();
                BundleMap map = Src2mc.bundles().active().findMap(placement.campaignId(), placement.mapId()).orElse(null);
                built = buildSection(new SectionInput(section, in.contributors, level,
                    MapSurfaceRenderer.occlusionFor(placement, map, (x, y, z) -> MapSurfaceRenderer.surfacePresentLive(placement, x, y, z)), in.smooth));
            }
            boolean current = flight.version == SECTION_VERSIONS.getOrDefault(section, 0);
            BlockPos origin = section.placement().translation().offset(section.x() << 4, section.y() << 4, section.z() << 4);
            for (AggregateKey key : List.copyOf(SECTION_KEYS.getOrDefault(section, Set.of()))) {
                PackedVertices vertices = built.get(new PageClass(key.page(), key.renderClass()));
                Mesh mesh = vertices == null || vertices.isEmpty() ? null : upload(flight.input, origin, vertices);
                Mesh old = AGGREGATES.value(key);
                OCCLUSION.invalidate(key);
                if (old != null) old.close();
                if (current) {
                    AGGREGATES.rebuildComplete(key, mesh);
                    if (!AGGREGATES.keys().contains(key)) removeSectionKey(key);
                } else {
                    AGGREGATES.publish(key, mesh);
                }
                uploaded++;
                PERF.add(PropRenderPerf.M_REBUILT_AGGREGATES, 1);
            }
            if (!current) sectionsOvertaken++;
        }
        PERF.add(PropRenderPerf.M_DIRTY_REMAINING, AGGREGATES.dirtyCount());
        PERF.add(PropRenderPerf.M_REBUILD_NANOS, System.nanoTime() - started);
    }

    private static List<Section> coveredSections(PropTessellator.Triangle triangle) {
        double minX = Math.min(triangle.a().x(), Math.min(triangle.b().x(), triangle.c().x()));
        double minY = Math.min(triangle.a().y(), Math.min(triangle.b().y(), triangle.c().y()));
        double minZ = Math.min(triangle.a().z(), Math.min(triangle.b().z(), triangle.c().z()));
        double maxX = Math.max(triangle.a().x(), Math.max(triangle.b().x(), triangle.c().x()));
        double maxY = Math.max(triangle.a().y(), Math.max(triangle.b().y(), triangle.c().y()));
        double maxZ = Math.max(triangle.a().z(), Math.max(triangle.b().z(), triangle.c().z()));
        int fromX = floorSection(minX), fromY = floorSection(minY), fromZ = floorSection(minZ);
        // Clamped so a triangle lying exactly on a section plane still lands in one section.
        int toX = Math.max(fromX, floorSection(maxX - 1.0e-8)), toY = Math.max(fromY, floorSection(maxY - 1.0e-8)), toZ = Math.max(fromZ, floorSection(maxZ - 1.0e-8));
        List<Section> result = new ArrayList<>();
        for (int x = fromX; x <= toX; x++) for (int y = fromY; y <= toY; y++) for (int z = fromZ; z <= toZ; z++) result.add(new Section(x, y, z));
        return result;
    }

    private static int floorSection(double coordinate) { return (int) Math.floor(coordinate / 16.0); }

    private static Mesh upload(SectionInput input, BlockPos origin, PackedVertices vertices) {
        var uploaded = vertices.upload(MapSurfaceRenderer.neutralEntityId(), MapSurfaceRenderer.indexedMeshes());
        double[] b = vertices.bounds();
        AABB bounds = new AABB(b[0], b[1], b[2], b[3], b[4], b[5]).move(origin.getX(), origin.getY(), origin.getZ()).inflate(0.01);
        Contributor first = input.contributors.isEmpty() ? null : input.contributors.get(0);
        return new Mesh(first == null ? null : first.source.bundle(), first == null ? null : first.source.map().atlas(), uploaded.buffer(),
            origin, bounds, uploaded.bytes(), vertices.triangles());
    }

    /** One sample per vertex, along the vertex's own normal. One value for the whole triangle is
     * vanilla's flat lighting, and on a prop-sized mesh it showed every block boundary the prop
     * crossed. */
    private static int sampleVertexLight(SectionInput in, MapPlacement placement, PropTessellator.Vertex vertex, Map<Long, Integer> cache) {
        float nx = (float) vertex.nx(), ny = (float) vertex.ny(), nz = (float) vertex.nz();
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length > 1.0e-6f) { nx /= length; ny /= length; nz /= length; } else { nx = 0; ny = 1; nz = 0; }
        double worldX = placement.translation().getX() + vertex.x();
        double worldY = placement.translation().getY() + vertex.y();
        double worldZ = placement.translation().getZ() + vertex.z();
        // A prop stands among the map's own walls and floors, and at its exact position its lower
        // vertices sit inside the block under a floor that lies part-way up that block. The
        // surfaces' visibility test and open-cell fallback keep it from reading daylight through
        // that block the way the brightest-neighbour fallback did.
        return in.smooth
            ? LightSampler.smooth(in.world, worldX, worldY, worldZ, nx, ny, nz, cache, in.occlusion)
            : LightSampler.sample(in.world, worldX, worldY, worldZ, nx, ny, nz, cache);
    }

    /** Rebuilds every prop aggregate, for a change in how light is sampled rather than in the
     * light itself. */
    static void invalidateAllLight() {
        for (AggregateKey key : AGGREGATES.keys()) { AGGREGATES.markDirty(key); touch(key); }
    }

    /** Marks aggregates for the section overlapping {@code worldSection} and all 26 neighbours
     * dirty; {@link #rebuildDirtyAggregates} drains them under its own budget. A smooth sample
     * reads the eight cells around a point up to half a block outside the mesh, so a change
     * across a section corner does reach these vertices. */
    static void invalidateLight(MapPlacement placement, SectionPos worldSection) {
        BlockPos local = placement.toLocal(new BlockPos(SectionPos.sectionToBlockCoord(worldSection.x()),
            SectionPos.sectionToBlockCoord(worldSection.y()), SectionPos.sectionToBlockCoord(worldSection.z())));
        int sx = Math.floorDiv(local.getX(), 16), sy = Math.floorDiv(local.getY(), 16), sz = Math.floorDiv(local.getZ(), 16);
        for (AggregateKey key : AGGREGATES.keys()) {
            if (!key.placement().equals(placement)) continue;
            int dx = Math.abs(key.sectionX() - sx), dy = Math.abs(key.sectionY() - sy), dz = Math.abs(key.sectionZ() - sz);
            if (Math.max(dx, Math.max(dy, dz)) <= 1) { AGGREGATES.markDirty(key); touch(key); }
        }
    }

    private static void draw(RenderLevelStageEvent event, BundleGeneration generation, boolean translucent, boolean shadowPass) {
        long started = System.nanoTime();
        var camera = event.getCamera().getPosition();
        List<Map.Entry<AggregateKey, Mesh>> visible = new ArrayList<>();
        List<OcclusionCuller.Candidate<AggregateKey>> queryCandidates = new ArrayList<>();
        OcclusionCuller.CameraView view = new OcclusionCuller.CameraView(camera, event.getCamera().getXRot(), event.getCamera().getYRot());
        var casterFrustum = shadowPass ? MapSurfaceRenderer.shadowFrustum() : null;
        for (AggregateKey key : AGGREGATES.keys()) {
            Mesh mesh = AGGREGATES.value(key);
            if (mesh == null || (key.renderClass() == BundleMaterial.RenderClass.TRANSLUCENT) != translucent) continue;
            // PVS answers "can the player's BSP leaf see this", which is the wrong question for a
            // shadow caster: geometry the player cannot see still casts shadows the player can.
            if (!shadowPass && MapSurfaceRenderer.pvsCulling() && CameraVisibility.row() != null && key.placement().equals(CameraVisibility.placement())) {
                short[] clusters = CameraVisibility.table().sectionClusters(key.sectionX(), key.sectionY(), key.sectionZ());
                if (!CameraVisibility.table().visible(CameraVisibility.row(), clusters)) { PERF.add(PropRenderPerf.M_PVS_REJECTED, 1); continue; }
            }
            PERF.add(PropRenderPerf.M_FRUSTUM_TESTS, 1);
            if (shadowPass) {
                shadowConsidered++;
                if (!MapSurfaceRenderer.withinShadowDistance(mesh.bounds, camera)) { shadowRejectedDistance++; continue; }
                if (casterFrustum != null && !casterFrustum.isVisible(mesh.bounds)) { shadowRejectedFrustum++; continue; }
            } else if (MapSurfaceRenderer.frustumCulling() && !event.getFrustum().isVisible(mesh.bounds)) continue;
            // Translucent props draw at AFTER_PARTICLES, when the depth buffer already holds glass,
            // water, entities and particles; a query there would cull props behind any of them.
            if (!shadowPass && !translucent) {
                queryCandidates.add(new OcclusionCuller.Candidate<>(key, mesh.bounds, mesh.triangles));
                if (OCCLUSION.shouldCull(key, mesh.bounds, view, mesh.triangles)) continue;
            }
            visible.add(Map.entry(key, mesh));
        }
        if (shadowPass) shadowDrawn += visible.size();
        if (!shadowPass && !translucent) OCCLUSION.issue(queryCandidates, view, event.getModelViewMatrix(), event.getProjectionMatrix());
        if (translucent) visible.sort(Comparator.<Map.Entry<AggregateKey, Mesh>>comparingDouble(item -> -distanceSquared(item.getValue().bounds, camera)));
        // Nearest first within a render-state group, so the depth test rejects hidden pixels early.
        else if (MapSurfaceRenderer.sortedDraws()) visible.sort(Comparator.<Map.Entry<AggregateKey, Mesh>>comparingInt(item -> item.getKey().renderClass().ordinal())
            .thenComparingInt(item -> item.getKey().page())
            .thenComparingDouble(item -> MapSurfaceRenderer.nearestDistanceSquared(item.getValue().bounds, camera)));
        int drawCalls = 0;
        long triangles = 0;
        RenderType activeType = null;
        // entityTranslucent writes depth, which would reject a translucent prop behind another translucent layer.
        boolean suppressDepthWrite = translucent && !MapSurfaceRenderer.translucentDepthWrite();
        BundleMaterial.RenderClass activeClass = null;
        int activePage = -1;
        String activeFingerprint = null;
        AtlasIndex activeAtlas = null;
        for (var item : visible) {
            AggregateKey key = item.getKey();
            Mesh mesh = item.getValue();
            // Consecutive aggregates sharing bundle, atlas, page, and class reuse one render state.
            if (activeType == null || key.renderClass() != activeClass || key.page() != activePage
                || !activeFingerprint.equals(mesh.bundle.fingerprint()) || activeAtlas != mesh.atlas) {
                if (activeType != null) {
                    activeType.clearRenderState();
                    if (suppressDepthWrite) RenderSystem.depthMask(true);
                }
                var texture = MapSurfaceRenderer.atlasPages().request(generation.sequence(), mesh.bundle, mesh.atlas, key.page(), frame).orElseGet(MapSurfaceRenderer.atlasPages()::placeholderTexture);
                activeType = translucent ? RenderType.entityTranslucent(texture) : key.renderClass() == BundleMaterial.RenderClass.SOLID ? RenderType.entitySolid(texture) : RenderType.entityCutout(texture);
                activeType.setupRenderState();
                if (suppressDepthWrite) RenderSystem.depthMask(false);
                activeClass = key.renderClass();
                activePage = key.page();
                activeFingerprint = mesh.bundle.fingerprint();
                activeAtlas = mesh.atlas;
                PERF.add(PropRenderPerf.M_STATE_SWITCHES, 1);
            }
            Matrix4f modelView = new Matrix4f(event.getModelViewMatrix()).translate((float) (mesh.origin.getX() - camera.x), (float) (mesh.origin.getY() - camera.y), (float) (mesh.origin.getZ() - camera.z));
            mesh.buffer.bind(); mesh.buffer.drawWithShader(modelView, event.getProjectionMatrix(), translucent ? GameRenderer.getRendertypeEntityTranslucentShader() : key.renderClass() == BundleMaterial.RenderClass.SOLID ? GameRenderer.getRendertypeEntitySolidShader() : GameRenderer.getRendertypeEntityCutoutShader());
            drawCalls++;
            triangles += mesh.triangles;
        }
        if (activeType != null) {
            activeType.clearRenderState();
            if (suppressDepthWrite) RenderSystem.depthMask(true);
        }
        VertexBuffer.unbind();
        PERF.add(PropRenderPerf.M_VISIBLE_AGGREGATES, drawCalls);
        PERF.add(PropRenderPerf.M_DRAW_CALLS, drawCalls);
        if (translucent) PERF.add(PropRenderPerf.M_DRAW_CALLS_TRANSLUCENT, drawCalls);
        PERF.add(PropRenderPerf.M_TRIANGLES, triangles);
        PERF.add(PropRenderPerf.M_RENDER_NANOS, System.nanoTime() - started);
    }

    private static double distanceSquared(AABB bounds, net.minecraft.world.phys.Vec3 camera) { double x = bounds.getCenter().x - camera.x, y = bounds.getCenter().y - camera.y, z = bounds.getCenter().z - camera.z; return x * x + y * y + z * z; }
    private static long estimatedVboBytes() { return AGGREGATES.values().stream().mapToLong(mesh -> mesh.estimatedVboBytes).sum(); }
    private static String formatBytes(long bytes) { return String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0)); }
    private static String gpuMs(GpuTimer.Phase phase) { double ms = GpuTimer.averageMs(phase); return ms < 0 ? "-" : formatMs(ms); }
    private static String formatMs(double millis) { return String.format(java.util.Locale.ROOT, "%.2f ms", millis); }
    private static String formatMillions(long value) { return String.format(java.util.Locale.ROOT, "%.2fM", value / 1_000_000.0); }
    private static MutableComponent statusLine(String label, String value, ChatFormatting valueColor) {
        return Component.literal(label + ": ").withStyle(ChatFormatting.DARK_GRAY)
            .append(Component.literal(value).withStyle(valueColor));
    }
    private static Component detail(String text) { return Component.literal(text).withStyle(ChatFormatting.GRAY); }
    /** @return the number of aggregate batches newly dirtied by removing this prop. */
    private static int removeProp(PropKey key) {
        int dirtied = 0;
        Set<AggregateKey> contributions = PROP_CONTRIBUTIONS.remove(key);
        if (contributions != null) for (AggregateKey aggregate : contributions) {
            OCCLUSION.invalidate(aggregate);
            if (AGGREGATES.unregister(aggregate, key)) dirtied++;
            touch(aggregate);
        }
        REGISTERING.remove(key);
        ROOT_DATA.remove(key);
        BUILT_PROPS.remove(key); LAST_VISIBLE.remove(key);
        return dirtied;
    }
    private static void discardExpiredMeshes() {
        List<PropKey> expired = LAST_VISIBLE.entrySet().stream().filter(item -> frame - item.getValue() > MESH_GRACE_FRAMES).map(Map.Entry::getKey).toList();
        if (expired.isEmpty()) return;
        int dirtied = 0;
        for (PropKey key : expired) dirtied += removeProp(key);
        PERF.add(PropRenderPerf.M_EVICTED_PROPS, expired.size());
        PERF.add(PropRenderPerf.M_EVICTED_AGGREGATES, dirtied);
    }
    /**
     * Drops every built batch and every known root so they are rebuilt from the same generation
     * against the light that exists now. Paired with the surface renderer's own drop; see
     * {@code /src2mc_rebuild_meshes}.
     *
     * @return how many batches were dropped.
     */
    static int rebuildMeshes() {
        int batches = AGGREGATES.size();
        AGGREGATES.values().forEach(Mesh::close); AGGREGATES.clear();
        PROP_CONTRIBUTIONS.clear(); ROOT_DATA.clear();
        ROOTS.clear(); ROOT_STATUS.clear(); BUILT_PROPS.clear(); LAST_VISIBLE.clear();
        clearBuilds();
        OCCLUSION.close();
        return batches;
    }

    private static void clear() {
        AGGREGATES.values().forEach(Mesh::close); AGGREGATES.clear();
        PROP_CONTRIBUTIONS.clear(); ROOT_DATA.clear();
        ROOTS.clear(); ROOT_STATUS.clear(); BUILT_PROPS.clear(); LAST_VISIBLE.clear(); PERF.reset(); OCCLUSION.close();
        clearBuilds();
        CameraVisibility.reset();
        level = null; generationSequence = -1; placementSnapshot = List.of(); frame = 0; nearbyProps = 0; nearbyUnbuilt = 0; buildsLastFrame = 0;
        shadowPassCallsSinceMainPass = 0; shadowPassCallsLastFrame = 0;
        shadowConsidered = 0; shadowRejectedDistance = 0; shadowRejectedFrustum = 0; shadowDrawn = 0;
        shadowConsideredLast = 0; shadowRejectedDistanceLast = 0; shadowRejectedFrustumLast = 0; shadowDrawnLast = 0;
    }

    /** Forgets every registration and section rebuild in flight; their workers finish unobserved. */
    private static void clearBuilds() {
        REGISTERING.clear(); SECTION_KEYS.clear(); SECTION_VERSIONS.clear(); SECTION_BUILDS.clear(); DIRTY_SINCE.clear();
        lastScanSectionX = Integer.MIN_VALUE; lastScanSectionZ = Integer.MIN_VALUE;
    }

    private static SectionKey sectionOf(AggregateKey key) {
        return new SectionKey(key.placement(), key.sectionX(), key.sectionY(), key.sectionZ());
    }

    /** Records that a batch in the key's section changed, for any rebuild of it already running. */
    private static void touch(AggregateKey key) {
        SECTION_VERSIONS.merge(sectionOf(key), 1, Integer::sum);
    }

    private static void removeSectionKey(AggregateKey key) {
        SectionKey section = sectionOf(key);
        Set<AggregateKey> keys = SECTION_KEYS.get(section);
        if (keys == null) return;
        keys.remove(key);
        if (keys.isEmpty()) { SECTION_KEYS.remove(section); SECTION_VERSIONS.remove(section); }
    }

    private record PropKey(MapPlacement placement, String stableId) {}
    private record SectionKey(MapPlacement placement, int x, int y, int z) {}
    private record PageClass(int page, BundleMaterial.RenderClass renderClass) {}
    private record Registration(PropSource source, java.util.concurrent.CompletableFuture<Set<BatchKey>> batches) {}
    private record Contributor(PropSource source, RuntimeMesh mesh) {}
    /** One section rebuild's inputs, fixed when it starts; see {@link MapSurfaceRenderer}'s BuildInput. */
    private record SectionInput(SectionKey section, List<Contributor> contributors, net.minecraft.world.level.BlockAndTintGetter world,
                                SurfaceOcclusion occlusion, boolean smooth) {}
    private record SectionFlight(SectionInput input, int version, java.util.concurrent.CompletableFuture<Map<PageClass, PackedVertices>> result) {}
    private record PropSource(BundleManifest bundle, BundleMap map, MapPlacement placement, BundleProp prop) {}
    private record Section(int x, int y, int z) {}
    private record BatchKey(Section section, int page, BundleMaterial.RenderClass renderClass) {}
    private record AggregateKey(MapPlacement placement, int sectionX, int sectionY, int sectionZ, int page, BundleMaterial.RenderClass renderClass) {}
    private record BuildCandidate(PropSource source, double distanceSquared) {}
    private enum RootStatus { ACTIVE, UNLOADED, MISSING, SCHEMA, CAMPAIGN, MAP, IDENTITY }
    private static final class Mesh implements AutoCloseable {
        final BundleManifest bundle; final AtlasIndex atlas; final VertexBuffer buffer; final BlockPos origin; final AABB bounds; final long estimatedVboBytes; final long triangles;
        Mesh(BundleManifest bundle, AtlasIndex atlas, VertexBuffer buffer, BlockPos origin, AABB bounds, long estimatedVboBytes, long triangles) { this.bundle = bundle; this.atlas = atlas; this.buffer = buffer; this.origin = origin; this.bounds = bounds; this.estimatedVboBytes = estimatedVboBytes; this.triangles = triangles; }
        @Override public void close() { buffer.close(); }
    }
}
