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
import org.joml.Quaternionf;

/**
 * Draws the maps' movers (D21) where Sable has their sub-levels: each mover's surfaces and the
 * props it carries, baked once into mover-local buffers per atlas page, and drawn with the
 * sub-level's interpolated pose. The blocks in the plot are invisible; this is all that is seen.
 *
 * <p>A mover is lit as one: by the world's light at its middle, where it is now. The buffers are
 * baked again when that light changes, which a sliding door does a few times on its way.
 */
@EventBusSubscriber(modid = Src2mc.MOD_ID, value = Dist.CLIENT)
public final class MoverRenderer {
    private MoverRenderer() {}

    private record PageClass(int page, BundleMaterial.RenderClass renderClass) {}

    private static final class Built implements AutoCloseable {
        final MoverTable.Mover mover;
        final BundleManifest bundle;
        final AtlasIndex atlas;
        final Map<PageClass, VertexBuffer> buffers = new HashMap<>();
        final int light;
        final boolean shaders;
        final boolean complete;
        final double[] bounds;
        Built(MoverTable.Mover mover, BundleManifest bundle, AtlasIndex atlas, int light, boolean shaders, boolean complete, double[] bounds) {
            this.mover = mover; this.bundle = bundle; this.atlas = atlas; this.light = light; this.shaders = shaders;
            this.complete = complete; this.bounds = bounds;
        }
        @Override public void close() { buffers.values().forEach(VertexBuffer::close); }
    }

    private static final Map<UUID, Built> BUILT = new HashMap<>();
    private static long frame, generationSequence = -1;
    private static boolean enabled = true;
    private static long drawnLast, triangles;

    public static void setEnabled(boolean value) { enabled = value; }

    public static String status() {
        return "src2mc movers: " + MoverRegistry.instances(true).size() + " known, " + BUILT.size() + " built, "
            + drawnLast + " drawn last frame" + (enabled ? "" : " (drawing off)");
    }

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
            int light = lightAt(level, subLevel, resolved);
            Built old = BUILT.get(instance.subLevel());
            if (old != null && old.mover == resolved.mover() && old.light == light && old.shaders == shaders && old.complete) continue;
            var located = generation.findLocatedMap(instance.campaignId(), instance.mapId()).orElse(null);
            if (located == null) continue;
            Built built = build(generation, located.bundle(), resolved, light, shaders);
            if (old != null) old.close();
            BUILT.put(instance.subLevel(), built);
        }
    }

    /** The world's light at the middle of the mover, where its sub-level has it now. */
    private static int lightAt(ClientLevel level, ClientSubLevel subLevel, MoverRegistry.Resolved resolved) {
        BlockPos plotOrigin = MoverRegistry.plotOrigin(subLevel.getPlot());
        MoverTable.Mover mover = resolved.mover();
        Vec3 middle = subLevel.logicalPose().transformPosition(new Vec3(plotOrigin.getX() + mover.sizeX() / 2.0,
            plotOrigin.getY() + mover.sizeY() / 2.0, plotOrigin.getZ() + mover.sizeZ() / 2.0));
        return LevelRenderer.getLightColor(level, BlockPos.containing(middle));
    }

    private static Built build(BundleGeneration generation, BundleManifest bundle, MoverRegistry.Resolved resolved, int light, boolean shaders) {
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
        for (MoverTable.Prop moverProp : mover.props()) {
            BundleModel model = map.models().get(moverProp.model());
            RuntimeMesh mesh = PropRenderer.runtimeMeshes().request(generation.sequence(), bundle, model.contentId()).orElse(null);
            // Still loading: built without it now, and again once it is there.
            if (mesh == null) { complete = false; continue; }
            BundleProp prop = new BundleProp("", moverProp.model(), new int[3], moverProp.translation(), moverProp.rotation(), moverProp.scale());
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
                            (float) vertex.nx(), (float) vertex.ny(), (float) vertex.nz(), light);
                    }
                }
            }
        }
        double[] bounds = null;
        Built built = new Built(mover, bundle, map.atlas(), light, shaders, complete, null);
        for (var entry : meshes.entrySet()) {
            PackedVertices vertices = entry.getValue();
            if (vertices.isEmpty()) continue;
            vertices.index();
            built.buffers.put(entry.getKey(), vertices.upload(MapSurfaceRenderer.neutralEntityId(), MapSurfaceRenderer.indexedMeshes()).buffer());
            double[] b = vertices.bounds();
            if (bounds == null) bounds = b.clone();
            else for (int axis = 0; axis < 3; axis++) { bounds[axis] = Math.min(bounds[axis], b[axis]); bounds[axis + 3] = Math.max(bounds[axis + 3], b[axis + 3]); }
        }
        return bounds == null ? built : withBounds(built, bounds);
    }

    private static Built withBounds(Built built, double[] bounds) {
        Built copy = new Built(built.mover, built.bundle, built.atlas, built.light, built.shaders, built.complete, bounds);
        copy.buffers.putAll(built.buffers);
        return copy;
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
        long drawn = 0;
        for (Map.Entry<UUID, Built> entry : BUILT.entrySet()) {
            SubLevel subLevel = container.getSubLevel(entry.getKey());
            if (!(subLevel instanceof ClientSubLevel client)) continue;
            Built built = entry.getValue();
            Pose3dc pose = client.renderPose(partialTick);
            BlockPos plotOrigin = MoverRegistry.plotOrigin(client.getPlot());
            if (built.bounds != null && !shadowPass && MapSurfaceRenderer.frustumCulling()) {
                AABB box = worldBounds(pose, plotOrigin, built.bounds);
                if (!event.getFrustum().isVisible(box)) continue;
            }
            // Mover-local vertex v is plot point plotOrigin + v, drawn at R (plot - C) + position.
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
