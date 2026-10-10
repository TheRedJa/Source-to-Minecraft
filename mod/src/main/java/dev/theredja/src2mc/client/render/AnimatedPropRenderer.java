package dev.theredja.src2mc.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.bundle.AnimationAsset;
import dev.theredja.src2mc.bundle.AtlasIndex;
import dev.theredja.src2mc.bundle.BundleGeneration;
import dev.theredja.src2mc.bundle.BundleManifest;
import dev.theredja.src2mc.bundle.BundleMap;
import dev.theredja.src2mc.bundle.BundleMaterial;
import dev.theredja.src2mc.bundle.BundleModel;
import dev.theredja.src2mc.bundle.BundleProp;
import dev.theredja.src2mc.bundle.LogicPropTable;
import dev.theredja.src2mc.bundle.MoverTable;
import dev.theredja.src2mc.bundle.RuntimeMesh;
import dev.theredja.src2mc.world.MapPlacement;
import dev.theredja.src2mc.world.MoverRegistry;
import dev.theredja.src2mc.world.PropStates;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Draws the logic props whose model animates (format.md section 19), placed or riding a mover,
 * in the pose their sequence has now. The server says which sequence plays from which cycle at
 * which map time; this advances it on the client's clock, as Source's client interpolates the
 * cycle, and blends out of the previous sequence over the fade Source's sequence transitioner
 * uses.
 *
 * <p>A triangle all three of whose corners follow one bone alone is drawn in a buffer of that
 * bone's, with the bone's transform: posing it costs a matrix. The rest, whose corners several
 * bones pull, are skinned on the CPU, as the studio renderer skins them, each time the pose
 * changes, and uploaded again. Vertices keep model-space normals; the draw turns vanilla's two
 * shading lights back by each buffer's rotation instead, as {@link MoverRenderer} does.
 * {@code /src2mc_anim} shows counts and switches drawing off for comparison.
 */
@EventBusSubscriber(modid = Src2mc.MOD_ID, value = Dist.CLIENT)
public final class AnimatedPropRenderer {
    private AnimatedPropRenderer() {}

    private record Key(MapPlacement placement, int entity) {}
    /** One buffer: a bone's rigid triangles ({@code bone >= 0}) or the skinned ones ({@code -1}). */
    private record Part(int bone, int page, BundleMaterial.RenderClass renderClass) {}

    /** Skinned triangles: each vertex a blend of its source triangle's three corners. */
    private static final class Blended {
        final PackedVertices vertices;
        int[] corners = new int[96];
        float[] weights = new float[96];
        boolean[] flipped = new boolean[32];
        int count;
        Blended() { vertices = new PackedVertices(); }
        /** Another prop's copy: its own vertices, the corners and weights shared, which nothing changes once built. */
        Blended(Blended built) {
            vertices = built.vertices.copy();
            corners = built.corners; weights = built.weights; flipped = built.flipped; count = built.count;
        }
        void add(int a, int b, int c, float wa, float wb, float wc, boolean flip) {
            if (count * 3 == corners.length) {
                corners = java.util.Arrays.copyOf(corners, corners.length * 2);
                weights = java.util.Arrays.copyOf(weights, weights.length * 2);
                flipped = java.util.Arrays.copyOf(flipped, flipped.length * 2);
            }
            corners[count * 3] = a; corners[count * 3 + 1] = b; corners[count * 3 + 2] = c;
            weights[count * 3] = wa; weights[count * 3 + 1] = wb; weights[count * 3 + 2] = wc;
            flipped[count++] = flip;
        }
    }

    /** The sequence being blended out of: Source's previous animation layer. */
    private record Layer(int sequence, float cycle, float rate, double time, float fadeOut) {}

    private static final class Built implements AutoCloseable {
        final LogicPropTable.Prop prop;
        final int model, color;
        final BundleManifest bundle;
        final AtlasIndex atlas;
        /** Null for a model without animation, drawn here because it is mounted. */
        final AnimationAsset animation;
        final RuntimeMesh mesh;
        /** The mesh's vertex array, copied once: {@link RuntimeMesh#vertices} copies on every call. */
        final float[] vertices;
        final Map<Part, PackedVertices> rigid;
        final Map<Part, double[]> rigidBounds;
        final Map<Part, Blended> blended;
        /** The map's baked light; null for none. */
        dev.theredja.src2mc.bundle.LightTable lightTable;
        final Map<Part, VertexBuffer> buffers = new HashMap<>();
        final float[] positions, rotations, skin, previousPositions, previousRotations;
        /** Model-space corners posed this frame, by mesh vertex; {@code posedFrame} says which are current. */
        final float[] posed, posedNormals;
        final long[] posedFrame;
        int poseSequence = -2, lastSequence = -2, lastParity;
        float poseCycle = Float.NaN, poseWeight = -1, lastCycle, lastRate;
        double lastTime;
        Layer previous;
        boolean hidden, skinnedStale = true;
        /** The model-space box around the posed mesh. */
        final float[] box = new float[6];
        int light = Integer.MIN_VALUE;
        /**
         * Lit on a worker against a copy of the world: a copy, or a prop riding a mover, whose
         * light changes as it goes. Relighting a big piece of debris on the render thread cost
         * up to 80 ms (sp_a2_bts4, 2026-10-04).
         */
        boolean onWorker;
        /** A relight sampling on a worker; its light goes up on the render thread once it is done. */
        java.util.concurrent.CompletableFuture<Void> relighting;
        /** Each bone buffer's bytes, kept so a relight sends only its light again. */
        final Map<Part, PackedVertices.LightPatch> patches = new HashMap<>();
        /** The skinned triangles' new light, per part, from the relight in progress. */
        Map<Part, int[]> blendedLight;
        /** Not drawn until its first light is in. */
        boolean unlit = true;
        /** Where its middle was in the world when it was last lit; null before. */
        org.joml.Vector3d litAt;
        long litFrame = Long.MIN_VALUE;

        Built(LogicPropTable.Prop prop, int model, int color, BundleManifest bundle, AtlasIndex atlas, AnimationAsset animation, RuntimeMesh mesh,
              Map<Part, PackedVertices> rigid, Map<Part, double[]> rigidBounds, Map<Part, Blended> blended) {
            this.prop = prop; this.model = model; this.color = color; this.bundle = bundle; this.atlas = atlas; this.animation = animation;
            this.mesh = mesh; this.vertices = mesh.vertices(); this.rigid = rigid; this.rigidBounds = rigidBounds; this.blended = blended;
            int bones = animation == null ? 1 : animation.boneCount();
            positions = new float[bones * 3]; rotations = new float[bones * 4]; skin = new float[bones * 12];
            // A model without animation is one bone that stays where it is.
            if (animation == null) { skin[0] = skin[5] = skin[10] = 1; }
            previousPositions = new float[bones * 3]; previousRotations = new float[bones * 4];
            posed = new float[mesh.vertexCount() * 3]; posedNormals = new float[mesh.vertexCount() * 3];
            posedFrame = new long[mesh.vertexCount()];
            java.util.Arrays.fill(posedFrame, Long.MIN_VALUE);
        }
        @Override public void close() {
            buffers.values().forEach(VertexBuffer::close);
            buffers.clear();
            List<PackedVertices.LightPatch> kept = List.copyOf(patches.values());
            patches.clear();
            // A worker may still be writing them.
            if (relighting != null && !relighting.isDone()) relighting.whenComplete((ignored, failure) -> kept.forEach(PackedVertices.LightPatch::close));
            else kept.forEach(PackedVertices.LightPatch::close);
        }
    }

