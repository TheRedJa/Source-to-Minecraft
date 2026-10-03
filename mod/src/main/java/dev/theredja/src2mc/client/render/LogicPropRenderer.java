package dev.theredja.src2mc.client.render;

import com.mojang.blaze3d.vertex.VertexBuffer;
import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.bundle.AtlasIndex;
import dev.theredja.src2mc.bundle.BundleGeneration;
import dev.theredja.src2mc.bundle.BundleManifest;
import dev.theredja.src2mc.bundle.BundleMap;
import dev.theredja.src2mc.bundle.BundleMaterial;
import dev.theredja.src2mc.bundle.BundleProp;
import dev.theredja.src2mc.bundle.LogicPropTable;
import dev.theredja.src2mc.bundle.RuntimeMesh;
import dev.theredja.src2mc.world.MapPlacement;
import dev.theredja.src2mc.world.PropStates;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

/**
 * Draws the placed props the map's logic changes (format.md section 18), each from buffers of its
 * own, so a {@code Skin}, {@code Color}, {@code Enable} or {@code Kill} changes one prop and never
 * rebuilds a section's merged aggregate. {@link PropRenderer} leaves these props out of the
 * aggregates and draws everything else.
 *
 * <p>A prop is built in the skin and tint its {@link PropStates} state names and built again when
 * that changes; a hidden one is not drawn. Each vertex is lit where it stands, sampled as the
 * aggregates sample theirs, and lit again when the light at the prop's middle changes.
 * {@code /src2mc_logic_props} shows counts and switches drawing off for comparison.
 */
@EventBusSubscriber(modid = Src2mc.MOD_ID, value = Dist.CLIENT)
public final class LogicPropRenderer {
    private LogicPropRenderer() {}

    private record Key(MapPlacement placement, int entity) {}
    private record PageClass(int page, BundleMaterial.RenderClass renderClass) {}

    private static final class Built implements AutoCloseable {
        final LogicPropTable.Prop prop;
        final BundleManifest bundle;
        final AtlasIndex atlas;
        final int skin, color;
        final BlockPos origin;
        final Map<PageClass, PackedVertices> meshes;
        final Map<PageClass, VertexBuffer> buffers = new HashMap<>();
        final AABB bounds;
        int light = Integer.MIN_VALUE;
        long litFrame = Long.MIN_VALUE;
        Built(LogicPropTable.Prop prop, BundleManifest bundle, AtlasIndex atlas, int skin, int color, BlockPos origin,
              Map<PageClass, PackedVertices> meshes, AABB bounds) {
            this.prop = prop; this.bundle = bundle; this.atlas = atlas; this.skin = skin; this.color = color; this.origin = origin;
            this.meshes = meshes; this.bounds = bounds;
        }
        @Override public void close() { buffers.values().forEach(VertexBuffer::close); buffers.clear(); }
    }

    private static final Map<Key, Built> BUILT = new HashMap<>();
    /** Builds a frame may start: each is one prop, tessellated on the render thread. */
    private static final int BUILDS_PER_FRAME = 8;
    private static final int LIGHT_CHECK_FRAMES = 20;
    private static long frame, generationSequence = -1;
    private static boolean enabled = true;
    private static long drawnLast, builds, relights, hiddenLast;

    public static String status() {
        return "src2mc logic props: " + BUILT.size() + " built, " + drawnLast + " buffers drawn last frame, " + hiddenLast
            + " hidden; " + builds + " builds, " + relights + " relights" + (enabled ? "" : " (drawing off)");
    }

    @SubscribeEvent
    public static void registerCommand(net.neoforged.neoforge.client.event.RegisterClientCommandsEvent event) {
        event.getDispatcher().register(net.minecraft.commands.Commands.literal("src2mc_logic_props")
            .then(net.minecraft.commands.Commands.literal("status").executes(context -> reply(context.getSource(), status())))
            .then(net.minecraft.commands.Commands.literal("draw")
                .then(net.minecraft.commands.Commands.literal("on").executes(context -> { enabled = true; return reply(context.getSource(), status()); }))
                .then(net.minecraft.commands.Commands.literal("off").executes(context -> { enabled = false; return reply(context.getSource(), status()); }))));
    }

