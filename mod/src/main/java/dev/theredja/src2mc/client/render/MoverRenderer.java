package dev.theredja.src2mc.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.bundle.AtlasIndex;
import dev.theredja.src2mc.bundle.BundleGeneration;
import dev.theredja.src2mc.bundle.BundleManifest;
import dev.theredja.src2mc.bundle.BundleMap;
import dev.theredja.src2mc.bundle.BundleMaterial;
import dev.theredja.src2mc.bundle.BundleModel;
import dev.theredja.src2mc.bundle.BundleProp;
import dev.theredja.src2mc.bundle.MoverTable;
import dev.theredja.src2mc.bundle.RuntimeMesh;
import dev.theredja.src2mc.bundle.SurfaceTable;
import dev.theredja.src2mc.world.MoverRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Quaternionf;

/**
 * Draws the maps' movers (D21) where Sable has their sub-levels: each mover's surfaces and the
 * props it carries, baked once into mover-local buffers per atlas page, and drawn with the
 * sub-level's interpolated pose. The blocks in the plot are invisible; this is all that is seen.
 *
 * <p>Each vertex takes the world's light where the mover has it now, sampled as the static
 * surfaces sample theirs; a moving mover is lit again as it gets somewhere new, a few times a
 * second at most. The vertices keep their mover-local normals, so the draw turns vanilla's two
 * shading lights back by the mover's rotation instead: the same shading as turning every normal.
 * {@code /src2mc_movers} switches both, for comparison, to the old one light per mover and
 * unturned shading.
 */
@EventBusSubscriber(modid = Src2mc.MOD_ID, value = Dist.CLIENT)
public final class MoverRenderer {
    private MoverRenderer() {}

    private record PageClass(int page, BundleMaterial.RenderClass renderClass) {}

    private static final class Built implements AutoCloseable {
        final MoverTable.Mover mover;
        final BundleManifest bundle;
        final AtlasIndex atlas;
        final Map<PageClass, PackedVertices> meshes;
        final Map<PageClass, VertexBuffer> buffers = new HashMap<>();
        final boolean shaders;
        final boolean complete;
        final double[] bounds;
        /** The logic prop states it was built with; see {@link #propStates}. */
        final List<dev.theredja.src2mc.world.PropStates.State> propStates;
        /** The light at the mover's middle, and where the mover was, when it was last lit. */
        int light = Integer.MIN_VALUE;
        Vector3d litPosition;
        Quaterniond litOrientation;
        long litFrame = Long.MIN_VALUE;
        boolean litPerVertex;
        /** A relight sampling on a worker; its buffers go up on the render thread once it is done. */
        java.util.concurrent.CompletableFuture<Void> relighting;
        Built(MoverTable.Mover mover, BundleManifest bundle, AtlasIndex atlas, Map<PageClass, PackedVertices> meshes, boolean shaders,
              boolean complete, double[] bounds, List<dev.theredja.src2mc.world.PropStates.State> propStates) {
            this.mover = mover; this.bundle = bundle; this.atlas = atlas; this.meshes = meshes; this.shaders = shaders;
            this.complete = complete; this.bounds = bounds; this.propStates = propStates;
        }
        @Override public void close() { buffers.values().forEach(VertexBuffer::close); buffers.clear(); }
    }

    private static final Map<UUID, Built> BUILT = new HashMap<>();
    private static long frame, generationSequence = -1;
    private static boolean enabled = true, perVertexLight = true, turnShading = true;
    private static long drawnLast, relights, workerRelights, workerNanos, uploadNanosLast, uploadNanosMax;
    /** A moving mover is lit again at most this often, in frames, once it moved or turned this far. */
    private static final int RELIGHT_FRAMES = 6, LIGHT_CHECK_FRAMES = 20;
    private static final double RELIGHT_DISTANCE = 0.25, RELIGHT_ANGLE = Math.toRadians(5);
    private static final org.joml.Vector3f LIGHT_0 = new org.joml.Vector3f(0.2F, 1.0F, -0.7F).normalize();
    private static final org.joml.Vector3f LIGHT_1 = new org.joml.Vector3f(-0.2F, 1.0F, 0.7F).normalize();
    private static final org.joml.Vector3f NETHER_LIGHT_1 = new org.joml.Vector3f(-0.2F, -1.0F, 0.7F).normalize();