    /** Where a prop is drawn: its transform within its placement, or within the mover it rides. */
    private record Placing(MapPlacement placement, double[] translation, double[] rotation, double scale, UUID mover) {}

    private static final Map<Key, Built> BUILT = new HashMap<>();
    private static final Map<Key, Placing> PLACING = new HashMap<>();
    private static final int BUILDS_PER_FRAME = 4, LIGHT_CHECK_FRAMES = 20;
    private static final Vector3f LIGHT_0 = new Vector3f(0.2F, 1.0F, -0.7F).normalize();
    private static final Vector3f LIGHT_1 = new Vector3f(-0.2F, 1.0F, 0.7F).normalize();
    private static final Vector3f NETHER_LIGHT_1 = new Vector3f(-0.2F, -1.0F, 0.7F).normalize();
    private static long frame, generationSequence = -1;
    private static boolean enabled = true;
    private static long drawnLast, builds, reskins, reskinNanosLast, posesLast, lights, lightNanosMax, buildNanosMax;

    /** Whether a logic prop is drawn here rather than by {@link LogicPropRenderer} or its mover. */
    static boolean animated(BundleMap map, LogicPropTable.Prop prop, PropStates.State state) {
        return map.models().get(prop.model(state.skin())).animation() != null;
    }

    public static String status() {
        return "src2mc animated props: " + BUILT.size() + " built, " + posesLast + " posed and " + drawnLast + " buffers drawn last frame; "
            + builds + String.format(java.util.Locale.ROOT, " builds (%.2f ms max), ", buildNanosMax / 1e6) + lights
            + String.format(java.util.Locale.ROOT, " lit (%.2f ms max), ", lightNanosMax / 1e6)
            + reskins + String.format(java.util.Locale.ROOT, " skinned uploads (%.2f ms last), ", reskinNanosLast / 1e6)
            + mountsLast + " mounted"
            + (enabled ? "" : " (drawing off)");
    }

    @SubscribeEvent
    public static void registerCommand(net.neoforged.neoforge.client.event.RegisterClientCommandsEvent event) {
        event.getDispatcher().register(net.minecraft.commands.Commands.literal("src2mc_anim")
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
        boolean timed = event.getStage() == MapSurfaceRenderer.opaqueStage() || event.getStage() == RenderLevelStageEvent.Stage.AFTER_PARTICLES;
        GpuTimer.Phase phase = IrisCompat.renderingShadowPass() ? GpuTimer.Phase.ANIMATED_PROPS_SHADOW : GpuTimer.Phase.ANIMATED_PROPS;
        if (timed) GpuTimer.begin(phase);
        try {
            renderStage(event);
        } finally {
            if (timed) GpuTimer.end(phase);
        }
    }

    private static void renderStage(RenderLevelStageEvent event) {
        boolean shadowPass = IrisCompat.renderingShadowPass();
        if (event.getStage() == MapSurfaceRenderer.opaqueStage()) {
            if (!shadowPass) prepare(event);
            draw(event, false);
        } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_PARTICLES && !shadowPass) {
            draw(event, true);
        }
    }

    /** Main pass, once a frame: builds what is missing, drops what is gone, poses and lights. */
    private static void prepare(RenderLevelStageEvent event) {
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
        double clock = dev.theredja.src2mc.client.logic.ScreenOverlay.clock();
        Map<Long, Map<Integer, UUID>> movers = new HashMap<>();
        for (MoverRegistry.Instance instance : MoverRegistry.instances(true))
            movers.computeIfAbsent(instance.anchor(), ignored -> new HashMap<>()).put(instance.entity(), instance.subLevel());
        Set<Key> wanted = new HashSet<>();
        startedThisFrame = 0;
        posedThisFrame = 0;
        int mounts = 0;
        for (MapPlacement placement : dev.theredja.src2mc.network.PlacementNetwork.clientIndex(level.dimension().location()).view()) {
            var located = generation.findLocatedMap(placement.campaignId(), placement.mapId()).orElse(null);
            if (located == null || located.map().atlas() == null || located.map().logicProps() == null) continue;
            BundleMap map = located.map();
            PropStates.Key stateKey = PropStates.key(level, placement);
            double time = PropStates.mapTime(stateKey, clock);
            Map<String, BundleProp> records = null;
            for (LogicPropTable.Prop prop : map.logicProps().props()) {
                if (prop.sequence() < 0) continue;
                PropStates.State state = PropStates.effective(true, stateKey, map, prop);
                // One parented anew, or a template's, is drawn by its mount below.
                if (!animated(map, prop, state) || dev.theredja.src2mc.world.PropMounts.mounted(stateKey, prop.entity())) continue;
                Key key = new Key(placement, prop.entity());
                Placing placing = PLACING.get(key);
                // A mover that spawned again rides on a new sub-level.
                if (placing != null && placing.mover != null
                    && !placing.mover.equals(movers.getOrDefault(placement.anchorWorld().asLong(), Map.of()).get(prop.mover()))) placing = null;
                if (placing == null || placing.placement != placement) {
                    if (prop.stableId() != null) {
                        if (records == null) records = records(map);
                        BundleProp record = records.get(prop.stableId());
                        if (record == null) continue;
                        placing = new Placing(placement, record.translation(), record.rotation(), record.scale(), null);
                    } else {
                        MoverTable.Prop rider = rider(map, prop);
                        UUID subLevel = movers.getOrDefault(placement.anchorWorld().asLong(), Map.of()).get(prop.mover());
                        if (rider == null || subLevel == null) continue;
                        placing = new Placing(placement, rider.translation(), rider.rotation(), rider.scale(), subLevel);
                    }
                    PLACING.put(key, placing);
                }
                prepareProp(level, generation, located.bundle(), map, prop, state, key, placing, camera, reach, time, wanted, false);
            }
            // Template copies and props parented anew, wherever their mount has them.
            mounts += dev.theredja.src2mc.world.PropMounts.of(stateKey).size();
            for (var mount : dev.theredja.src2mc.world.PropMounts.of(stateKey).values()) {
                LogicPropTable.Prop prop = map.logicProps().byEntity(mount.source());
                if (prop == null) continue;
                PropStates.State stored = PropStates.stored(true, stateKey, mount.entity());
                // A copy's state is its own; until it arrives, the prop as it spawns, shown.
                PropStates.State state = stored != null ? stored
                    : new PropStates.State(0, prop.skin(), map.models().get(prop.model(prop.skin())).color(), prop.sequence(), 0, 0, 0, 0, -1);
                if (records == null) records = records(map);
                Placing placing = mounted(map, placement, prop, mount, records, movers);
                if (placing == null) continue;
                Key key = new Key(placement, mount.entity());
                PLACING.put(key, placing);
                prepareProp(level, generation, located.bundle(), map, prop, state, key, placing, camera, reach, time, wanted, true);
            }
        }
        BUILT.entrySet().removeIf(entry -> {
            if (wanted.contains(entry.getKey())) return false;
            entry.getValue().close();
            PLACING.remove(entry.getKey());
            return true;
        });
        posesLast = posedThisFrame;
        mountsLast = mounts;
    }

