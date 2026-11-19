package dev.theredja.src2mc.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.bundle.BundleGeneration;
import dev.theredja.src2mc.bundle.BundleMap;
import dev.theredja.src2mc.bundle.BundleMaterial;
import dev.theredja.src2mc.bundle.MoverTable;
import dev.theredja.src2mc.bundle.SurfaceTable;
import dev.theredja.src2mc.client.view.MonitorCameras;
import dev.theredja.src2mc.network.PlacementNetwork;
import dev.theredja.src2mc.world.MapPlacement;
import dev.theredja.src2mc.world.MoverRegistry;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

/**
 * Draws the faces of {@code _rt_Camera} materials (format.md section 6, {@code camera}): Half-Life 2's monitor
 * screens. Every such face samples the one shared camera target ({@link MonitorCameras}), as every Source monitor
 * samples {@code _rt_Camera} (baseclientrendertargets.cpp:37-46), with the material's shader, colour proxies and
 * scrolling {@code $texture2} ({@code src2mc:monitor}). Opaque materials draw with the solid surfaces, additive
 * ones ({@code SHADER_BLEND_ONE, SHADER_BLEND_ONE}, unlittwotexture_dx9.cpp:174-177) with the translucent ones.
 *
 * <p>Monitor movers ({@code func_monitor}) found in view are remembered; {@link MonitorCameras} draws a camera
 * only while one of its monitors was, as Source networks a {@code point_camera} only while a linked monitor is drawn
 * and in the player's PVS ({@code PointCameraSetupVisibility}, info_camera_link.cpp:146-184). A hidden monitor (a
 * disabled {@code func_brush}, EF_NODRAW) is neither drawn nor counted. Faces of map surfaces show the target too,
 * whatever it holds, and do not ask for a camera.
 */
@EventBusSubscriber(modid = Src2mc.MOD_ID, value = Dist.CLIENT)
public final class MonitorScreens {
    private MonitorScreens() {}

    /** Draws the monitor faces; off leaves them see-through, as before. */
    public static boolean enabled = true;
    /** Multiplies the picture by the tone map scale as Source's monitor shaders do; off to compare. */
    public static boolean exposure = true;

    /** A {@code func_monitor} of a placed map: its placement's anchor and its entity index. */
    public record MonitorKey(long anchor, int entity) {}

    /** The monitors drawn in view in the last frame. */
    private static volatile Set<MonitorKey> visible = Set.of();
    private static Set<MonitorKey> collecting = new HashSet<>();
    /** Faces drawn last frame, for {@link #status()}. */
    static long drawnFaces, drawnLast;

    private static ShaderInstance shader;
    private static VertexBuffer buffer;