    public static void setEnabled(boolean value) { enabled = value; }

    public static String status() {
        return "src2mc movers: " + MoverRegistry.instances(true).size() + " known, " + BUILT.size() + " built, "
            + drawnLast + " drawn last frame, " + relights + " relit (" + workerRelights + " on workers, "
            + String.format(java.util.Locale.ROOT, "%.2f ms each; upload %.2f ms last frame, %.2f ms max", workerRelights == 0 ? 0 : workerNanos / 1e6 / workerRelights,
                uploadNanosLast / 1e6, uploadNanosMax / 1e6) + "); light " + (perVertexLight ? "per vertex" : "one per mover")
            + ", shading " + (turnShading ? "turned" : "unturned") + (enabled ? "" : " (drawing off)");
    }

    @SubscribeEvent
    public static void registerCommand(net.neoforged.neoforge.client.event.RegisterClientCommandsEvent event) {
        event.getDispatcher().register(net.minecraft.commands.Commands.literal("src2mc_movers")
            .then(net.minecraft.commands.Commands.literal("status").executes(context -> reply(context.getSource(), status())))
            .then(net.minecraft.commands.Commands.literal("draw")
                .then(net.minecraft.commands.Commands.literal("on").executes(context -> { enabled = true; return reply(context.getSource(), status()); }))
                .then(net.minecraft.commands.Commands.literal("off").executes(context -> { enabled = false; return reply(context.getSource(), status()); })))
            .then(net.minecraft.commands.Commands.literal("light")
                .then(net.minecraft.commands.Commands.literal("vertex").executes(context -> { perVertexLight = true; relightAll(); return reply(context.getSource(), status()); }))
                .then(net.minecraft.commands.Commands.literal("single").executes(context -> { perVertexLight = false; relightAll(); return reply(context.getSource(), status()); })))
            .then(net.minecraft.commands.Commands.literal("shading")
                .then(net.minecraft.commands.Commands.literal("turned").executes(context -> { turnShading = true; return reply(context.getSource(), status()); }))
                .then(net.minecraft.commands.Commands.literal("unturned").executes(context -> { turnShading = false; return reply(context.getSource(), status()); }))));
    }