    private static int startedThisFrame, posedThisFrame, mountsLast;

    private static Map<String, BundleProp> records(BundleMap map) {
        Map<String, BundleProp> records = new HashMap<>();
        for (BundleProp record : map.props()) records.put(record.stableId(), record);
        return records;
    }

    private static boolean carrierHidden(ClientLevel level, UUID subLevel) {
        MoverRegistry.Resolved resolved = MoverRegistry.resolve(level, subLevel);
        return resolved != null && (MoverRegistry.state(true, resolved) & MoverRegistry.HIDDEN) != 0;
    }

    /** One prop's turn: built when missing or changed (a few a frame), then posed, skinned and lit. */
    private static void prepareProp(ClientLevel level, BundleGeneration generation, BundleManifest bundle, BundleMap map, LogicPropTable.Prop prop,
                                    PropStates.State state, Key key, Placing placing, Vec3 camera, double reach, double time, Set<Key> wanted,
                                    boolean mounted) {
        if (placing.mover == null) {
            // A placed prop whose root block is gone is not drawn; a copy has no root of its own.
            if (key.entity() == prop.entity() && prop.stableId() != null && !PropRenderer.rootActive(placing.placement, prop.stableId())) return;
            double dx = placing.placement.translation().getX() + placing.translation[0] - camera.x;
            double dz = placing.placement.translation().getZ() + placing.translation[2] - camera.z;
            if (dx * dx + dz * dz > reach * reach) return;
        }
        wanted.add(key);
        int model = prop.model(state.skin());
        Built built = BUILT.get(key);
        if (built == null || built.prop != prop || built.model != model || built.color != state.color()) {
            if (startedThisFrame >= BUILDS_PER_FRAME) return;
            long started = System.nanoTime();
            Built fresh = build(generation, bundle, map, prop, model, state.color());
            if (fresh == null) return;
            buildNanosMax = Math.max(buildNanosMax, System.nanoTime() - started);
            startedThisFrame++;
            if (built != null) built.close();
            fresh.onWorker = mounted || placing.mover != null;
            BUILT.put(key, fresh);
            built = fresh;
        }
        built.hidden = state.hidden();
        if (built.hidden) return;
        if (built.animation != null && pose(built, state, time)) posedThisFrame++;
        if (built.skinnedStale) skin(built);
        if (built.relighting != null) {
            if (!built.relighting.isDone()) return;
            finishRelight(built);
        }
        // A sub-level handed to a new entity is hidden until Sable has it at its new place: lit
        // now, the prop would take the light of where it was.
        if (placing.mover != null && carrierHidden(level, placing.mover)) return;
        if (needsLight(level, placing, built)) {
            long started = System.nanoTime();
            if (built.onWorker) relightOnWorker(level, placing, built);
            else light(level, placing, map, built);
            lights++;
            lightNanosMax = Math.max(lightNanosMax, System.nanoTime() - started);
        }
    }

    /**
     * Where a mount has a prop: its compiled place -- its record in the map, or its seat on the
     * mover it was exported riding -- moved by the mount's motion, in the map or on the
     * carrying mover's sub-level. Null while that mover is not known here.
     */
    private static Placing mounted(BundleMap map, MapPlacement placement, LogicPropTable.Prop prop, dev.theredja.src2mc.world.PropMounts.Mount mount,
                                   Map<String, BundleProp> records, Map<Long, Map<Integer, UUID>> movers) {
        double[] translation, rotation;
        double scale;
        if (prop.stableId() != null) {
            BundleProp record = records.get(prop.stableId());
            if (record == null) return null;
            translation = record.translation(); rotation = record.rotation(); scale = record.scale();
        } else {
            MoverTable.Prop rider = rider(map, prop);
            MoverTable.Mover own = rider == null ? null : map.movers().movers().get(map.movers().indexOfEntity(prop.mover()));
            if (own == null) return null;
            double[] seat = rider.translation();
            translation = new double[]{seat[0] + own.originX(), seat[1] + own.originY(), seat[2] + own.originZ()};
            rotation = rider.rotation(); scale = rider.scale();
        }
        org.joml.Quaterniond motion = new org.joml.Quaterniond(mount.qx(), mount.qy(), mount.qz(), mount.qw());
        org.joml.Vector3d moved = motion.transform(new org.joml.Vector3d(translation[0], translation[1], translation[2])).add(mount.x(), mount.y(), mount.z());
        org.joml.Quaterniond turned = motion.mul(new org.joml.Quaterniond(rotation[0], rotation[1], rotation[2], rotation[3]), new org.joml.Quaterniond());
        UUID subLevel = null;
        if (mount.mover() >= 0) {
            subLevel = movers.getOrDefault(placement.anchorWorld().asLong(), Map.of()).get(mount.mover());
            MoverRegistry.Instance carrier = subLevel == null ? null : MoverRegistry.instance(true, subLevel);
            int index = carrier == null || map.movers() == null ? -1 : map.movers().indexOfEntity(carrier.source());
            if (index < 0) return null;
            MoverTable.Mover mover = map.movers().movers().get(index);
            moved.sub(mover.originX(), mover.originY(), mover.originZ());
        }
        return new Placing(placement, new double[]{moved.x, moved.y, moved.z}, new double[]{turned.x, turned.y, turned.z, turned.w}, scale, subLevel);
    }

    private static MoverTable.Prop rider(BundleMap map, LogicPropTable.Prop prop) {
        if (map.movers() == null) return null;
        int index = map.movers().indexOfEntity(prop.mover());
        if (index < 0) return null;
        for (MoverTable.Prop rider : map.movers().movers().get(index).props()) if (rider.entity() == prop.entity()) return rider;
        return null;
    }

    /** One model reference's triangles in one tint, cut and split as {@link #build} wants them; each prop copies them. */
    private record Geometry(Map<Part, PackedVertices> rigid, Map<Part, double[]> rigidBounds, Map<Part, Blended> blended) {}

    /**
     * Tessellated once per model reference and tint: a conveyor makes a copy of the same debris
     * every few seconds, and cutting a big model again each time cost a frame. By identity.
     */
    private static final Map<BundleModel, Map<Integer, java.util.concurrent.CompletableFuture<Geometry>>> GEOMETRY = new java.util.IdentityHashMap<>();