    @EventBusSubscriber(modid = Src2mc.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Shaders {
        private Shaders() {}

        @SubscribeEvent
        public static void register(RegisterShadersEvent event) throws IOException {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                ResourceLocation.fromNamespaceAndPath(Src2mc.MOD_ID, "monitor"), DefaultVertexFormat.POSITION_TEX), loaded -> shader = loaded);
        }
    }

    /** One material's triangles: position (mover- or map-local blocks) and texture coordinate, five floats a vertex. */
    private record Group(int materialId, BundleMaterial material, float[] vertices) {
        int vertexCount() { return vertices.length / 5; }
    }

    /** The camera faces of a surface table, and their local bounds (min xyz, max xyz). */
    private record Faces(List<Group> groups, double[] bounds) {
        static final Faces NONE = new Faces(List.of(), null);
    }

    private record MoverFaces(BundleMap map, MoverTable.Mover mover, Faces faces) {}
    private record MapFaces(BundleMap map, Faces faces) {}

    private static final Map<UUID, MoverFaces> MOVERS = new HashMap<>();
    private static final Map<Long, MapFaces> MAPS = new HashMap<>();
    /** Whether a map has any camera material; keyed by identity (a bundle map's hashCode is expensive). */
    private static final Map<BundleMap, Boolean> HAS_CAMERA = new IdentityHashMap<>();
    private static final Map<String, Integer> TEXTURE2 = new HashMap<>();
    private static long generationSequence = -1;

    /** The monitors drawn in view last frame. */
    public static Set<MonitorKey> visibleMonitors() { return visible; }

    public static String status() {
        return "monitor screens " + (enabled ? "on" : "off") + (exposure ? "" : " (no exposure)") + ", " + drawnLast + " faces drawn last frame, " + visible.size()
            + " monitors in view";
    }

    @SubscribeEvent
    static void onStage(RenderLevelStageEvent event) {
        boolean opaque = event.getStage() == MapSurfaceRenderer.opaqueStage();
        boolean translucent = event.getStage() == RenderLevelStageEvent.Stage.AFTER_PARTICLES;
        if (!opaque && !translucent) return;
        if (IrisCompat.renderingShadowPass() || dev.theredja.src2mc.client.view.SecondaryView.active()) return;
        if (opaque) {
            collecting = new HashSet<>();
            drawnLast = drawnFaces;
            drawnFaces = 0;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (enabled && shader != null && level != null) {
            BundleGeneration generation = Src2mc.bundles().active();
            if (generation.sequence() != generationSequence) clear(generation.sequence());
            draw(event, level, generation, translucent);
        }
        // Read before the next frame, which draws the cameras these monitors show.
        if (opaque) visible = Set.copyOf(collecting);
    }

    private static void clear(long sequence) {
        MOVERS.clear();
        MAPS.clear();
        HAS_CAMERA.clear();
        for (int texture : TEXTURE2.values()) if (texture > 0) GlStateManager._deleteTexture(texture);
        TEXTURE2.clear();
        generationSequence = sequence;
    }

    private static boolean hasCamera(BundleMap map) {
        return HAS_CAMERA.computeIfAbsent(map, m -> m.materials().stream().anyMatch(material -> material.camera() != null));
    }

    /** The camera faces of {@code surfaces}, by material, fanned into triangles. */
    private static Faces faces(SurfaceTable surfaces, List<BundleMaterial> materials) {
        if (surfaces == null) return Faces.NONE;
        Map<Integer, List<Float>> byMaterial = new TreeMap<>();
        double[] bounds = null;
        for (var section : surfaces.sections().entrySet()) {
            int baseX = section.getKey().x() << 4, baseY = section.getKey().y() << 4, baseZ = section.getKey().z() << 4;
            for (SurfaceTable.Face face : section.getValue()) {
                int id = face.materialId();
                if (id < 0 || id >= materials.size()) continue;
                BundleMaterial material = materials.get(id);
                BundleMaterial.Camera camera = material.camera();
                if (camera == null || face.uvRegionId() < 0 || face.uvRegionId() >= surfaces.uvRegions().size()) continue;
                int local = face.localCell();
                int x = baseX + (local & 15), y = baseY + (local >> 8 & 15), z = baseZ + (local >> 4 & 15);
                double[] uv = surfaces.uvRegions().get(face.uvRegionId()).values();
                int count = face.vertexCount();
                double[][] corners = new double[count][];
                for (int i = 0; i < count; i++) {
                    double px = x + face.coordinate(i, 0), py = y + face.coordinate(i, 1), pz = z + face.coordinate(i, 2);
                    // The surface table's texture coordinates are texels; Source divides them by the material's
                    // size, so the camera picture repeats every size texels.
                    corners[i] = new double[] {px, py, pz, (px * uv[0] + py * uv[1] + pz * uv[2] + uv[3]) / camera.sizeS(),
                        (px * uv[4] + py * uv[5] + pz * uv[6] + uv[7]) / camera.sizeT()};
                    if (bounds == null) bounds = new double[] {px, py, pz, px, py, pz};
                    bounds[0] = Math.min(bounds[0], px); bounds[1] = Math.min(bounds[1], py); bounds[2] = Math.min(bounds[2], pz);
                    bounds[3] = Math.max(bounds[3], px); bounds[4] = Math.max(bounds[4], py); bounds[5] = Math.max(bounds[5], pz);
                }
                List<Float> out = byMaterial.computeIfAbsent(id, ignored -> new ArrayList<>());
                for (int i = 1; i + 1 < count; i++) {
                    add(out, corners[0]); add(out, corners[i]); add(out, corners[i + 1]);
                    if (material.doubleSided()) { add(out, corners[0]); add(out, corners[i + 1]); add(out, corners[i]); }
                }
            }
        }
        if (byMaterial.isEmpty()) return Faces.NONE;
        List<Group> groups = new ArrayList<>();
        for (var entry : byMaterial.entrySet()) {
            float[] vertices = new float[entry.getValue().size()];
            for (int i = 0; i < vertices.length; i++) vertices[i] = entry.getValue().get(i);
            groups.add(new Group(entry.getKey(), materials.get(entry.getKey()), vertices));
        }
        return new Faces(List.copyOf(groups), bounds);
    }

    private static void add(List<Float> out, double[] vertex) {
        for (double value : vertex) out.add((float) value);
    }

    private static void draw(RenderLevelStageEvent event, ClientLevel level, BundleGeneration generation, boolean translucent) {
        Vec3 camera = event.getCamera().getPosition();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        // gpGlobals->curtime: the proxies' clock, in seconds.
        double time = (level.getGameTime() + partialTick) / 20.0;
        List<Runnable> draws = new ArrayList<>();
        // The map surfaces' camera faces, map-local, drawn at their placement.
        ResourceLocation dimension = level.dimension().location();
        Set<Long> placed = new HashSet<>();
        for (MapPlacement placement : PlacementNetwork.clientIndex(dimension).view()) {
            long anchor = placement.anchorWorld().asLong();
            placed.add(anchor);
            BundleMap map = generation.findMap(placement.campaignId(), placement.mapId()).orElse(null);
            if (map == null || !hasCamera(map)) continue;
            MapFaces cached = MAPS.get(anchor);
            if (cached == null || cached.map != map) MAPS.put(anchor, cached = new MapFaces(map, faces(map.surfaces(), map.materials())));
            Faces faces = cached.faces;
            if (faces.groups.isEmpty()) continue;
            BlockPos t = placement.translation();
            AABB box = new AABB(faces.bounds[0] + t.getX(), faces.bounds[1] + t.getY(), faces.bounds[2] + t.getZ(),
                faces.bounds[3] + t.getX(), faces.bounds[4] + t.getY(), faces.bounds[5] + t.getZ()).inflate(0.01);
            if (MapSurfaceRenderer.frustumCulling() && !event.getFrustum().isVisible(box)) continue;
            Matrix4f modelView = new Matrix4f(event.getModelViewMatrix())
                .translate((float) (t.getX() - camera.x), (float) (t.getY() - camera.y), (float) (t.getZ() - camera.z));
            draws.add(() -> drawGroups(faces, modelView, event.getProjectionMatrix(), translucent, time));
        }
        MAPS.keySet().retainAll(placed);
        // The movers' camera faces, mover-local, drawn at the sub-level's pose as MoverRenderer draws movers.
        ClientSubLevelContainer container = SubLevelContainer.getContainer(level);
        Set<UUID> seen = new HashSet<>();
        if (container != null) {
            for (MoverRegistry.Instance instance : MoverRegistry.instances(true)) {
                if (!(container.getSubLevel(instance.subLevel()) instanceof ClientSubLevel client)) continue;
                MoverRegistry.Resolved resolved = MoverRegistry.resolve(level, instance.subLevel());
                if (resolved == null || !hasCamera(resolved.map())) continue;
                seen.add(instance.subLevel());
                MoverFaces cached = MOVERS.get(instance.subLevel());
                if (cached == null || cached.map != resolved.map() || cached.mover != resolved.mover()) {
                    MOVERS.put(instance.subLevel(), cached = new MoverFaces(resolved.map(), resolved.mover(),
                        faces(resolved.mover().surfaces(), resolved.map().materials())));
                }
                Faces faces = cached.faces;
                if (faces.groups.isEmpty()) continue;
                // A disabled func_brush is EF_NODRAW: not drawn, and its camera link skipped.
                if ((MoverRegistry.state(true, resolved) & MoverRegistry.HIDDEN) != 0) continue;
                Pose3dc pose = client.renderPose(partialTick);
                BlockPos plotOrigin = MoverRegistry.plotOrigin(client.getPlot());
                AABB box = worldBounds(pose, plotOrigin, faces.bounds);
                if (MapSurfaceRenderer.frustumCulling() && !event.getFrustum().isVisible(box)) continue;
                if (!translucent && inPvs(resolved.placement(), box)) {
                    collecting.add(new MonitorKey(resolved.placement().anchorWorld().asLong(), instance.entity()));
                }
                Matrix4f modelView = new Matrix4f(event.getModelViewMatrix())
                    .translate((float) (pose.position().x() - camera.x), (float) (pose.position().y() - camera.y), (float) (pose.position().z() - camera.z))
                    .rotate(new Quaternionf(pose.orientation()))
                    .translate((float) (plotOrigin.getX() - pose.rotationPoint().x()), (float) (plotOrigin.getY() - pose.rotationPoint().y()),
                        (float) (plotOrigin.getZ() - pose.rotationPoint().z()));
                draws.add(() -> drawGroups(faces, modelView, event.getProjectionMatrix(), translucent, time));
            }
        }
        if (!translucent) MOVERS.keySet().retainAll(seen);
        if (draws.isEmpty()) return;
        int previous0 = RenderSystem.getShaderTexture(0), previous1 = RenderSystem.getShaderTexture(1);
        RenderSystem.setShader(() -> shader);
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        if (translucent) {
            RenderSystem.enableBlend();
            RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE);
            RenderSystem.depthMask(false);
        } else {
            RenderSystem.disableBlend();
            RenderSystem.depthMask(true);
        }
        shader.safeGetUniform("MonitorExposure").set(exposure ? BakedLighting.currentExposure() : 1f);
        shader.safeGetUniform("MonitorSrgb").set(SurfaceEffects.srgbCurve ? 1f : 0f);
        dev.theredja.src2mc.client.look.LookClient.applyFog(shader);
        RenderSystem.setShaderTexture(0, MonitorCameras.texture());
        try {
            for (Runnable draw : draws) draw.run();
        } finally {
            VertexBuffer.unbind();
            RenderSystem.setShaderTexture(0, previous0);
            RenderSystem.setShaderTexture(1, previous1);
            RenderSystem.depthMask(true);
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
        }
    }

    /**
     * Whether the player's PVS cluster sees the monitor ({@code IsInPVS}, info_camera_link.cpp:170): any of its box's
     * corners or centre in a visible cluster; open when the player's cluster or the monitor's is unknown.
     */
    private static boolean inPvs(MapPlacement placement, AABB box) {
        byte[] row = CameraVisibility.row();
        if (row == null || !MapSurfaceRenderer.pvsCulling() || !placement.equals(CameraVisibility.placement())) return true;
        BlockPos t = placement.translation();
        AABB local = box.move(-t.getX(), -t.getY(), -t.getZ()).deflate(0.05);
        boolean known = false;
        for (int corner = 0; corner < 9; corner++) {
            double x = corner == 8 ? local.getCenter().x : (corner & 1) == 0 ? local.minX : local.maxX;
            double y = corner == 8 ? local.getCenter().y : (corner & 2) == 0 ? local.minY : local.maxY;
            double z = corner == 8 ? local.getCenter().z : (corner & 4) == 0 ? local.minZ : local.maxZ;
            int cluster = CameraVisibility.table().clusterAt(x, y, z);
            if (cluster < 0) continue;
            known = true;
            if (CameraVisibility.table().visible(row, new short[] {(short) cluster})) return true;
        }
        return !known;
    }

    private static void drawGroups(Faces faces, Matrix4f modelView, Matrix4f projection, boolean translucent, double time) {
        for (Group group : faces.groups) {
            BundleMaterial.Camera camera = group.material.camera();
            if (camera.additive() != translucent) continue;
            setMaterial(camera, time);
            int vertexCount = group.vertexCount();
            int stride = DefaultVertexFormat.POSITION_TEX.getVertexSize();
            try (ByteBufferBuilder bytes = new ByteBufferBuilder(vertexCount * stride)) {
                long base = bytes.reserve(vertexCount * stride);
                float[] v = group.vertices;
                for (int i = 0; i < vertexCount; i++) {
                    long at = base + (long) i * stride;
                    MemoryUtil.memPutFloat(at, v[i * 5]);
                    MemoryUtil.memPutFloat(at + 4, v[i * 5 + 1]);
                    MemoryUtil.memPutFloat(at + 8, v[i * 5 + 2]);
                    MemoryUtil.memPutFloat(at + 12, v[i * 5 + 3]);
                    MemoryUtil.memPutFloat(at + 16, v[i * 5 + 4]);
                }
                MeshData mesh = new MeshData(bytes.build(), new MeshData.DrawState(DefaultVertexFormat.POSITION_TEX, vertexCount, vertexCount,
                    VertexFormat.Mode.TRIANGLES, VertexFormat.IndexType.least(vertexCount)));
                if (buffer == null) buffer = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
                buffer.bind();
                buffer.upload(mesh);
                buffer.drawWithShader(modelView, projection, shader);
                drawnFaces += vertexCount / 3;
            }
        }
    }

    /** One material's uniforms at time {@code t} seconds, as its shader's dynamic state sets them. */
    private static void setMaterial(BundleMaterial.Camera camera, double t) {
        float[] color = camera.modulation(t);
        boolean monitor = camera.shader() == BundleMaterial.CameraShader.MONITOR_SCREEN;
        if (!monitor) {
            // SetModulationPixelShaderDynamicState_LinearColorSpace (BaseVSShader.cpp:652-661): a channel above 1
            // as it is, else GammaToLinear.
            for (int i = 0; i < 3; i++) color[i] = color[i] > 1 ? color[i] : gammaToLinear(color[i]);
        }
        shader.safeGetUniform("MonitorShader").set(switch (camera.shader()) {
            case UNLIT_GENERIC -> 0f;
            case UNLIT_TWO_TEXTURE -> 1f;
            case MONITOR_SCREEN -> 2f;
        });
        shader.safeGetUniform("MonitorColor").set(color[0], color[1], color[2]);
        shader.safeGetUniform("MonitorAdditive").set(camera.additive() ? 1f : 0f);
        float[] tint = camera.tint();
        shader.safeGetUniform("MonitorTint").set(tint[0], tint[1], tint[2]);
        shader.safeGetUniform("MonitorContrast").set(camera.contrast());
        shader.safeGetUniform("MonitorSaturation").set(camera.saturation());
        int texture2 = camera.texture2() == null ? 0 : texture2(camera.texture2());
        shader.safeGetUniform("MonitorTexture2").set(texture2 > 0 ? 1f : 0f);
        BundleMaterial.Texture2Transform transform = camera.transform();
        if (transform == null) {
            shader.safeGetUniform("MonitorTransform").set(1f, 1f, 0f, 0f);
        } else {
            // The offset wrapped into 0..1 on the CPU, as TextureScroll does it, so the GPU keeps its precision.
            double s = transform.offsetS() + transform.velocityS() * t, tt = transform.offsetT() + transform.velocityT() * t;
            shader.safeGetUniform("MonitorTransform").set(transform.scale(), transform.scale(), (float) (s - Math.floor(s)),
                (float) (tt - Math.floor(tt)));
        }
        RenderSystem.setShaderTexture(1, Math.max(texture2, 0));
    }

    /**
     * mathlib's {@code GammaToLinear} (mathlib/color_conversion.cpp:274-293): 0 below 0, 1 from 0.95 up, else the
     * 256-entry table of {@code pow(i / 255, 2.2)} at {@code round(gamma * 255)}.
     */
    static float gammaToLinear(float gamma) {
        if (gamma < 0) return 0;
        if (gamma >= 0.95f) return 1;
        int index = Math.round(gamma * 255.0f);
        return (float) Math.pow(index / 255.0f, 2.2f);
    }

    /** The GL texture of a {@code $texture2}, uploaded once from the map's detail images: repeating, mipmapped. */
    private static int texture2(String id) {
        Integer cached = TEXTURE2.get(id);
        if (cached != null) return cached;
        int texture = -1;
        dev.theredja.src2mc.bundle.DetailImages.Image image = null;
        for (var located : Src2mc.bundles().active().bundles()) {
            for (BundleMap map : located.maps()) {
                if (map.details() != null && map.details().images().containsKey(id)) image = map.details().images().get(id);
                if (image != null) break;
            }
            if (image != null) break;
        }
        if (image != null) {
            ByteBuffer pixels = MemoryUtil.memAlloc(image.rgba().length);
            try {
                pixels.put(image.rgba()).flip();
                texture = GlStateManager._genTexture();
                GlStateManager._bindTexture(texture);
                int unpack = UnpackState.begin(4);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, image.width(), image.height(), 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
                UnpackState.end(unpack);
                GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
                GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
                GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
                GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
                GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
                GlStateManager._bindTexture(0);
            } finally {
                MemoryUtil.memFree(pixels);
            }
        }
        TEXTURE2.put(id, texture);
        return texture;
    }

    /** The world box around local bounds at a mover's pose. */
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
}