    private static int reply(net.minecraft.commands.CommandSourceStack source, String text) {
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal(text), false);
        return 1;
    }

    private static void relightAll() { for (Built built : BUILT.values()) built.litFrame = Long.MIN_VALUE; }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        boolean shadowPass = IrisCompat.renderingShadowPass();
        if (event.getStage() == MapSurfaceRenderer.opaqueStage()) {
            if (!shadowPass) prepare();
            draw(event, false, shadowPass);
        } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_PARTICLES && !shadowPass) {
            draw(event, true, false);
        }
    }

    /** Main pass, once a frame: builds what is missing and drops what is gone. */
    private static void prepare() {
        frame++;
        ClientLevel level = Minecraft.getInstance().level;
        BundleGeneration generation = Src2mc.bundles().active();
        if (level == null || generation.sequence() != generationSequence) {
            clear();
            generationSequence = generation.sequence();
            if (level == null) return;
        }
        ClientSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return;
        BUILT.keySet().removeIf(uuid -> {
            if (MoverRegistry.instance(true, uuid) != null && container.getSubLevel(uuid) != null) return false;
            BUILT.get(uuid).close();
            return true;
        });
        boolean shaders = IrisCompat.shaderPackInUse();
        for (MoverRegistry.Instance instance : MoverRegistry.instances(true)) {
            if (!(container.getSubLevel(instance.subLevel()) instanceof ClientSubLevel subLevel)) continue;
            MoverRegistry.Resolved resolved = MoverRegistry.resolve(level, instance.subLevel());
            if (resolved == null) continue;
            Built built = BUILT.get(instance.subLevel());
            List<dev.theredja.src2mc.world.PropStates.State> propStates = propStates(level, resolved);
            if (built == null || built.mover != resolved.mover() || built.shaders != shaders || !built.complete
                || !built.propStates.equals(propStates)) {
                var located = generation.findLocatedMap(instance.campaignId(), instance.mapId()).orElse(null);
                if (located == null) continue;
                Built fresh = build(generation, located.bundle(), resolved, shaders, level);
                if (built != null) built.close();
                BUILT.put(instance.subLevel(), fresh);
                built = fresh;
            }
            if (built.relighting != null) {
                if (!built.relighting.isDone()) continue;
                finishRelight(built);
            }
            if (needsLight(level, subLevel, resolved, built)) {
                // The first light and the one-light mode are quick and needed now; per-vertex
                // relights of a moving mover sample a copy of the world on a worker.
                if (built.litFrame == Long.MIN_VALUE || !perVertexLight) light(level, subLevel, built);
                else relightOnWorker(level, subLevel, built);
            }
        }
    }

    /**
     * Whether a mover is due to be lit again: never lit, switched between the two ways, its
     * middle's light changed, or -- lit per vertex -- moved or turned enough since.
     */
    private static boolean needsLight(ClientLevel level, ClientSubLevel subLevel, MoverRegistry.Resolved resolved, Built built) {
        if (built.litFrame == Long.MIN_VALUE || built.litPerVertex != perVertexLight) return true;
        if (!perVertexLight) return lightAt(level, subLevel, resolved) != built.light;
        if (frame - built.litFrame < RELIGHT_FRAMES) return false;
        Pose3dc pose = subLevel.logicalPose();
        if (built.litPosition.distance(pose.position().x(), pose.position().y(), pose.position().z()) > RELIGHT_DISTANCE
            || built.litOrientation.difference(new Quaterniond(pose.orientation()), new Quaterniond()).angle() > RELIGHT_ANGLE) return true;
        return frame - built.litFrame >= LIGHT_CHECK_FRAMES && lightAt(level, subLevel, resolved) != built.light;
    }

    /** Lights every vertex where the mover is now, and uploads its buffers again. */
    private static void light(ClientLevel level, ClientSubLevel subLevel, Built built) {
        Pose3dc pose = subLevel.logicalPose();
        BlockPos plotOrigin = MoverRegistry.plotOrigin(subLevel.getPlot());
        MoverRegistry.Resolved resolved = MoverRegistry.resolve(level, subLevel.getUniqueId());
        int middle = resolved == null ? 0 : lightAt(level, subLevel, resolved);
        Quaterniond orientation = new Quaterniond(pose.orientation());
        Map<Long, Integer> cache = new HashMap<>();
        Vector3d normal = new Vector3d();
        built.close();
        for (var entry : built.meshes.entrySet()) {
            PackedVertices vertices = entry.getValue();
            for (int i = 0; i < vertices.vertices(); i++) {
                int light = middle;
                if (perVertexLight) {
                    Vec3 world = pose.transformPosition(new Vec3(plotOrigin.getX() + vertices.x(i), plotOrigin.getY() + vertices.y(i),
                        plotOrigin.getZ() + vertices.z(i)));
                    orientation.transform(normal.set(vertices.nx(i), vertices.ny(i), vertices.nz(i)));
                    light = LightSampler.smooth(level, world.x, world.y, world.z, (float) normal.x, (float) normal.y, (float) normal.z, cache);
                }
                vertices.setLight(i, light);
            }
            vertices.index();
            built.buffers.put(entry.getKey(), vertices.upload(MapSurfaceRenderer.neutralEntityId(), MapSurfaceRenderer.indexedMeshes()).buffer());
        }
        built.light = middle;
        built.litPosition = new Vector3d(pose.position().x(), pose.position().y(), pose.position().z());
        built.litOrientation = orientation;
        built.litFrame = frame;
        built.litPerVertex = perVertexLight;
        relights++;
    }

    /**
     * Starts a per-vertex relight on a mesh worker against a snapshot of the world around the
     * mover; the buffers drawn now stay until {@link #finishRelight} replaces them. Lighting every
     * turning fan on the render thread cost a frame-time spike every few frames (escape_02's 15
     * rotators, 2026-10-04).
     */
    private static void relightOnWorker(ClientLevel level, ClientSubLevel subLevel, Built built) {
        Pose3dc pose = subLevel.logicalPose();
        BlockPos plotOrigin = MoverRegistry.plotOrigin(subLevel.getPlot());
        MoverRegistry.Resolved resolved = MoverRegistry.resolve(level, subLevel.getUniqueId());
        int middle = resolved == null ? 0 : lightAt(level, subLevel, resolved);
        Quaterniond orientation = new Quaterniond(pose.orientation());
        Vector3d position = new Vector3d(pose.position().x(), pose.position().y(), pose.position().z());
        Vector3d rotationPoint = new Vector3d(pose.rotationPoint().x(), pose.rotationPoint().y(), pose.rotationPoint().z());
        if (built.bounds == null) return;
        AABB box = worldBounds(pose, plotOrigin, built.bounds).inflate(2);
        WorldSnapshot world = WorldSnapshot.capture(level, (int) Math.floor(box.minX), (int) Math.floor(box.minY), (int) Math.floor(box.minZ),
            (int) Math.floor(box.maxX), (int) Math.floor(box.maxY), (int) Math.floor(box.maxZ));
        built.light = middle;
        built.litPosition = position;
        built.litOrientation = orientation;
        built.litFrame = frame;
        built.litPerVertex = true;
        built.relighting = MeshBuildPool.submit(() -> {
            long started = System.nanoTime();
            Map<Long, Integer> cache = new HashMap<>();
            Vector3d point = new Vector3d(), normal = new Vector3d();
            for (PackedVertices vertices : built.meshes.values()) {
                for (int i = 0; i < vertices.vertices(); i++) {
                    // Drawn at R (plot - C) + position, as the draw places it.
                    orientation.transform(point.set(plotOrigin.getX() + vertices.x(i) - rotationPoint.x,
                        plotOrigin.getY() + vertices.y(i) - rotationPoint.y, plotOrigin.getZ() + vertices.z(i) - rotationPoint.z)).add(position);
                    orientation.transform(normal.set(vertices.nx(i), vertices.ny(i), vertices.nz(i)));
                    vertices.setLight(i, LightSampler.smooth(world, point.x, point.y, point.z, (float) normal.x, (float) normal.y, (float) normal.z, cache));
                }
                vertices.index();
            }
            synchronized (MoverRenderer.class) { workerNanos += System.nanoTime() - started; workerRelights++; }
            return null;
        });
    }

    /** Uploads a finished worker relight in place of the buffers drawn so far. */
    private static void finishRelight(Built built) {
        long started = System.nanoTime();
        var job = built.relighting;
        built.relighting = null;
        try {
            job.join();
        } catch (RuntimeException exception) {
            built.litFrame = Long.MIN_VALUE;
            return;
        }
        built.close();
        for (var entry : built.meshes.entrySet()) {
            built.buffers.put(entry.getKey(), entry.getValue().upload(MapSurfaceRenderer.neutralEntityId(), MapSurfaceRenderer.indexedMeshes()).buffer());
        }
        relights++;
        long took = System.nanoTime() - started;
        uploadNanosLast = took;
        uploadNanosMax = Math.max(uploadNanosMax, took);
    }

    /** The world's light at the middle of the mover, where its sub-level has it now. */
    private static int lightAt(ClientLevel level, ClientSubLevel subLevel, MoverRegistry.Resolved resolved) {
        BlockPos plotOrigin = MoverRegistry.plotOrigin(subLevel.getPlot());
        MoverTable.Mover mover = resolved.mover();
        Vec3 middle = subLevel.logicalPose().transformPosition(new Vec3(plotOrigin.getX() + mover.sizeX() / 2.0,
            plotOrigin.getY() + mover.sizeY() / 2.0, plotOrigin.getZ() + mover.sizeZ() / 2.0));
        return LevelRenderer.getLightColor(level, BlockPos.containing(middle));
    }

    /**
     * The state of every logic prop riding the mover (format.md section 18), in the order of its
     * props: a {@code Skin}, {@code Color} or {@code Disable} on one builds the mover again.
     */
    private static List<dev.theredja.src2mc.world.PropStates.State> propStates(ClientLevel level, MoverRegistry.Resolved resolved) {
        BundleMap map = resolved.map();
        if (map.logicProps() == null) return List.of();
        var key = dev.theredja.src2mc.world.PropStates.key(level, resolved.placement());
        List<dev.theredja.src2mc.world.PropStates.State> states = new ArrayList<>();
        for (MoverTable.Prop moverProp : resolved.mover().props()) {
            var logicProp = map.logicProps().byEntity(moverProp.entity());
            if (logicProp != null && logicProp.mover() == resolved.mover().entity())
                states.add(dev.theredja.src2mc.world.PropStates.effective(true, key, map, logicProp));
        }
        return states;
    }

    private static Built build(BundleGeneration generation, BundleManifest bundle, MoverRegistry.Resolved resolved, boolean shaders,
                               ClientLevel level) {
        List<dev.theredja.src2mc.world.PropStates.State> propStates = propStates(level, resolved);
        int light = 0;
        BundleMap map = resolved.map();
        MoverTable.Mover mover = resolved.mover();
        Map<PageClass, PackedVertices> meshes = new HashMap<>();
        if (mover.surfaces() != null && map.atlas() != null) {
            for (var section : mover.surfaces().sections().entrySet()) {
                int baseX = section.getKey().x() << 4, baseY = section.getKey().y() << 4, baseZ = section.getKey().z() << 4;
                for (SurfaceTable.Face face : section.getValue()) {
                    if (face.materialId() < 0 || face.materialId() >= map.materials().size()) continue;
                    BundleMaterial material = map.materials().get(face.materialId());
                    if (!material.textured() || material.renderClass() == BundleMaterial.RenderClass.FALLBACK) continue;
                    AtlasIndex.Texture texture = map.atlas().textures().get(material.texture().contentId());
                    if (texture == null) continue;
                    int local = face.localCell();
                    int x = baseX + (local & 15), y = baseY + (local >> 8 & 15), z = baseZ + (local >> 4 & 15);
                    for (var triangle : SurfaceTessellator.tessellate(x, y, z, face,
                        mover.surfaces().uvRegions().get(face.uvRegionId()), material.texture(), texture, map.atlas().pageSize())) {
                        PackedVertices out = meshes.computeIfAbsent(new PageClass(triangle.page(), material.renderClass()), ignored -> new PackedVertices());
                        addSurface(out, triangle, light, false);
                        if (material.doubleSided()) addSurface(out, triangle, light, true);
                    }
                }
            }
        }
        boolean complete = true;
        var stateKey = dev.theredja.src2mc.world.PropStates.key(level, resolved.placement());
        for (MoverTable.Prop moverProp : mover.props()) {
            int modelIndex = moverProp.model();
            BundleModel model = map.models().get(modelIndex);
            int tint = model.color();
            var logicProp = map.logicProps() == null ? null : map.logicProps().byEntity(moverProp.entity());
            if (logicProp != null && logicProp.mover() == mover.entity()) {
                var state = dev.theredja.src2mc.world.PropStates.effective(true, stateKey, map, logicProp);
                if (state.hidden()) continue;
                modelIndex = logicProp.model(state.skin());
                model = map.models().get(modelIndex);
                tint = state.color();
            }
            RuntimeMesh mesh = PropRenderer.runtimeMeshes().request(generation.sequence(), bundle, model.contentId()).orElse(null);
            // Still loading: built without it now, and again once it is there.
            if (mesh == null) { complete = false; continue; }
            BundleProp prop = new BundleProp("", modelIndex, new int[3], moverProp.translation(), moverProp.rotation(), moverProp.scale());
            for (RuntimeMesh.Submesh submesh : mesh.submeshes()) {
                if (submesh.materialSlot() < 0 || submesh.materialSlot() >= model.materialSlotCount()) continue;
                int materialId = model.materialIds()[submesh.materialSlot()];
                if (materialId < 0 || materialId >= map.materials().size()) continue;
                BundleMaterial material = map.materials().get(materialId);
                if (!material.textured() || material.renderClass() == BundleMaterial.RenderClass.FALLBACK || map.atlas() == null) continue;
                AtlasIndex.Texture texture = map.atlas().textures().get(material.texture().contentId());
                if (texture == null) continue;
                List<PropTessellator.Triangle> tessellated = PropTessellator.tessellate(mesh, submesh, prop, material.texture(), texture, map.atlas().pageSize());
                if (material.doubleSided()) tessellated = PropTessellator.withBackFaces(tessellated);
                for (PropTessellator.Triangle triangle : tessellated) {
                    PackedVertices out = meshes.computeIfAbsent(new PageClass(triangle.page(), material.renderClass()), ignored -> new PackedVertices());
                    for (PropTessellator.Vertex vertex : List.of(triangle.a(), triangle.b(), triangle.c())) {
                        out.add((float) vertex.x(), (float) vertex.y(), (float) vertex.z(), (float) vertex.u(), (float) vertex.v(),
                            (float) vertex.nx(), (float) vertex.ny(), (float) vertex.nz(), light, tint);
                    }
                }
            }
        }
        double[] bounds = null;
        Map<PageClass, PackedVertices> kept = new HashMap<>();
        for (var entry : meshes.entrySet()) {
            PackedVertices vertices = entry.getValue();
            if (vertices.isEmpty()) continue;
            kept.put(entry.getKey(), vertices);
            double[] b = vertices.bounds();
            if (bounds == null) bounds = b.clone();
            else for (int axis = 0; axis < 3; axis++) { bounds[axis] = Math.min(bounds[axis], b[axis]); bounds[axis + 3] = Math.max(bounds[axis + 3], b[axis + 3]); }
        }
        // Uploaded once it is lit, in the same frame.
        return new Built(mover, bundle, map.atlas(), kept, shaders, complete, bounds, propStates);
    }

    private static void addSurface(PackedVertices out, SurfaceTessellator.Triangle triangle, int light, boolean back) {
        var a = triangle.a();
        var b = back ? triangle.c() : triangle.b();
        var c = back ? triangle.b() : triangle.c();
        double abx = b.x() - a.x(), aby = b.y() - a.y(), abz = b.z() - a.z();
        double acx = c.x() - a.x(), acy = c.y() - a.y(), acz = c.z() - a.z();
        float nx = (float) (aby * acz - abz * acy), ny = (float) (abz * acx - abx * acz), nz = (float) (abx * acy - aby * acx);
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length > 0) { nx /= length; ny /= length; nz /= length; }
        for (SurfaceTessellator.Vertex vertex : List.of(a, b, c)) {
            out.add((float) vertex.x(), (float) vertex.y(), (float) vertex.z(), (float) vertex.u(), (float) vertex.v(), nx, ny, nz, light);
        }
    }

    private static void draw(RenderLevelStageEvent event, boolean translucent, boolean shadowPass) {
        if (!enabled || BUILT.isEmpty()) return;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        ClientSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return;
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 camera = event.getCamera().getPosition();
        BundleGeneration generation = Src2mc.bundles().active();
        boolean suppressDepthWrite = translucent && !MapSurfaceRenderer.translucentDepthWrite();
        // Vanilla's level shading lights, as LevelRenderer sets them for this dimension.
        boolean netherLighting = level.effects().constantAmbientLight();
        long drawn = 0;
        for (Map.Entry<UUID, Built> entry : BUILT.entrySet()) {
            SubLevel subLevel = container.getSubLevel(entry.getKey());
            if (!(subLevel instanceof ClientSubLevel client)) continue;
            Built built = entry.getValue();
            MoverRegistry.Resolved resolved = MoverRegistry.resolve(level, entry.getKey());
            if (resolved == null || (MoverRegistry.state(true, resolved) & MoverRegistry.HIDDEN) != 0) continue;
            Pose3dc pose = client.renderPose(partialTick);
            BlockPos plotOrigin = MoverRegistry.plotOrigin(client.getPlot());
            if (built.bounds != null && !shadowPass && MapSurfaceRenderer.frustumCulling()) {
                AABB box = worldBounds(pose, plotOrigin, built.bounds);
                if (!event.getFrustum().isVisible(box)) continue;
            }
            // Mover-local vertex v is plot point plotOrigin + v, drawn at R (plot - C) + position.
            if (turnShading) {
                // Normals stay mover-local: the shading lights turn back instead.
                Quaternionf back = new Quaternionf(pose.orientation()).conjugate();
                RenderSystem.setShaderLights(back.transform(LIGHT_0, new org.joml.Vector3f()),
                    back.transform(netherLighting ? NETHER_LIGHT_1 : LIGHT_1, new org.joml.Vector3f()));
            }
            Matrix4f modelView = new Matrix4f(event.getModelViewMatrix())
                .translate((float) (pose.position().x() - camera.x), (float) (pose.position().y() - camera.y), (float) (pose.position().z() - camera.z))
                .rotate(new Quaternionf(pose.orientation()))
                .translate((float) (plotOrigin.getX() - pose.rotationPoint().x()), (float) (plotOrigin.getY() - pose.rotationPoint().y()),
                    (float) (plotOrigin.getZ() - pose.rotationPoint().z()));
            for (Map.Entry<PageClass, VertexBuffer> buffer : built.buffers.entrySet()) {
                PageClass key = buffer.getKey();
                if ((key.renderClass() == BundleMaterial.RenderClass.TRANSLUCENT) != translucent) continue;
                ResourceLocation texture = MapSurfaceRenderer.atlasPages().request(generation.sequence(), built.bundle, built.atlas, key.page(), frame)
                    .orElseGet(MapSurfaceRenderer.atlasPages()::placeholderTexture);
                RenderType type = translucent ? RenderType.entityTranslucent(texture)
                    : key.renderClass() == BundleMaterial.RenderClass.SOLID ? RenderType.entitySolid(texture) : RenderType.entityCutout(texture);
                type.setupRenderState();
                MapSurfaceRenderer.applyAtlasFilter(texture);
                if (suppressDepthWrite) RenderSystem.depthMask(false);
                buffer.getValue().bind();
                buffer.getValue().drawWithShader(modelView, event.getProjectionMatrix(),
                    translucent ? GameRenderer.getRendertypeEntityTranslucentShader()
                        : key.renderClass() == BundleMaterial.RenderClass.SOLID
                            ? GameRenderer.getRendertypeEntitySolidShader() : GameRenderer.getRendertypeEntityCutoutShader());
                type.clearRenderState();
                if (suppressDepthWrite) RenderSystem.depthMask(true);
                drawn++;
            }
        }
        VertexBuffer.unbind();
        if (turnShading) RenderSystem.setShaderLights(LIGHT_0, netherLighting ? NETHER_LIGHT_1 : LIGHT_1);
        if (!shadowPass && !translucent) drawnLast = drawn;
    }

    /** The world box around a mover's buffers at a pose. */
    private static AABB worldBounds(Pose3dc pose, BlockPos plotOrigin, double[] b) {
        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX, maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (int corner = 0; corner < 8; corner++) {
            Vec3 world = pose.transformPosition(new Vec3(plotOrigin.getX() + ((corner & 1) == 0 ? b[0] : b[3]),
                plotOrigin.getY() + ((corner & 2) == 0 ? b[1] : b[4]), plotOrigin.getZ() + ((corner & 4) == 0 ? b[2] : b[5])));
            minX = Math.min(minX, world.x); minY = Math.min(minY, world.y); minZ = Math.min(minZ, world.z);
            maxX = Math.max(maxX, world.x); maxY = Math.max(maxY, world.y); maxZ = Math.max(maxZ, world.z);
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ).inflate(0.01);
    }

    public static void clear() {
        BUILT.values().forEach(Built::close);
        BUILT.clear();
    }
}