    /** The prop in one skin and tint, split into bone buffers and skinned triangles; null while its mesh loads. */
    private static Built build(BundleGeneration generation, BundleManifest bundle, BundleMap map, LogicPropTable.Prop prop, int model, int color) {
        BundleModel reference = map.models().get(model);
        AnimationAsset animation = reference.animation();
        RuntimeMesh mesh = PropRenderer.runtimeMeshes().request(generation.sequence(), bundle, reference.contentId()).orElse(null);
        if (mesh == null || (animation != null && animation.vertexCount() != mesh.vertexCount())) return null;
        Map<Integer, java.util.concurrent.CompletableFuture<Geometry>> tints = GEOMETRY.computeIfAbsent(reference, ignored -> new HashMap<>());
        // Cut on a worker the first time; the prop is built once it is done.
        java.util.concurrent.CompletableFuture<Geometry> cutting = tints.computeIfAbsent(color,
            ignored -> MeshBuildPool.submit(() -> tessellate(map, reference, animation, mesh, model, color)));
        if (!cutting.isDone()) return null;
        Geometry geometry;
        try {
            geometry = cutting.join();
        } catch (RuntimeException failure) {
            tints.remove(color);
            return null;
        }
        Map<Part, PackedVertices> rigid = new HashMap<>();
        Map<Part, Blended> blended = new HashMap<>();
        geometry.rigid().forEach((part, vertices) -> rigid.put(part, vertices.copy()));
        geometry.blended().forEach((part, built) -> blended.put(part, new Blended(built)));
        builds++;
        Built built = new Built(prop, model, color, bundle, map.atlas(), animation, mesh, rigid, geometry.rigidBounds(), blended);
        built.lightTable = map.light();
        // A still model's box is where it stays.
        if (animation == null) bounds(built);
        return built;
    }

    private static Geometry tessellate(BundleMap map, BundleModel reference, AnimationAsset animation, RuntimeMesh mesh, int model, int color) {
        BundleProp identity = new BundleProp("", model, new int[3], new double[3], new double[] {0, 0, 0, 1}, 1);
        float[] vertices = mesh.vertices();
        int[] indices = mesh.indices();
        Map<Part, PackedVertices> rigid = new HashMap<>();
        Map<Part, double[]> rigidBounds = new HashMap<>();
        Map<Part, Blended> blended = new HashMap<>();
        for (RuntimeMesh.Submesh submesh : mesh.submeshes()) {
            if (submesh.materialSlot() < 0 || submesh.materialSlot() >= reference.materialSlotCount()) continue;
            int materialId = reference.materialIds()[submesh.materialSlot()];
            if (materialId < 0 || materialId >= map.materials().size()) continue;
            BundleMaterial material = map.materials().get(materialId);
            if (!material.textured() || material.renderClass() == BundleMaterial.RenderClass.FALLBACK) continue;
            AtlasIndex.Texture texture = map.atlas().textures().get(material.texture().contentId());
            if (texture == null) continue;
            int row = SurfaceEffects.row(materialId, material);
            if (row != 0) SurfaceEffects.register(map);
            for (int first = submesh.firstIndex(); first < submesh.firstIndex() + submesh.indexCount(); first += 3) {
                int a = indices[first], b = indices[first + 1], c = indices[first + 2];
                int bone = animation == null ? 0 : rigidBone(animation, a, b, c);
                List<PropTessellator.Triangle> pieces = PropTessellator.tessellate(vertices, indices, first, 3,
                    identity, material.texture(), texture, map.atlas().pageSize());
                if (material.doubleSided()) pieces = PropTessellator.withBackFaces(pieces);
                for (int i = 0; i < pieces.size(); i++) {
                    PropTessellator.Triangle piece = pieces.get(i);
                    boolean back = material.doubleSided() && (i & 1) == 1;
                    Part part = new Part(bone, piece.page(), material.renderClass());
                    if (bone >= 0) {
                        PackedVertices out = rigid.computeIfAbsent(part, ignored -> new PackedVertices());
                        out.row(row);
                        for (PropTessellator.Vertex vertex : List.of(piece.a(), piece.b(), piece.c())) add(out, vertex, color);
                    } else {
                        Blended out = blended.computeIfAbsent(part, ignored -> new Blended());
                        out.vertices.row(row);
                        for (PropTessellator.Vertex vertex : List.of(piece.a(), piece.b(), piece.c())) {
                            float[] w = barycentric(vertices, a, b, c, vertex);
                            out.add(a, b, c, w[0], w[1], w[2], back);
                            add(out.vertices, vertex, color);
                        }
                    }
                }
            }
        }
        rigid.values().removeIf(PackedVertices::isEmpty);
        blended.values().removeIf(part -> part.count == 0);
        for (var entry : rigid.entrySet()) rigidBounds.put(entry.getKey(), entry.getValue().bounds());
        return new Geometry(rigid, rigidBounds, blended);
    }

    /** Lit by the ambient cube where the prop is, set for each draw, as Source lights a dynamic prop. */
    private static final float[] AMBIENT_LIT = {0, 0, 0, BakedLighting.AMBIENT};

    private static void add(PackedVertices out, PropTessellator.Vertex vertex, int color) {
        out.add((float) vertex.x(), (float) vertex.y(), (float) vertex.z(), (float) vertex.u(), (float) vertex.v(),
            (float) vertex.nx(), (float) vertex.ny(), (float) vertex.nz(), 0, color, AMBIENT_LIT);
    }

    /** The one bone all three corners follow wholly, or -1 for a triangle several bones pull. */
    private static int rigidBone(AnimationAsset animation, int a, int b, int c) {
        int bone = -1;
        for (int vertex : new int[] {a, b, c}) {
            if (animation.boneCount(vertex) != 1 || animation.weight(vertex, 0) < 0.9999f) return -1;
            int own = animation.bone(vertex, 0);
            if (bone >= 0 && own != bone) return -1;
            bone = own;
        }
        return bone;
    }

    /** Where in the source triangle a piece's vertex lies; the pieces are cut out of it in its own plane. */
    private static float[] barycentric(float[] vertices, int a, int b, int c, PropTessellator.Vertex p) {
        double ax = vertices[a * 8], ay = vertices[a * 8 + 1], az = vertices[a * 8 + 2];
        double v0x = vertices[b * 8] - ax, v0y = vertices[b * 8 + 1] - ay, v0z = vertices[b * 8 + 2] - az;
        double v1x = vertices[c * 8] - ax, v1y = vertices[c * 8 + 1] - ay, v1z = vertices[c * 8 + 2] - az;
        double v2x = p.x() - ax, v2y = p.y() - ay, v2z = p.z() - az;
        double d00 = v0x * v0x + v0y * v0y + v0z * v0z, d01 = v0x * v1x + v0y * v1y + v0z * v1z, d11 = v1x * v1x + v1y * v1y + v1z * v1z;
        double d20 = v2x * v0x + v2y * v0y + v2z * v0z, d21 = v2x * v1x + v2y * v1y + v2z * v1z;
        double denominator = d00 * d11 - d01 * d01;
        if (Math.abs(denominator) < 1e-18) return new float[] {1, 0, 0};
        double v = (d11 * d20 - d01 * d21) / denominator, w = (d00 * d21 - d01 * d20) / denominator;
        return new float[] {(float) (1 - v - w), (float) v, (float) w};
    }

