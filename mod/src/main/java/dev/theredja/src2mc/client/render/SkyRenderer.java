package dev.theredja.src2mc.client.render;

import static net.minecraft.commands.Commands.literal;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.bundle.BundleGeneration;
import dev.theredja.src2mc.bundle.BundleManifest;
import dev.theredja.src2mc.bundle.BundleMap;
import dev.theredja.src2mc.bundle.BundleMaterial;
import dev.theredja.src2mc.bundle.SkyTable;
import dev.theredja.src2mc.bundle.SkyboxTable;
import dev.theredja.src2mc.network.PlacementNetwork;
import dev.theredja.src2mc.world.MapPlacement;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipFile;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * Draws each placed map's sky faces (format.md section 20) showing Source's 2D skybox: every
 * pixel samples the side of the skybox its view direction points at, as the engine's box of six
 * textures around the eye would show it, so Minecraft's sky and terrain no longer show through
 * the map's sky. Where it shows is decided per pixel from three depth snapshots (see
 * {@link #BEFORE_MAP}); never in a shadow pass.
 */
@EventBusSubscriber(modid = Src2mc.MOD_ID, value = Dist.CLIENT)
public final class SkyRenderer {
    private static ShaderInstance shader, roomShader;
    private static boolean enabled = true, roomEnabled = true;
    /** Each map's 3D skybox, cut on a worker, then uploaded. Keyed by identity: see BundleMap#hashCode. */
    private static final Map<BundleMap, RoomSlot> ROOMS = new java.util.IdentityHashMap<>();
    private static RoomTarget roomTarget;
    private static VertexBuffer cube;
    private static int drawnRooms;
    private static String roomState = "none";

    private static final class RoomSlot {
        final CompletableFuture<SkyboxScene.Prepared> prepared;
        SkyboxScene scene;
        boolean failed;
        RoomSlot(CompletableFuture<SkyboxScene.Prepared> prepared) { this.prepared = prepared; }
    }
    private static long generationSequence = -1;
    /** One buffer per placement with a sky, built on first sight. */
    private static final Map<MapPlacement, Faces> FACES = new HashMap<>();
    /** Side textures by content ID, shared by every map that uses them; null side IDs draw {@link #black}. */
    private static final Map<String, Side> SIDES = new HashMap<>();
    private static SideTexture black;
    private static int drawnMaps, drawnTriangles;

    private SkyRenderer() {}

    private record Faces(VertexBuffer buffer, int triangles, SkyTable sky, BundleManifest bundle) {}

    private static final class Side {
        final CompletableFuture<NativeImage> decode;
        SideTexture texture;
        boolean failed;
        Side(CompletableFuture<NativeImage> decode) { this.decode = decode; }
    }

    /** Registers the sky's core shader, on the mod bus. */
    @EventBusSubscriber(modid = Src2mc.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Shaders {
        private Shaders() {}

        @SubscribeEvent
        public static void register(RegisterShadersEvent event) throws IOException {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                ResourceLocation.fromNamespaceAndPath(Src2mc.MOD_ID, "sky"), DefaultVertexFormat.POSITION), loaded -> shader = loaded);
            // Only the names bind locations; SkyboxScene sets each attribute's layout itself.
            VertexFormat room = VertexFormat.builder()
                .add("Position", com.mojang.blaze3d.vertex.VertexFormatElement.POSITION)
                .add("UV0", com.mojang.blaze3d.vertex.VertexFormatElement.UV0)
                .add("UV1", com.mojang.blaze3d.vertex.VertexFormatElement.UV1)
                .add("Normal", com.mojang.blaze3d.vertex.VertexFormatElement.NORMAL)
                .build();
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                ResourceLocation.fromNamespaceAndPath(Src2mc.MOD_ID, "skybox"), room), loaded -> roomShader = loaded);
        }
    }

    /**
     * Depth before the map is drawn (Minecraft's own terrain), after the map's opaque geometry, and
     * once everything opaque and Minecraft's translucent terrain and particles are in. The sky shows
     * where its face is in front of Minecraft's terrain, the map drew nothing over that terrain,
     * and nothing drawn since is in front of the face: Source draws the sky behind everything of
     * the map, so a tower standing out through a sky brush stays whole, while the world the map was
     * placed in stays hidden behind the sky.
     */
    private static final DepthSnapshot BEFORE_MAP = new DepthSnapshot(), AFTER_MAP = new DepthSnapshot(), BEFORE_GLASS = new DepthSnapshot();
    /** How many of the three snapshots this frame has taken, in order; the sky is drawn only after all three. */
    private static int snapshots;

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void beforeMap(RenderLevelStageEvent event) {
        if (event.getStage() != MapSurfaceRenderer.opaqueStage() || IrisCompat.renderingShadowPass()) return;
        snapshots = 0;
        if (prepare() && copy(BEFORE_MAP)) snapshots = 1;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void afterMap(RenderLevelStageEvent event) {
        if (event.getStage() != MapSurfaceRenderer.opaqueStage() || IrisCompat.renderingShadowPass()) return;
        if (snapshots == 1 && copy(AFTER_MAP)) snapshots = 2;
    }

    /**
     * Before the map's translucent surfaces: without a shader pack the sky is drawn now, colour and
     * depth, so a window in front of it is drawn over it and clouds behind it are hidden.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void beforeGlass(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || IrisCompat.renderingShadowPass()) return;
        if (snapshots != 2 || !copy(BEFORE_GLASS)) return;
        snapshots = 3;
        if (!IrisCompat.shaderPackInUse()) draw(event, true);
    }

    /**
     * With a shader pack, once Iris has finished the frame: Iris draws no core shader it does not
     * know while it renders the world, and the pack would light and fog the sky as terrain. Drawn
     * over the finished image, a map window in front of the sky shows the sky without the glass.
     */
    @SubscribeEvent
    public static void afterLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        if (snapshots == 3 && IrisCompat.shaderPackInUse()) {
            Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
            draw(event, false);
        }
        if (snapshots == 3) costFrames++;
        snapshots = 0;
    }

    /** CPU nanoseconds spent on the snapshots, the room and the faces since the last status, over {@link #costFrames}. */
    private static long snapshotNanos, roomNanos, faceNanos, costFrames;

    private static boolean copy(DepthSnapshot snapshot) {
        long start = System.nanoTime();
        GpuTimer.begin(GpuTimer.Phase.SKY_SNAPSHOTS);
        try {
            return snapshot.copy(Minecraft.getInstance().getMainRenderTarget());
        } finally {
            GpuTimer.end(GpuTimer.Phase.SKY_SNAPSHOTS);
            snapshotNanos += System.nanoTime() - start;
        }
    }

    /** Clears what a new bundle generation or level makes stale; whether there is a sky to draw. */
    private static boolean prepare() {
        drawnMaps = 0;
        drawnTriangles = 0;
        drawnRooms = 0;
        ClientLevel level = Minecraft.getInstance().level;
        BundleGeneration generation = Src2mc.bundles().active();
        if (level == null || generation.sequence() != generationSequence) {
            clear();
            generationSequence = generation.sequence();
            if (level == null) return false;
        }
        if (!enabled || shader == null) return false;
        for (MapPlacement placement : PlacementNetwork.clientIndex(level.dimension().location()).view()) {
            var located = generation.findLocatedMap(placement.campaignId(), placement.mapId()).orElse(null);
            if (located != null && located.map().sky() != null) return true;
        }
        return false;
    }

    /** Draws every placed map's sky faces where the snapshots let the sky show. */
    private static void draw(RenderLevelStageEvent event, boolean writeDepth) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        BundleGeneration generation = Src2mc.bundles().active();
        Vec3 camera = event.getCamera().getPosition();
        Set<MapPlacement> seen = new HashSet<>();
        boolean stateSet = false;
        // The sides take texture slots 0 to 5, the snapshots 6 to 8 and the room 9; whatever drew
        // before gets them back.
        int[] previous = new int[ROOM_SLOT + 1];
        for (int slot = 0; slot < previous.length; slot++) previous[slot] = RenderSystem.getShaderTexture(slot);
        for (MapPlacement placement : PlacementNetwork.clientIndex(level.dimension().location()).view()) {
            var located = generation.findLocatedMap(placement.campaignId(), placement.mapId()).orElse(null);
            if (located == null || located.map().sky() == null) continue;
            seen.add(placement);
            Faces faces = FACES.computeIfAbsent(placement, ignored -> build(located.map().sky(), located.bundle()));
            if (faces == null || faces.triangles == 0) continue;
            int[] textures = sideTextures(faces);
            if (textures == null) continue;
            if (!stateSet) {
                RenderSystem.disableDepthTest();
                RenderSystem.depthMask(writeDepth);
                // With the test off nothing is written either, unless the test always passes.
                if (writeDepth) {
                    RenderSystem.enableDepthTest();
                    RenderSystem.depthFunc(519); // GL_ALWAYS
                }
                RenderSystem.enableCull();
                RenderSystem.disableBlend();
                RenderSystem.setShaderTexture(SNAPSHOT_SLOT, BEFORE_MAP.texture());
                RenderSystem.setShaderTexture(SNAPSHOT_SLOT + 1, AFTER_MAP.texture());
                RenderSystem.setShaderTexture(SNAPSHOT_SLOT + 2, BEFORE_GLASS.texture());
                stateSet = true;
            }
            for (int side = 0; side < textures.length; side++) RenderSystem.setShaderTexture(side, textures[side]);
            long roomStart = System.nanoTime();
            GpuTimer.begin(GpuTimer.Phase.SKY_ROOM);
            boolean room;
            try {
                room = drawRoom(event, generation, located.bundle(), located.map(), placement, camera);
            } finally {
                GpuTimer.end(GpuTimer.Phase.SKY_ROOM);
                roomNanos += System.nanoTime() - roomStart;
            }
            if (room) {
                RenderSystem.setShaderTexture(ROOM_SLOT, roomTarget.color);
                // The room pass left its own state; the faces' is set again.
                RenderSystem.disableBlend();
                RenderSystem.enableCull();
                RenderSystem.depthMask(writeDepth);
                if (writeDepth) {
                    RenderSystem.enableDepthTest();
                    RenderSystem.depthFunc(519);
                } else {
                    RenderSystem.disableDepthTest();
                }
            }
            shader.safeGetUniform("Mode").set(room ? 2.0f : 0.0f);
            var translation = placement.translation();
            shader.safeGetUniform("Offset").set((float) (translation.getX() - camera.x), (float) (translation.getY() - camera.y),
                (float) (translation.getZ() - camera.z));
            long faceStart = System.nanoTime();
            GpuTimer.begin(GpuTimer.Phase.SKY_FACES);
            faces.buffer.bind();
            faces.buffer.drawWithShader(new Matrix4f(event.getModelViewMatrix()), event.getProjectionMatrix(), shader);
            GpuTimer.end(GpuTimer.Phase.SKY_FACES);
            faceNanos += System.nanoTime() - faceStart;
            drawnMaps++;
            drawnTriangles += faces.triangles;
        }
        if (stateSet) {
            VertexBuffer.unbind();
            for (int slot = 0; slot < previous.length; slot++) RenderSystem.setShaderTexture(slot, previous[slot]);
            RenderSystem.depthFunc(515); // GL_LEQUAL, Minecraft's default
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
        }
        if (FACES.size() > seen.size()) {
            FACES.entrySet().removeIf(entry -> {
                if (seen.contains(entry.getKey())) return false;
                if (entry.getValue() != null && entry.getValue().buffer != null) entry.getValue().buffer.close();
                return true;
            });
        }
    }

    private static final int SNAPSHOT_SLOT = 6, ROOM_SLOT = 9;

    /**
     * Renders the map's 3D skybox room behind its 2D skybox into the room target, as
     * {@code CSkyboxView::DrawInternal} does: the eye at {@code sky_camera + eye / scale}, the
     * player's view, the room's own fog with its distances over {@code scale}. Only from a cluster
     * whose leaves see the 3D sky ({@code LEAF_FLAGS_SKY}). False when there is nothing to show
     * (no room, not seen from here, or not uploaded yet).
     */
    private static boolean drawRoom(RenderLevelStageEvent event, BundleGeneration generation, BundleManifest bundle, BundleMap map,
                                    MapPlacement placement, Vec3 camera) {
        SkyboxTable table = map.skybox();
        if (table == null || !roomEnabled || roomShader == null) { roomState = table == null ? "none" : "off"; return false; }
        var translation = placement.translation();
        double localX = camera.x - translation.getX(), localY = camera.y - translation.getY(), localZ = camera.z - translation.getZ();
        int cluster = map.pvs() == null ? -1 : map.pvs().clusterAt(localX, localY, localZ);
        if (!table.visibleFrom(cluster)) { roomState = "not seen from cluster " + cluster; return false; }
        RoomSlot slot = ROOMS.computeIfAbsent(map, ignored -> new RoomSlot(SkyboxScene.prepare(map)));
        if (slot.scene == null && !slot.failed && slot.prepared.isDone()) {
            try {
                slot.scene = SkyboxScene.upload(slot.prepared.join());
            } catch (RuntimeException e) {
                Src2mc.LOGGER.warn("3D skybox of {} could not be built", map.mapId(), e);
                slot.failed = true;
            }
        }
        if (slot.scene == null) { roomState = slot.failed ? "failed" : "building"; return false; }
        var main = Minecraft.getInstance().getMainRenderTarget();
        if (roomTarget == null) roomTarget = new RoomTarget();
        int previousRead = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER_BINDING);
        int previousDraw = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int[] viewport = new int[4];
        org.lwjgl.opengl.GL11.glGetIntegerv(org.lwjgl.opengl.GL11.GL_VIEWPORT, viewport);
        try {
            roomTarget.bind(main.width, main.height);
            RenderSystem.viewport(0, 0, main.width, main.height);
            // A clear writes only what the masks allow: with a shader pack the faces are drawn
            // without depth writes, and the room's depth then kept every earlier frame's nearest
            // surface, which hid the room wherever the view had moved.
            RenderSystem.depthMask(true);
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.clearColor(0, 0, 0, 1);
            RenderSystem.clear(org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT | org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
            Matrix4f modelView = new Matrix4f(event.getModelViewMatrix());
            // The 2D skybox first, around the eye, as the skybox view draws it before the room.
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
            RenderSystem.disableBlend();
            shader.safeGetUniform("Mode").set(1.0f);
            shader.safeGetUniform("Offset").set(0f, 0f, 0f);
            cube().bind();
            cube().drawWithShader(modelView, event.getProjectionMatrix(), shader);
            VertexBuffer.unbind();
            // Then the room.
            double[] origin = table.worldOrigin();
            float scale = table.scale();
            roomShader.safeGetUniform("Offset").set((float) (-(localX - origin[0]) / scale), (float) (-(localY - origin[1]) / scale),
                (float) (-(localZ - origin[2]) / scale));
            roomShader.safeGetUniform("Exposure").set(BakedLighting.exposure());
            SkyboxTable.Fog fog = table.fog();
            if (fog != null) {
                // Source units to blocks, and the skybox view's division by the scale.
                float units = 32f * scale;
                roomShader.safeGetUniform("SkyFogRange").set(fog.start() / units, fog.end() / units, fog.maxDensity());
                roomShader.safeGetUniform("SkyFogColor").set(linear(fog.red()), linear(fog.green()), linear(fog.blue()));
            } else {
                roomShader.safeGetUniform("SkyFogRange").set(0f, 0f, 0f);
            }
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(515);
            RenderSystem.setShaderTexture(1, slot.scene.lightmap);
            for (SkyboxScene.Draw draw : slot.scene.draws) {
                SkyboxScene.Key key = draw.key();
                var texture = MapSurfaceRenderer.atlasPages().request(generation.sequence(), bundle, map.atlas(), key.page(), frame++);
                if (texture.isEmpty()) continue;
                var loaded = Minecraft.getInstance().getTextureManager().getTexture(texture.get());
                loaded.setFilter(true, true);
                RenderSystem.setShaderTexture(0, loaded.getId());
                boolean translucent = key.renderClass() == BundleMaterial.RenderClass.TRANSLUCENT;
                if (translucent) {
                    RenderSystem.enableBlend();
                    RenderSystem.defaultBlendFunc();
                    RenderSystem.depthMask(false);
                } else {
                    RenderSystem.disableBlend();
                    RenderSystem.depthMask(true);
                }
                if (key.doubleSided()) RenderSystem.disableCull(); else RenderSystem.enableCull();
                int tint = key.tint();
                roomShader.safeGetUniform("Tint").set(linear(tint >> 16 & 255), linear(tint >> 8 & 255), linear(tint & 255));
                roomShader.safeGetUniform("VertexLit").set(key.lighting() == SkyboxTable.VERTEX_LIGHT ? 1f : 0f);
                roomShader.safeGetUniform("Cutout").set(key.renderClass() == BundleMaterial.RenderClass.CUTOUT ? 1f : 0f);
                roomShader.setDefaultUniforms(VertexFormat.Mode.TRIANGLES, modelView, event.getProjectionMatrix(), Minecraft.getInstance().getWindow());
                roomShader.apply();
                org.lwjgl.opengl.GL30.glBindVertexArray(draw.vao());
                org.lwjgl.opengl.GL11.glDrawArrays(org.lwjgl.opengl.GL11.GL_TRIANGLES, 0, draw.vertices());
                roomShader.clear();
            }
            org.lwjgl.opengl.GL30.glBindVertexArray(0);
            com.mojang.blaze3d.vertex.BufferUploader.invalidate();
            RenderSystem.disableBlend();
            RenderSystem.depthMask(true);
            drawnRooms++;
            roomState = "drawn, " + slot.scene.triangles + " triangles, cluster " + cluster;
            return true;
        } finally {
            org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, previousRead);
            org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER, previousDraw);
            RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        }
    }

    private static long frame;

    private static float linear(int channel) {
        return (float) Math.pow(channel / 255.0, 2.2);
    }

    /** A cube around the eye, for the 2D skybox behind the room. */
    private static VertexBuffer cube() {
        if (cube == null) {
            try (var bytes = new ByteBufferBuilder(36 * 12)) {
                var builder = new BufferBuilder(bytes, VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION);
                int[][] faces = {{0, 1, 3, 2}, {4, 6, 7, 5}, {0, 4, 5, 1}, {2, 3, 7, 6}, {0, 2, 6, 4}, {1, 5, 7, 3}};
                for (int[] face : faces) {
                    for (int index : new int[]{face[0], face[1], face[2], face[0], face[2], face[3]}) {
                        builder.addVertex((index & 1) == 0 ? -1 : 1, (index & 2) == 0 ? -1 : 1, (index & 4) == 0 ? -1 : 1);
                    }
                }
                try (var data = builder.buildOrThrow()) {
                    cube = new VertexBuffer(VertexBuffer.Usage.STATIC);
                    cube.bind();
                    cube.upload(data);
                    VertexBuffer.unbind();
                }
            }
        }
        return cube;
    }

    /** The screen-sized colour and depth the room is drawn into. */
    private static final class RoomTarget {
        int framebuffer = -1, color = -1, depth = -1, width, height;

        void bind(int newWidth, int newHeight) {
            if (framebuffer < 0 || newWidth != width || newHeight != height) {
                close();
                width = newWidth;
                height = newHeight;
                int previousTexture = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);
                color = org.lwjgl.opengl.GL11.glGenTextures();
                org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, color);
                org.lwjgl.opengl.GL11.glTexImage2D(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_RGBA8, width, height, 0,
                    org.lwjgl.opengl.GL11.GL_RGBA, org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
                org.lwjgl.opengl.GL11.glTexParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER, org.lwjgl.opengl.GL11.GL_NEAREST);
                org.lwjgl.opengl.GL11.glTexParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER, org.lwjgl.opengl.GL11.GL_NEAREST);
                org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, previousTexture);
                depth = org.lwjgl.opengl.GL30.glGenRenderbuffers();
                org.lwjgl.opengl.GL30.glBindRenderbuffer(org.lwjgl.opengl.GL30.GL_RENDERBUFFER, depth);
                org.lwjgl.opengl.GL30.glRenderbufferStorage(org.lwjgl.opengl.GL30.GL_RENDERBUFFER, org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT24, width, height);
                framebuffer = org.lwjgl.opengl.GL30.glGenFramebuffers();
                org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, framebuffer);
                org.lwjgl.opengl.GL30.glFramebufferTexture2D(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0,
                    org.lwjgl.opengl.GL11.GL_TEXTURE_2D, color, 0);
                org.lwjgl.opengl.GL30.glFramebufferRenderbuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, org.lwjgl.opengl.GL30.GL_DEPTH_ATTACHMENT,
                    org.lwjgl.opengl.GL30.GL_RENDERBUFFER, depth);
            }
            org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, framebuffer);
            org.lwjgl.opengl.GL20.glDrawBuffers(org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0);
        }

        void close() {
            if (framebuffer >= 0) org.lwjgl.opengl.GL30.glDeleteFramebuffers(framebuffer);
            if (color >= 0) org.lwjgl.opengl.GL11.glDeleteTextures(color);
            if (depth >= 0) org.lwjgl.opengl.GL30.glDeleteRenderbuffers(depth);
            framebuffer = color = depth = -1;
        }
    }

    /** Each face as a fan, in map-local blocks. */
    private static Faces build(SkyTable sky, BundleManifest bundle) {
        int triangles = 0;
        for (float[] face : sky.faces()) triangles += face.length / 3 - 2;
        if (triangles == 0) return new Faces(null, 0, sky, bundle);
        try (var bytes = new ByteBufferBuilder(triangles * 3 * 12)) {
            var builder = new BufferBuilder(bytes, VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION);
            for (float[] face : sky.faces()) {
                for (int i = 1; i + 1 < face.length / 3; i++) {
                    builder.addVertex(face[0], face[1], face[2]);
                    builder.addVertex(face[i * 3], face[i * 3 + 1], face[i * 3 + 2]);
                    builder.addVertex(face[i * 3 + 3], face[i * 3 + 4], face[i * 3 + 5]);
                }
            }
            try (var data = builder.buildOrThrow()) {
                var buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                buffer.bind();
                buffer.upload(data);
                VertexBuffer.unbind();
                return new Faces(buffer, triangles, sky, bundle);
            }
        }
    }

    /** The six side textures, once all are loaded; null while one is still decoding. */
    private static int[] sideTextures(Faces faces) {
        int[] ids = new int[SkyTable.SUFFIXES.size()];
        boolean ready = true;
        for (int side = 0; side < ids.length; side++) {
            String id = faces.sky.sides().get(side);
            if (id == null) { ids[side] = black().getId(); continue; }
            Side loaded = SIDES.computeIfAbsent(id, ignored -> decode(faces.bundle, "sky/" + id + ".png"));
            if (loaded.texture == null && !loaded.failed && loaded.decode.isDone()) {
                try {
                    loaded.texture = new SideTexture(loaded.decode.join());
                    loaded.texture.load(null);
                } catch (RuntimeException e) {
                    Src2mc.LOGGER.warn("sky side {} could not be loaded", id, e);
                    loaded.failed = true;
                }
            }
            if (loaded.failed) ids[side] = black().getId();
            else if (loaded.texture == null) ready = false;
            else ids[side] = loaded.texture.getId();
        }
        return ready ? ids : null;
    }

    private static Side decode(BundleManifest bundle, String path) {
        return new Side(CompletableFuture.supplyAsync(() -> {
            try (ZipFile zip = new ZipFile(bundle.path().toFile())) {
                var entry = zip.getEntry(path);
                if (entry == null) throw new IllegalStateException("missing " + path);
                try (var input = zip.getInputStream(entry)) {
                    return NativeImage.read(input);
                }
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }, Util.backgroundExecutor()));
    }

    private static SideTexture black() {
        if (black == null) {
            NativeImage image = new NativeImage(1, 1, false);
            image.setPixelRGBA(0, 0, 0xFF000000);
            black = new SideTexture(image);
            black.load(null);
        }
        return black;
    }

    private static void clear() {
        for (Faces faces : FACES.values()) if (faces != null && faces.buffer != null) faces.buffer.close();
        FACES.clear();
        for (Side side : SIDES.values()) {
            if (side.texture != null) side.texture.close();
            else side.decode.thenAccept(NativeImage::close);
        }
        SIDES.clear();
        for (RoomSlot slot : ROOMS.values()) if (slot.scene != null) slot.scene.close();
        ROOMS.clear();
    }

    /** A side image, filtered linearly and clamped at its edges so the box shows no seams. */
    private static final class SideTexture extends AbstractTexture {
        private NativeImage image;

        SideTexture(NativeImage image) { this.image = image; }

        @Override
        public void load(ResourceManager ignored) {
            TextureUtil.prepareImage(getId(), 0, image.getWidth(), image.getHeight());
            image.upload(0, 0, 0, 0, 0, image.getWidth(), image.getHeight(), true, true, false, true);
            image = null;
        }

        @Override
        public void close() {
            if (image != null) image.close();
            image = null;
            releaseId();
        }
    }

    @SubscribeEvent
    public static void registerCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(literal("src2mc_sky")
            .then(literal("on").executes(context -> setEnabled(context.getSource(), true)))
            .then(literal("off").executes(context -> setEnabled(context.getSource(), false)))
            .then(literal("status").executes(context -> status(context.getSource())))
            .then(literal("room")
                .then(literal("on").executes(context -> setRoom(context.getSource(), true)))
                .then(literal("off").executes(context -> setRoom(context.getSource(), false))))
            .then(literal("exposure").then(net.minecraft.commands.Commands.argument("scale",
                    com.mojang.brigadier.arguments.FloatArgumentType.floatArg(0.01f, 64f))
                .executes(context -> setExposure(context.getSource(),
                    com.mojang.brigadier.arguments.FloatArgumentType.getFloat(context, "scale"))))));
    }

    private static int setRoom(CommandSourceStack source, boolean value) {
        roomEnabled = value;
        source.sendSuccess(() -> Component.literal("src2mc 3D skybox " + (value ? "on" : "off")), false);
        return 1;
    }

    private static int setExposure(CommandSourceStack source, float value) {
        // One tone map scale for everything, as Source has.
        BakedLighting.setExposure(value);
        source.sendSuccess(() -> Component.literal("src2mc exposure " + value + " (3D skybox and baked light)"), false);
        return 1;
    }

    private static int setEnabled(CommandSourceStack source, boolean value) {
        enabled = value;
        source.sendSuccess(() -> Component.literal("src2mc sky " + (value ? "on" : "off")), false);
        return 1;
    }

    private static String gpuMs(GpuTimer.Phase phase) {
        double ms = GpuTimer.averageMs(phase);
        return ms < 0 ? "-" : String.format(java.util.Locale.ROOT, "%.2f", ms);
    }

    private static int status(CommandSourceStack source) {
        List<String> lines = new ArrayList<>();
        lines.add("src2mc sky " + (enabled ? "on" : "off") + (shader == null ? ", shader not loaded" : "")
            + ": " + drawnMaps + " maps, " + drawnTriangles + " triangles drawn last frame");
        int loaded = 0, failed = 0, pending = 0;
        for (Side side : SIDES.values()) {
            if (side.failed) failed++;
            else if (side.texture != null) loaded++;
            else pending++;
        }
        lines.add("side textures: " + loaded + " loaded, " + pending + " decoding, " + failed + " failed");
        lines.add("3D skybox " + (roomEnabled ? "on" : "off") + ", exposure " + BakedLighting.exposure() + ": " + drawnRooms
            + " drawn last frame; last: " + roomState);
        double frames = Math.max(1, costFrames);
        lines.add(String.format(java.util.Locale.ROOT,
            "cost per frame over %d frames, CPU / GPU: snapshots %.2f / %s, room %.2f / %s, faces %.2f / %s",
            costFrames, snapshotNanos / 1e6 / frames, gpuMs(GpuTimer.Phase.SKY_SNAPSHOTS),
            roomNanos / 1e6 / frames, gpuMs(GpuTimer.Phase.SKY_ROOM), faceNanos / 1e6 / frames, gpuMs(GpuTimer.Phase.SKY_FACES)));
        snapshotNanos = roomNanos = faceNanos = costFrames = 0;
        for (var entry : FACES.entrySet()) {
            Faces faces = entry.getValue();
            if (faces == null) continue;
            long missing = faces.sky.sides().stream().filter(java.util.Objects::isNull).count();
            lines.add(entry.getKey().mapId() + ": " + faces.sky.faces().size() + " faces, " + faces.triangles + " triangles"
                + (missing > 0 ? ", " + missing + " sides missing (black)" : ""));
        }
        String text = String.join("\n", lines);
        source.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }
}