    private static int reply(net.minecraft.commands.CommandSourceStack source, String text) {
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal(text), false);
        return 1;
    }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        boolean shadowPass = IrisCompat.renderingShadowPass();
        if (event.getStage() == MapSurfaceRenderer.opaqueStage()) {
            if (!shadowPass) prepare();
            draw(event, false);
        } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_PARTICLES && !shadowPass) {
            draw(event, true);
        }
    }

    /** Main pass, once a frame: builds what changed or is missing, drops what is gone, relights. */
    private static void prepare() {
        frame++;
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        BundleGeneration generation = Src2mc.bundles().active();
        if (level == null || generation.sequence() != generationSequence) {
            clear();
            generationSequence = generation.sequence();
            if (level == null) return;
        }
        Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
        double reach = (minecraft.options.getEffectiveRenderDistance() + 1) * 16.0;
        Set<Key> wanted = new HashSet<>();
        int started = 0, hidden = 0;
        for (MapPlacement placement : dev.theredja.src2mc.network.PlacementNetwork.clientIndex(level.dimension().location()).view()) {
            var located = generation.findLocatedMap(placement.campaignId(), placement.mapId()).orElse(null);
            if (located == null || located.map().atlas() == null || located.map().logicProps() == null) continue;
            BundleMap map = located.map();
            PropStates.Key stateKey = PropStates.key(level, placement);
            Map<String, BundleProp> placed = null;
            for (LogicPropTable.Prop prop : map.logicProps().props()) {
                if (prop.stableId() == null) continue;
                if (!PropRenderer.rootActive(placement, prop.stableId())) continue;
                PropStates.State state = PropStates.effective(true, stateKey, map, prop);
                if (state.hidden()) { hidden++; continue; }
                if (placed == null) {
                    placed = new HashMap<>();
                    for (BundleProp record : map.props()) placed.put(record.stableId(), record);
                }
                BundleProp record = placed.get(prop.stableId());
                if (record == null) continue;
                double[] t = record.translation();
                double dx = placement.translation().getX() + t[0] - camera.x, dz = placement.translation().getZ() + t[2] - camera.z;
                if (dx * dx + dz * dz > reach * reach) continue;
                Key key = new Key(placement, prop.entity());
                wanted.add(key);
                Built built = BUILT.get(key);
                int model = prop.model(state.skin());
                if (built != null && built.prop == prop && built.skin == model && built.color == state.color()) {
                    if (needsLight(level, built)) light(level, placement, map, built);
                    continue;
                }
                if (started >= BUILDS_PER_FRAME) continue;
                Built fresh = build(generation, located.bundle(), map, placement, prop, record, model, state.color());
                if (fresh == null) continue;
                started++;
                if (built != null) built.close();
                BUILT.put(key, fresh);
                light(level, placement, map, fresh);
            }
        }
        BUILT.entrySet().removeIf(entry -> {
            if (wanted.contains(entry.getKey())) return false;
            entry.getValue().close();
            return true;
        });
        hiddenLast = hidden;
    }

    private static boolean needsLight(ClientLevel level, Built built) {
        if (built.litFrame == Long.MIN_VALUE) return true;
        if (frame - built.litFrame < LIGHT_CHECK_FRAMES) return false;
        built.litFrame = frame;
        return middleLight(level, built) != built.light;
    }

    private static int middleLight(ClientLevel level, Built built) {
        return LevelRenderer.getLightColor(level, BlockPos.containing(built.bounds.getCenter()));
    }

    /** The prop in one skin and tint, or null while its mesh is still loading. */
    private static Built build(BundleGeneration generation, BundleManifest bundle, BundleMap map, MapPlacement placement,
                               LogicPropTable.Prop prop, BundleProp record, int model, int color) {
        RuntimeMesh mesh = PropRenderer.runtimeMeshes().request(generation.sequence(), bundle, map.models().get(model).contentId()).orElse(null);
        if (mesh == null) return null;
        BundleProp worn = new BundleProp(record.stableId(), model, record.rootCell(), record.translation(), record.rotation(), record.scale());
        double[] t = record.translation();
        BlockPos origin = BlockPos.containing(t[0], t[1], t[2]);
        Map<PageClass, PackedVertices> meshes = new HashMap<>();
        for (var batch : PropRenderer.tessellate(bundle, map, placement, worn, mesh).entrySet()) {
            PackedVertices out = meshes.computeIfAbsent(new PageClass(batch.getKey().page(), batch.getKey().renderClass()), ignored -> new PackedVertices());
            for (PropTessellator.Triangle triangle : batch.getValue()) {
                for (PropTessellator.Vertex vertex : List.of(triangle.a(), triangle.b(), triangle.c())) {
                    out.add((float) (vertex.x() - origin.getX()), (float) (vertex.y() - origin.getY()), (float) (vertex.z() - origin.getZ()),
                        (float) vertex.u(), (float) vertex.v(), (float) vertex.nx(), (float) vertex.ny(), (float) vertex.nz(), 0, color);
                }
            }
        }
        meshes.values().removeIf(PackedVertices::isEmpty);
        double[] box = null;
        for (PackedVertices vertices : meshes.values()) {
            double[] b = vertices.bounds();
            if (box == null) box = b.clone();
            else for (int axis = 0; axis < 3; axis++) { box[axis] = Math.min(box[axis], b[axis]); box[axis + 3] = Math.max(box[axis + 3], b[axis + 3]); }
        }
        BlockPos world = placement.translation().offset(origin);
        AABB bounds = box == null ? new AABB(world)
            : new AABB(box[0], box[1], box[2], box[3], box[4], box[5]).move(world.getX(), world.getY(), world.getZ()).inflate(0.01);
        builds++;
        return new Built(prop, bundle, map.atlas(), model, color, origin, meshes, bounds);
    }

    /** Lights every vertex where it stands, as the aggregates do, and uploads the buffers again. */
    private static void light(ClientLevel level, MapPlacement placement, BundleMap map, Built built) {
        Map<Long, Integer> cache = new HashMap<>();
        SurfaceOcclusion occlusion = MapSurfaceRenderer.occlusionFor(placement, map,
            (x, y, z) -> MapSurfaceRenderer.surfacePresentLive(placement, x, y, z));
        boolean smooth = MapSurfaceRenderer.smoothLighting();
        BlockPos world = placement.translation().offset(built.origin);
        built.close();
        for (var entry : built.meshes.entrySet()) {
            PackedVertices vertices = entry.getValue();
            for (int i = 0; i < vertices.vertices(); i++) {
                float nx = vertices.nx(i), ny = vertices.ny(i), nz = vertices.nz(i);
                float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (length > 1.0e-6f) { nx /= length; ny /= length; nz /= length; } else { nx = 0; ny = 1; nz = 0; }
                double x = world.getX() + vertices.x(i), y = world.getY() + vertices.y(i), z = world.getZ() + vertices.z(i);
                vertices.setLight(i, smooth ? LightSampler.smooth(level, x, y, z, nx, ny, nz, cache, occlusion)
                    : LightSampler.sample(level, x, y, z, nx, ny, nz, cache));
            }
            vertices.index();
            built.buffers.put(entry.getKey(), vertices.upload(MapSurfaceRenderer.neutralEntityId(), MapSurfaceRenderer.indexedMeshes()).buffer());
        }
        built.light = middleLight(level, built);
        built.litFrame = frame;
        relights++;
    }

    private static void draw(RenderLevelStageEvent event, boolean translucent) {
        if (!enabled || BUILT.isEmpty()) return;
        boolean shadowPass = IrisCompat.renderingShadowPass();
        Vec3 camera = event.getCamera().getPosition();
        BundleGeneration generation = Src2mc.bundles().active();
        boolean suppressDepthWrite = translucent && !MapSurfaceRenderer.translucentDepthWrite();
        long drawn = 0;
        for (Map.Entry<Key, Built> entry : BUILT.entrySet()) {
            Built built = entry.getValue();
            if (!shadowPass && MapSurfaceRenderer.frustumCulling() && !event.getFrustum().isVisible(built.bounds)) continue;
            BlockPos world = entry.getKey().placement().translation().offset(built.origin);
            Matrix4f modelView = new Matrix4f(event.getModelViewMatrix())
                .translate((float) (world.getX() - camera.x), (float) (world.getY() - camera.y), (float) (world.getZ() - camera.z));
            for (Map.Entry<PageClass, VertexBuffer> buffer : built.buffers.entrySet()) {
                PageClass key = buffer.getKey();
                if ((key.renderClass() == BundleMaterial.RenderClass.TRANSLUCENT) != translucent) continue;
                ResourceLocation texture = MapSurfaceRenderer.atlasPages().request(generation.sequence(), built.bundle, built.atlas, key.page(), frame)
                    .orElseGet(MapSurfaceRenderer.atlasPages()::placeholderTexture);
                RenderType type = translucent ? RenderType.entityTranslucent(texture)
                    : key.renderClass() == BundleMaterial.RenderClass.SOLID ? RenderType.entitySolid(texture) : RenderType.entityCutout(texture);
                type.setupRenderState();
                MapSurfaceRenderer.applyAtlasFilter(texture);
                if (suppressDepthWrite) com.mojang.blaze3d.systems.RenderSystem.depthMask(false);
                buffer.getValue().bind();
                buffer.getValue().drawWithShader(modelView, event.getProjectionMatrix(),
                    translucent ? GameRenderer.getRendertypeEntityTranslucentShader()
                        : key.renderClass() == BundleMaterial.RenderClass.SOLID
                            ? GameRenderer.getRendertypeEntitySolidShader() : GameRenderer.getRendertypeEntityCutoutShader());
                type.clearRenderState();
                if (suppressDepthWrite) com.mojang.blaze3d.systems.RenderSystem.depthMask(true);
                drawn++;
            }
        }
        VertexBuffer.unbind();
        if (!shadowPass && !translucent) drawnLast = drawn;
    }

    public static void clear() {
        BUILT.values().forEach(Built::close);
        BUILT.clear();
    }
}