    /**
     * Brings the pose to the state at map time {@code time}: the sequence's cycle advanced from the
     * server's, blended out of the previous sequence while it fades. True when the pose changed.
     */
    private static boolean pose(Built built, PropStates.State state, double time) {
        AnimationAsset animation = built.animation;
        int sequence = Math.max(0, Math.min(animation.sequences().size() - 1, state.sequence() < 0 ? built.prop.sequence() : state.sequence()));
        AnimationAsset.Sequence current = animation.sequence(sequence);
        if (sequence != built.lastSequence || state.parity() != built.lastParity) {
            // CSequenceTransitioner: the old sequence keeps playing underneath, fading out.
            if (built.lastSequence >= 0 && (current.flags() & AnimationAsset.STUDIO_SNAP) == 0) {
                AnimationAsset.Sequence last = animation.sequence(built.lastSequence);
                float fade = Math.min(last.fadeOut(), current.fadeIn());
                if (fade > 0) built.previous = new Layer(built.lastSequence, cycle(last, built.lastCycle, built.lastRate, built.lastTime, time),
                    built.lastRate, time, fade);
            } else {
                built.previous = null;
            }
            built.lastSequence = sequence;
            built.lastParity = state.parity();
        }
        built.lastCycle = state.cycle(); built.lastRate = state.rate(); built.lastTime = state.time();
        float cycle = cycle(current, state.cycle(), state.rate(), state.time(), time);
        float weight = 0;
        if (built.previous != null) {
            // C_AnimationLayer::GetFadeout: a spline from 1 to 0 over the fade.
            float s = (float) (1 - (time - built.previous.time) / built.previous.fadeOut);
            if (s <= 0) built.previous = null;
            else weight = s >= 1 ? 1 : 3 * s * s - 2 * s * s * s;
        }
        if (sequence == built.poseSequence && cycle == built.poseCycle && weight == built.poseWeight) return false;
        animation.localPose(sequence, cycle, built.positions, built.rotations);
        if (built.previous != null && weight > 0) {
            Layer layer = built.previous;
            AnimationAsset.Sequence old = animation.sequence(layer.sequence);
            float oldCycle = cycle(old, layer.cycle, layer.rate, layer.time, time);
            animation.localPose(layer.sequence, oldCycle, built.previousPositions, built.previousRotations);
            for (int bone = 0; bone < animation.boneCount(); bone++) {
                float s = weight * old.boneWeight(bone);
                if (s <= 0) continue;
                s = Math.min(1, s);
                for (int i = 0; i < 3; i++)
                    built.positions[bone * 3 + i] += (built.previousPositions[bone * 3 + i] - built.positions[bone * 3 + i]) * s;
                AnimationAsset.slerp(built.rotations, bone * 4, built.previousRotations, bone * 4, s, built.rotations, bone * 4);
            }
        }
        animation.skinning(built.positions, built.rotations, built.skin);
        built.poseSequence = sequence; built.poseCycle = cycle; built.poseWeight = weight;
        built.skinnedStale = true;
        bounds(built);
        return true;
    }

    /** The cycle at {@code time}: advanced from {@code start} at {@code rate}, wrapped when it loops, held at an end otherwise. */
    private static float cycle(AnimationAsset.Sequence sequence, float start, float rate, double from, double time) {
        if ((sequence.flags() & AnimationAsset.STUDIO_REALTIME) != 0 && rate != 0) {
            double cycle = time * sequence.cyclesPerSecond();
            return (float) (cycle - Math.floor(cycle));
        }
        float cycle = (float) (start + Math.max(0, time - from) * sequence.cyclesPerSecond() * rate);
        if (cycle >= 0 && cycle < 1) return cycle;
        if (sequence.loops()) return (float) (cycle - Math.floor(cycle));
        return cycle < 0 ? 0 : 1;
    }

    /** The model-space box of the posed buffers. */
    private static void bounds(Built built) {
        float[] box = built.box;
        box[0] = box[1] = box[2] = Float.POSITIVE_INFINITY;
        box[3] = box[4] = box[5] = Float.NEGATIVE_INFINITY;
        for (var entry : built.rigidBounds.entrySet()) {
            double[] b = entry.getValue();
            if (b == null) continue;
            int at = entry.getKey().bone() * 12;
            for (int corner = 0; corner < 8; corner++) {
                float x = (float) ((corner & 1) == 0 ? b[0] : b[3]), y = (float) ((corner & 2) == 0 ? b[1] : b[4]), z = (float) ((corner & 4) == 0 ? b[2] : b[5]);
                include(box, transform(built.skin, at, x, y, z, 0), transform(built.skin, at, x, y, z, 1), transform(built.skin, at, x, y, z, 2));
            }
        }
        // The skinned triangles add their corners when they are skinned.
    }

    private static void include(float[] box, float x, float y, float z) {
        box[0] = Math.min(box[0], x); box[1] = Math.min(box[1], y); box[2] = Math.min(box[2], z);
        box[3] = Math.max(box[3], x); box[4] = Math.max(box[4], y); box[5] = Math.max(box[5], z);
    }

    private static float transform(float[] m, int at, float x, float y, float z, int row) {
        return m[at + row * 4] * x + m[at + row * 4 + 1] * y + m[at + row * 4 + 2] * z + m[at + row * 4 + 3];
    }

    /** Skins the triangles several bones pull, as the studio renderer does: each corner by its bones, each point by its corners. */
    private static void skin(Built built) {
        built.skinnedStale = false;
        if (built.blended.isEmpty() || built.litFrame == Long.MIN_VALUE) return;
        long started = System.nanoTime();
        AnimationAsset animation = built.animation;
        float[] vertices = built.vertices;
        long stamp = ++reskins;
        for (var entry : built.blended.entrySet()) {
            Blended part = entry.getValue();
            for (int i = 0; i < part.count; i++) {
                float x = 0, y = 0, z = 0, nx = 0, ny = 0, nz = 0;
                for (int k = 0; k < 3; k++) {
                    int corner = part.corners[i * 3 + k];
                    if (built.posedFrame[corner] != stamp) {
                        float px = vertices[corner * 8], py = vertices[corner * 8 + 1], pz = vertices[corner * 8 + 2];
                        float qx = vertices[corner * 8 + 3], qy = vertices[corner * 8 + 4], qz = vertices[corner * 8 + 5];
                        float ox = 0, oy = 0, oz = 0, mx = 0, my = 0, mz = 0;
                        for (int slot = 0; slot < animation.boneCount(corner); slot++) {
                            int at = animation.bone(corner, slot) * 12;
                            float w = animation.weight(corner, slot);
                            float[] m = built.skin;
                            ox += w * (m[at] * px + m[at + 1] * py + m[at + 2] * pz + m[at + 3]);
                            oy += w * (m[at + 4] * px + m[at + 5] * py + m[at + 6] * pz + m[at + 7]);
                            oz += w * (m[at + 8] * px + m[at + 9] * py + m[at + 10] * pz + m[at + 11]);
                            mx += w * (m[at] * qx + m[at + 1] * qy + m[at + 2] * qz);
                            my += w * (m[at + 4] * qx + m[at + 5] * qy + m[at + 6] * qz);
                            mz += w * (m[at + 8] * qx + m[at + 9] * qy + m[at + 10] * qz);
                        }
                        built.posed[corner * 3] = ox; built.posed[corner * 3 + 1] = oy; built.posed[corner * 3 + 2] = oz;
                        built.posedNormals[corner * 3] = mx; built.posedNormals[corner * 3 + 1] = my; built.posedNormals[corner * 3 + 2] = mz;
                        built.posedFrame[corner] = stamp;
                        include(built.box, ox, oy, oz);
                    }
                    float w = part.weights[i * 3 + k];
                    x += w * built.posed[corner * 3]; y += w * built.posed[corner * 3 + 1]; z += w * built.posed[corner * 3 + 2];
                    nx += w * built.posedNormals[corner * 3]; ny += w * built.posedNormals[corner * 3 + 1]; nz += w * built.posedNormals[corner * 3 + 2];
                }
                float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz), sign = part.flipped[i] ? -1 : 1;
                if (length > 1e-6f) { nx *= sign / length; ny *= sign / length; nz *= sign / length; } else { nx = 0; ny = 1; nz = 0; }
                part.vertices.set(i, x, y, z, nx, ny, nz);
            }
            VertexBuffer old = built.buffers.remove(entry.getKey());
            if (old != null) old.close();
            built.buffers.put(entry.getKey(), part.vertices.upload(MapSurfaceRenderer.neutralEntityId(), false).buffer());
        }
        reskinNanosLast = System.nanoTime() - started;
    }

    /**
     * Whether the prop is due to be lit again: never lit, moved {@link #RELIGHT_DISTANCE} since,
     * or the light at its middle changed. Riding a mover it moves through light that one cell
     * does not show: a belt module lit where its reused sub-level last stood kept that light
     * all the way along the belt (sp_a2_bts4, 2026-10-04).
     */
    private static boolean needsLight(ClientLevel level, Placing placing, Built built) {
        if (built.litFrame == Long.MIN_VALUE) return true;
        if (frame - built.litFrame < LIGHT_CHECK_FRAMES) return false;
        built.litFrame = frame;
        org.joml.Vector3d at = middleAt(level, placing, built);
        BlockPos middle = middle(level, placing, built);
        // Without a Minecraft light source near where it was and is, moving changes nothing to relight.
        if (built.light == 0 && (middle == null || blockLight(level, middle) == 0)) return false;
        if (at != null && (built.litAt == null || built.litAt.distance(at) > RELIGHT_DISTANCE)) return true;
        return middle != null && blockLight(level, middle) != built.light;
    }

    private static final double RELIGHT_DISTANCE = 2;

    /** Block light alone: sky light no longer lights the map, so only a light source relights it. */
    private static int blockLight(ClientLevel level, BlockPos at) {
        return level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, at);
    }

    /** Where the middle of the posed prop is in the world now, or null when its mover is not known here. */
    private static org.joml.Vector3d middleAt(ClientLevel level, Placing placing, Built built) {
        float[] b = built.box;
        if (!(b[0] <= b[3])) return null;
        org.joml.Matrix4d toWorld = toWorld(level, placing);
        return toWorld == null ? null : toWorld.transformPosition(new org.joml.Vector3d((b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2));
    }

    /** The world cell at the middle of the posed prop, or null when its mover is not known here. */
    private static BlockPos middle(ClientLevel level, Placing placing, Built built) {
        float[] b = built.box;
        if (!(b[0] <= b[3])) return null;
        org.joml.Matrix4d toWorld = toWorld(level, placing);
        if (toWorld == null) return null;
        org.joml.Vector3d world = toWorld.transformPosition(new org.joml.Vector3d((b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2));
        return BlockPos.containing(world.x, world.y, world.z);
    }

    /**
     * Model space to the world, where the prop is now: within its placement, or within its mover's
     * sub-level as Sable has it -- {@code R (plot + p - C) + position}, as {@link MoverRenderer}
     * places it. Null when the mover is not known here.
     */
    private static org.joml.Matrix4d toWorld(ClientLevel level, Placing placing) {
        double[] q = placing.rotation, t = placing.translation;
        org.joml.Matrix4d own = new org.joml.Matrix4d().translate(t[0], t[1], t[2])
            .rotate(new org.joml.Quaterniond(q[0], q[1], q[2], q[3])).scale(placing.scale);
        if (placing.mover == null) {
            BlockPos p = placing.placement.translation();
            return new org.joml.Matrix4d().translate(p.getX(), p.getY(), p.getZ()).mul(own);
        }
        ClientSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null || !(container.getSubLevel(placing.mover) instanceof ClientSubLevel subLevel)) return null;
        Pose3dc pose = subLevel.logicalPose();
        BlockPos plotOrigin = MoverRegistry.plotOrigin(subLevel.getPlot());
        return new org.joml.Matrix4d().translate(pose.position().x(), pose.position().y(), pose.position().z())
            .rotate(new org.joml.Quaterniond(pose.orientation()))
            .translate(plotOrigin.getX() - pose.rotationPoint().x(), plotOrigin.getY() - pose.rotationPoint().y(), plotOrigin.getZ() - pose.rotationPoint().z())
            .mul(own);
    }

    /** Lights every vertex where its pose has it now, and uploads the bone buffers again; the skinned ones follow. */
    private static void light(ClientLevel level, Placing placing, BundleMap map, Built built) {
        Map<Long, Integer> cache = new HashMap<>();
        SurfaceOcclusion occlusion = placing.mover == null ? MapSurfaceRenderer.occlusionFor(placing.placement, map,
            (x, y, z) -> MapSurfaceRenderer.surfacePresentLive(placing.placement, x, y, z)) : null;
        boolean smooth = MapSurfaceRenderer.smoothLighting();
        org.joml.Matrix4d toWorld = toWorld(level, placing);
        built.close();
        for (var entry : built.rigid.entrySet()) {
            int at = entry.getKey().bone() * 12;
            PackedVertices vertices = entry.getValue();
            lightVertices(level, LevelRenderer.getLightColor(level, BlockPos.ZERO), toWorld, built.skin, at, vertices, vertices.vertices(), cache, occlusion, smooth);
            vertices.index();
            built.buffers.put(entry.getKey(), vertices.upload(MapSurfaceRenderer.neutralEntityId(), MapSurfaceRenderer.indexedMeshes()).buffer());
        }
        for (Blended part : built.blended.values()) {
            // Lit where the triangles' corners are posed now, by the first of them.
            float[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};
            lightVertices(level, LevelRenderer.getLightColor(level, BlockPos.ZERO), toWorld, identity, 0, part.vertices, part.count, cache, occlusion, smooth);
        }
        BlockPos middle = middle(level, placing, built);
        built.light = middle == null ? 0 : blockLight(level, middle);
        built.litFrame = frame;
        built.litAt = middleAt(level, placing, built);
        built.unlit = false;
        built.skinnedStale = true;
        skin(built);
    }

    /**
     * Starts a relight on a mesh worker against a copy of the world around the prop where it is
     * now; what is drawn stays until {@link #finishRelight}. A prop's first light builds its
     * buffers there; after that only the light bytes are sent again.
     */
    private static void relightOnWorker(ClientLevel level, Placing placing, Built built) {
        org.joml.Matrix4d toWorld = toWorld(level, placing);
        float[] b = built.box;
        // Its mover not here yet, or no pose: tried again next frame.
        if (toWorld == null || !(b[0] <= b[3])) return;
        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX, maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        org.joml.Vector3d p = new org.joml.Vector3d();
        for (int corner = 0; corner < 8; corner++) {
            toWorld.transformPosition(p.set((corner & 1) == 0 ? b[0] : b[3], (corner & 2) == 0 ? b[1] : b[4], (corner & 4) == 0 ? b[2] : b[5]));
            minX = Math.min(minX, p.x); minY = Math.min(minY, p.y); minZ = Math.min(minZ, p.z);
            maxX = Math.max(maxX, p.x); maxY = Math.max(maxY, p.y); maxZ = Math.max(maxZ, p.z);
        }
        WorldSnapshot world = WorldSnapshot.capture(level, (int) Math.floor(minX) - 2, (int) Math.floor(minY) - 2, (int) Math.floor(minZ) - 2,
            (int) Math.floor(maxX) + 2, (int) Math.floor(maxY) + 2, (int) Math.floor(maxZ) + 2);
        int fallback = LevelRenderer.getLightColor(level, BlockPos.ZERO);
        BlockPos middle = middle(level, placing, built);
        built.light = middle == null ? 0 : blockLight(level, middle);
        built.litFrame = frame;
        built.litAt = middleAt(level, placing, built);
        float[] skin = built.skin.clone();
        Map<Part, PackedVertices.LightPatch> patches = Map.copyOf(built.patches);
        // The skinned triangles move on the render thread: lit from a copy of where they are now.
        Map<Part, PackedVertices> posed = new HashMap<>();
        for (var entry : built.blended.entrySet()) posed.put(entry.getKey(), entry.getValue().vertices.copy());
        Map<Part, int[]> blendedLight = new java.util.concurrent.ConcurrentHashMap<>();
        built.blendedLight = blendedLight;
        built.relighting = MeshBuildPool.submit(() -> {
            Map<Long, Integer> cache = new HashMap<>();
            for (var entry : built.rigid.entrySet()) {
                PackedVertices vertices = entry.getValue();
                lightVertices(world, fallback, toWorld, skin, entry.getKey().bone() * 12, vertices, vertices.vertices(), cache, null, true);
                PackedVertices.LightPatch patch = patches.get(entry.getKey());
                if (patch != null) vertices.patchLight(patch);
                else vertices.index();
            }
            float[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};
            for (var entry : posed.entrySet()) {
                PackedVertices vertices = entry.getValue();
                lightVertices(world, fallback, toWorld, identity, 0, vertices, vertices.vertices(), cache, null, true);
                int[] lights = new int[vertices.vertices()];
                for (int i = 0; i < lights.length; i++) lights[i] = vertices.light(i);
                blendedLight.put(entry.getKey(), lights);
            }
            return null;
        });
    }

    /** Sends a finished worker relight: light bytes into the buffers there are, new buffers for the rest. */
    private static void finishRelight(Built built) {
        var job = built.relighting;
        built.relighting = null;
        try {
            job.join();
        } catch (RuntimeException exception) {
            built.litFrame = Long.MIN_VALUE;
            return;
        }
        for (var entry : built.rigid.entrySet()) {
            PackedVertices.LightPatch patch = built.patches.get(entry.getKey());
            VertexBuffer buffer = built.buffers.get(entry.getKey());
            if (patch != null && buffer != null) {
                patch.send(buffer);
                continue;
            }
            if (buffer != null) buffer.close();
            var uploaded = entry.getValue().upload(MapSurfaceRenderer.neutralEntityId(), MapSurfaceRenderer.indexedMeshes(), true);
            built.buffers.put(entry.getKey(), uploaded.buffer());
            if (uploaded.kept() != null) built.patches.put(entry.getKey(), uploaded.kept());
        }
        if (built.blendedLight != null) {
            built.blendedLight.forEach((part, lights) -> {
                Blended blended = built.blended.get(part);
                if (blended == null) return;
                for (int i = 0; i < Math.min(lights.length, blended.vertices.vertices()); i++) blended.vertices.setLight(i, lights[i]);
            });
            built.blendedLight = null;
        }
        built.unlit = false;
        built.skinnedStale = true;
    }

    /** Lights each vertex where the world has it now; {@code level} may be a copy of the world, on a worker. */
    private static void lightVertices(net.minecraft.world.level.BlockAndTintGetter level, int fallback, org.joml.Matrix4d toWorld, float[] skin,
                                      int at, PackedVertices vertices, int count, Map<Long, Integer> cache, SurfaceOcclusion occlusion, boolean smooth) {
        org.joml.Vector3d world = new org.joml.Vector3d(), normal = new org.joml.Vector3d();
        for (int i = 0; i < count; i++) {
            if (toWorld == null) { vertices.setLight(i, fallback); continue; }
            float x = vertices.x(i), y = vertices.y(i), z = vertices.z(i);
            float px = transform(skin, at, x, y, z, 0), py = transform(skin, at, x, y, z, 1), pz = transform(skin, at, x, y, z, 2);
            float nx = vertices.nx(i), ny = vertices.ny(i), nz = vertices.nz(i);
            float mx = skin[at] * nx + skin[at + 1] * ny + skin[at + 2] * nz, my = skin[at + 4] * nx + skin[at + 5] * ny + skin[at + 6] * nz,
                mz = skin[at + 8] * nx + skin[at + 9] * ny + skin[at + 10] * nz;
            toWorld.transformPosition(world.set(px, py, pz));
            // The scale is uniform: the normal turns as a direction does.
            toWorld.transformDirection(normal.set(mx, my, mz));
            double length = normal.length();
            float wx = length > 1e-6 ? (float) (normal.x / length) : 0, wy = length > 1e-6 ? (float) (normal.y / length) : 1,
                wz = length > 1e-6 ? (float) (normal.z / length) : 0;
            int light = occlusion == null ? LightSampler.smooth(level, world.x, world.y, world.z, wx, wy, wz, cache)
                : smooth ? LightSampler.smooth(level, world.x, world.y, world.z, wx, wy, wz, cache, occlusion)
                : LightSampler.sample(level, world.x, world.y, world.z, wx, wy, wz, cache);
            vertices.setLight(i, light);
        }
    }

    private static void draw(RenderLevelStageEvent event, boolean translucent) {
        if (!enabled || BUILT.isEmpty() || !BakedLighting.ready()) return;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        boolean shadowPass = IrisCompat.renderingShadowPass();
        Vec3 camera = event.getCamera().getPosition();
        BundleGeneration generation = Src2mc.bundles().active();
        boolean suppressDepthWrite = translucent && !MapSurfaceRenderer.translucentDepthWrite();
        boolean netherLighting = level.effects().constantAmbientLight();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        ClientSubLevelContainer container = SubLevelContainer.getContainer(level);
        long drawn = 0;
        for (Map.Entry<Key, Built> entry : BUILT.entrySet()) {
            Built built = entry.getValue();
            Placing placing = PLACING.get(entry.getKey());
            if (built.hidden || built.unlit || placing == null || built.buffers.isEmpty()) continue;
            Matrix4f base = new Matrix4f(event.getModelViewMatrix());
            Quaternionf turn = new Quaternionf();
            if (placing.mover == null) {
                base.translate((float) (placing.placement.translation().getX() + placing.translation[0] - camera.x),
                    (float) (placing.placement.translation().getY() + placing.translation[1] - camera.y),
                    (float) (placing.placement.translation().getZ() + placing.translation[2] - camera.z));
            } else {
                if (container == null || !(container.getSubLevel(placing.mover) instanceof ClientSubLevel subLevel)) continue;
                MoverRegistry.Resolved resolved = MoverRegistry.resolve(level, placing.mover);
                if (resolved != null && (MoverRegistry.state(true, resolved) & MoverRegistry.HIDDEN) != 0) continue;
                Pose3dc pose = subLevel.renderPose(partialTick);
                BlockPos plotOrigin = MoverRegistry.plotOrigin(subLevel.getPlot());
                turn.set(new Quaternionf(pose.orientation()));
                base.translate((float) (pose.position().x() - camera.x), (float) (pose.position().y() - camera.y), (float) (pose.position().z() - camera.z))
                    .rotate(turn)
                    .translate((float) (plotOrigin.getX() - pose.rotationPoint().x() + placing.translation[0]),
                        (float) (plotOrigin.getY() - pose.rotationPoint().y() + placing.translation[1]),
                        (float) (plotOrigin.getZ() - pose.rotationPoint().z() + placing.translation[2]));
            }
            Quaternionf own = new Quaternionf((float) placing.rotation[0], (float) placing.rotation[1], (float) placing.rotation[2], (float) placing.rotation[3]);
            base.rotate(own).scale((float) placing.scale);
            turn.mul(own);
            if (!shadowPass && MapSurfaceRenderer.frustumCulling() && !visible(event, base, built)) continue;
            // The ambient cube where the prop's middle is now, in its map.
            org.joml.Vector3d middle = middleAt(level, placing, built);
            BlockPos t = placing.placement.translation();
            float[] cube = middle == null ? BakedLighting.cube(built.lightTable, placing.translation[0], placing.translation[1], placing.translation[2])
                : BakedLighting.cube(built.lightTable, middle.x - t.getX(), middle.y - t.getY(), middle.z - t.getZ());
            for (Map.Entry<Part, VertexBuffer> buffer : built.buffers.entrySet()) {
                Part part = buffer.getKey();
                if ((part.renderClass() == BundleMaterial.RenderClass.TRANSLUCENT) != translucent) continue;
                Matrix4f modelView = base;
                Quaternionf rotation = turn;
                if (part.bone() >= 0) {
                    Matrix4f bone = boneMatrix(built.skin, part.bone() * 12);
                    modelView = new Matrix4f(base).mul(bone);
                    rotation = new Quaternionf(turn).mul(bone.get3x3(new Matrix3f()).getNormalizedRotation(new Quaternionf()));
                }
                // Normals stay model-space: the shading lights turn back instead.
                Quaternionf back = new Quaternionf(rotation).conjugate();
                RenderSystem.setShaderLights(back.transform(LIGHT_0, new Vector3f()), back.transform(netherLighting ? NETHER_LIGHT_1 : LIGHT_1, new Vector3f()));
                ResourceLocation texture = MapSurfaceRenderer.atlasPages().request(generation.sequence(), built.bundle, built.atlas, part.page(), frame)
                    .orElseGet(MapSurfaceRenderer.atlasPages()::placeholderTexture);
                RenderType type = translucent ? RenderType.entityTranslucent(texture)
                    : part.renderClass() == BundleMaterial.RenderClass.SOLID ? RenderType.entitySolid(texture) : RenderType.entityCutout(texture);
                type.setupRenderState();
                MapSurfaceRenderer.applyAtlasFilter(texture);
                if (suppressDepthWrite) RenderSystem.depthMask(false);
                buffer.getValue().bind();
                buffer.getValue().drawWithShader(modelView, event.getProjectionMatrix(),
                    BakedLighting.prepare(built.lightTable, part.renderClass(), cube, new Matrix3f().set(rotation)));
                type.clearRenderState();
                if (suppressDepthWrite) RenderSystem.depthMask(true);
                drawn++;
            }
        }
        VertexBuffer.unbind();
        RenderSystem.setShaderLights(LIGHT_0, netherLighting ? NETHER_LIGHT_1 : LIGHT_1);
        if (!shadowPass && !translucent) drawnLast = drawn;
    }

    /** A row-major 3x4 skinning transform as a 4x4 matrix. */
    private static Matrix4f boneMatrix(float[] s, int at) {
        return new Matrix4f(
            s[at], s[at + 4], s[at + 8], 0,
            s[at + 1], s[at + 5], s[at + 9], 0,
            s[at + 2], s[at + 6], s[at + 10], 0,
            s[at + 3], s[at + 7], s[at + 11], 1);
    }

    /** Whether the posed box, where {@code base} draws it, is in the view; the frustum is camera-relative, as the box is. */
    private static boolean visible(RenderLevelStageEvent event, Matrix4f base, Built built) {
        float[] b = built.box;
        if (!(b[0] <= b[3])) return true;
        Matrix4f view = new Matrix4f(event.getModelViewMatrix()).invert().mul(base);
        Vec3 camera = event.getCamera().getPosition();
        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX, maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        Vector3f p = new Vector3f();
        for (int corner = 0; corner < 8; corner++) {
            view.transformPosition((corner & 1) == 0 ? b[0] : b[3], (corner & 2) == 0 ? b[1] : b[4], (corner & 4) == 0 ? b[2] : b[5], p);
            minX = Math.min(minX, p.x); minY = Math.min(minY, p.y); minZ = Math.min(minZ, p.z);
            maxX = Math.max(maxX, p.x); maxY = Math.max(maxY, p.y); maxZ = Math.max(maxZ, p.z);
        }
        return event.getFrustum().isVisible(new AABB(minX, minY, minZ, maxX, maxY, maxZ).move(camera.x, camera.y, camera.z).inflate(0.5));
    }

    public static void clear() {
        BUILT.values().forEach(Built::close);
        BUILT.clear();
        GEOMETRY.clear();
        PLACING.clear();
    }
}
