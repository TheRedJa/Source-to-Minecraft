package dev.theredja.src2mc.client.particles;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.bundle.AtlasIndex;
import dev.theredja.src2mc.bundle.BundleManifest;
import dev.theredja.src2mc.bundle.BundleMap;
import dev.theredja.src2mc.bundle.ParticleTable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

/**
 * Draws particles as Source's renderers lay them out: quads built here with the spritecard vertex
 * shader's placement (SDK {@code spritecard_vsxx.fxc}), sheet frames picked per particle, and the
 * {@code src2mc:particle} shader doing the spritecard pixel work. Each system draws in one call,
 * its particles back to front; effects far to near.
 */
public final class ParticleRenderer {
    private ParticleRenderer() {}

    /** Four floats: the second frame's texture coordinates, the blend towards it, unused. */
    static final VertexFormatElement FRAME = VertexFormatElement.register(VertexFormatElement.findNextId(), 0,
        VertexFormatElement.Type.FLOAT, VertexFormatElement.Usage.GENERIC, 4);
    static final VertexFormat FORMAT = VertexFormat.builder()
        .add("Position", VertexFormatElement.POSITION)
        .add("Color", VertexFormatElement.COLOR)
        .add("UV0", VertexFormatElement.UV0)
        .add("Frame", FRAME)
        .build();

    private static ShaderInstance shader;
    /** Refilled for every draw. */
    private static VertexBuffer buffer;

    @EventBusSubscriber(modid = Src2mc.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Shaders {
        private Shaders() {}

        @SubscribeEvent
        public static void register(RegisterShadersEvent event) throws IOException {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                ResourceLocation.fromNamespaceAndPath(Src2mc.MOD_ID, "particle"), FORMAT), loaded -> shader = loaded);
        }
    }

    static long drawnParticles, drawCalls;

    /** Where a material sits on its atlas page. */
    public record Placement(int page, float u0, float v0, float du, float dv) {}

    /** The material's place on the atlas; null when it has no single region there. */
    public static Placement placement(BundleMap map, ParticleTable.Material material) {
        if (material == null || map.atlas() == null || material.material() >= map.materials().size()) return null;
        var reference = map.materials().get(material.material()).texture();
        if (reference == null) return null;
        AtlasIndex.Texture texture = map.atlas().textures().get(reference.contentId());
        if (texture == null || texture.regions().size() != 1) return null;
        AtlasIndex.Region region = texture.regions().getFirst();
        int[] allocation = region.allocation();
        float size = map.atlas().pageSize();
        return new Placement(region.page(), allocation[0] / size, allocation[1] / size, texture.width() / size, texture.height() / size);
    }

    /** One effect to draw, with what it takes to reach its textures. */
    record Draw(Effect effect, BundleManifest bundle, BundleMap map) {}

    static void render(List<Draw> draws, Camera camera, Matrix4f modelView, Matrix4f projection, long generation, long frame) {
        if (shader == null || draws.isEmpty()) return;
        var cameraPos = camera.getPosition();
        Vector3f left = camera.getLeftVector(), up = camera.getUpVector();
        // Source's view space: X right, Y up, Z towards the viewer.
        double[] right = {-left.x, -left.y, -left.z}, upv = {up.x, up.y, up.z};
        var look = camera.getLookVector();
        double[] back = {-look.x, -look.y, -look.z};
        List<Draw> sorted = new ArrayList<>(draws);
        sorted.sort((a, b) -> Double.compare(distance2(b.effect, cameraPos), distance2(a.effect, cameraPos)));
        RenderSystem.setShader(() -> shader);
        RenderSystem.enableBlend();
        RenderSystem.depthMask(false);
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();
        shader.safeGetUniform("ParticleExposure").set(dev.theredja.src2mc.client.render.BakedLighting.currentExposure());
        int previousTexture = RenderSystem.getShaderTexture(0);
        try {
            for (Draw draw : sorted) {
                drawSystem(draw, draw.effect.root, cameraPos.x, cameraPos.y, cameraPos.z, right, upv, back, modelView, projection, generation, frame);
            }
        } finally {
            RenderSystem.setShaderTexture(0, previousTexture);
            RenderSystem.enableCull();
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
        }
    }

    private static double distance2(Effect effect, net.minecraft.world.phys.Vec3 camera) {
        double dx = effect.sortX - camera.x, dy = effect.sortY - camera.y, dz = effect.sortZ - camera.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static void drawSystem(Draw draw, ParticleSystem system, double cx, double cy, double cz, double[] right, double[] up, double[] back,
                                   Matrix4f modelView, Matrix4f projection, long generation, long frame) {
        if (system.count > 0 && system.material != null && !system.renderers.isEmpty()) {
            Placement placement = placement(draw.map, system.material);
            if (placement != null) {
                var texture = dev.theredja.src2mc.client.render.MapSurfaceRenderer.atlasPages()
                    .request(generation, draw.bundle, draw.map.atlas(), placement.page(), frame)
                    .orElseGet(dev.theredja.src2mc.client.render.MapSurfaceRenderer.atlasPages()::placeholderTexture);
                for (Renderers.Renderer renderer : system.renderers) {
                    drawRenderer(draw, system, renderer, placement, texture, cx, cy, cz, right, up, back, modelView, projection);
                }
            }
        }
        for (ParticleSystem child : system.children) drawSystem(draw, child, cx, cy, cz, right, up, back, modelView, projection, generation, frame);
    }

    private static void drawRenderer(Draw draw, ParticleSystem s, Renderers.Renderer renderer, Placement placement, ResourceLocation texture,
                                     double cx, double cy, double cz, double[] right, double[] up, double[] back,
                                     Matrix4f modelView, Matrix4f projection) {
        ParticleTable.Material material = s.material;
        boolean spritecard = material.shader().equals("spritecard");
        float overbright = spritecard ? (float) material.parameter("$overbrightfactor", 1) : 1;
        float addSelf = spritecard ? (float) material.parameter("$addself", 0) : 0;
        boolean addOver = spritecard && (addSelf != 0 || material.parameter("$addoverblend", 0) != 0 || material.parameter("$addbasetexture2", 0) != 0);
        boolean blendFrames = spritecard && material.parameter("$blendframes", 1) != 0;
        boolean maxLum = spritecard && material.parameter("$maxlumframeblend1", 0) != 0;
        boolean ignoreZ = material.parameter("$ignorez", 0) != 0;
        Space space = s.effect.space;

        // Back to front within the system, as its "Sort particles" asks by default.
        int count = s.count;
        Integer[] order = new Integer[count];
        double[] depth = new double[count];
        for (int q = 0; q < count; q++) {
            order[q] = q;
            double dx = space.worldX(s.x[q]) - cx, dy = space.worldY(s.z[q]) - cy, dz = space.worldZ(s.y[q]) - cz;
            depth[q] = dx * dx + dy * dy + dz * dz;
        }
        if (renderer.kind() != Renderers.Kind.ROPE && s.definition.attributes().bool("Sort particles", true)) {
            java.util.Arrays.sort(order, (a, b) -> Double.compare(depth[b], depth[a]));
        }

        int quads = renderer.kind() == Renderers.Kind.ROPE ? Math.max(0, count - 1) * renderer.subdivisions() : count;
        if (quads == 0) return;
        int stride = FORMAT.getVertexSize();
        int vertices = quads * 6;
        try (ByteBufferBuilder bytes = new ByteBufferBuilder(vertices * stride)) {
            long base = bytes.reserve(vertices * stride);
            Writer writer = new Writer(base, stride, cx, cy, cz, placement);
            int written = switch (renderer.kind()) {
                case SPRITES -> sprites(writer, s, renderer, order, right, up, back, cx, cy, cz, spritecard);
                case TRAIL -> trails(writer, s, renderer, order, cx, cy, cz);
                case ROPE -> rope(writer, s, renderer, cx, cy, cz);
            };
            if (written == 0) return;
            RenderSystem.setShaderTexture(0, texture);
            shader.safeGetUniform("ParticleOverbright").set(overbright);
            shader.safeGetUniform("ParticleAddSelf").set(addSelf);
            shader.safeGetUniform("ParticleBlendFrames").set(blendFrames ? 1f : 0f);
            shader.safeGetUniform("ParticleMaxLum").set(maxLum ? 1f : 0f);
            shader.safeGetUniform("ParticleAdditive").set(material.additive() ? 1f : 0f);
            // spritecard.cpp: ONE:INVSRCALPHA for $addself and friends, SRCALPHA:ONE when additive, else alpha blending.
            if (addOver) RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
            else if (material.additive()) RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
            else RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
            if (ignoreZ) RenderSystem.disableDepthTest(); else RenderSystem.enableDepthTest();
            int vertexCount = written * 6;
            MeshData mesh = new MeshData(bytes.build(), new MeshData.DrawState(FORMAT, vertexCount, vertexCount,
                VertexFormat.Mode.TRIANGLES, VertexFormat.IndexType.least(vertexCount)));
            if (buffer == null) buffer = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
            buffer.bind();
            buffer.upload(mesh);
            buffer.drawWithShader(modelView, projection, shader);
            VertexBuffer.unbind();
            drawCalls++;
            drawnParticles += renderer.kind() == Renderers.Kind.ROPE ? count : written;
        }
    }

    /** Writes quads as two triangles, in camera-relative world coordinates. */
    private static final class Writer {
        final long base;
        final int stride;
        final double cx, cy, cz;
        final Placement placement;
        int quads;

        Writer(long base, int stride, double cx, double cy, double cz, Placement placement) {
            this.base = base; this.stride = stride; this.cx = cx; this.cy = cy; this.cz = cz; this.placement = placement;
        }

        /**
         * One quad: corners {@code c} as 12 world doubles in order (0,0) (1,0) (1,1) (0,1) of the
         * corner identifier; frame rectangles in texture fractions; colour as 0..1 tint and alpha.
         */
        void quad(double[] c, float[] rect0, float[] rect1, float blend, float r, float g, float b, float a) {
            int[] order = {0, 1, 2, 0, 2, 3};
            int color = Math.round(clamp(r) * 255) | Math.round(clamp(g) * 255) << 8 | Math.round(clamp(b) * 255) << 16 | Math.round(clamp(a) * 255) << 24;
            for (int k = 0; k < 6; k++) {
                int corner = order[k];
                // spritecard_vsxx: u from the rectangle's z towards x as the corner's x goes 0 to 1, v from w towards y.
                float cornerX = corner == 1 || corner == 2 ? 1 : 0, cornerY = corner >= 2 ? 1 : 0;
                long at = base + (long) (quads * 6 + k) * stride;
                MemoryUtil.memPutFloat(at, (float) (c[corner * 3] - cx));
                MemoryUtil.memPutFloat(at + 4, (float) (c[corner * 3 + 1] - cy));
                MemoryUtil.memPutFloat(at + 8, (float) (c[corner * 3 + 2] - cz));
                MemoryUtil.memPutInt(at + 12, color);
                MemoryUtil.memPutFloat(at + 16, placement.u0() + placement.du() * lerp(rect0[2], rect0[0], cornerX));
                MemoryUtil.memPutFloat(at + 20, placement.v0() + placement.dv() * lerp(rect0[3], rect0[1], cornerY));
                MemoryUtil.memPutFloat(at + 24, placement.u0() + placement.du() * lerp(rect1[2], rect1[0], cornerX));
                MemoryUtil.memPutFloat(at + 28, placement.v0() + placement.dv() * lerp(rect1[3], rect1[1], cornerY));
                MemoryUtil.memPutFloat(at + 32, blend);
                MemoryUtil.memPutFloat(at + 36, 0);
            }
            quads++;
        }
    }

    private static float clamp(float v) { return v < 0 ? 0 : Math.min(v, 1); }
    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    private static final float[] FULL = {0, 0, 1, 1};

    /** Picks the two frames and blend of a sheet sequence at sheet time {@code t}, as noclip's {@code Sheet.calcScaleBias}. */
    static float frames(ParticleTable.Material material, int sequence, float t, float[][] out) {
        out[0] = FULL; out[1] = FULL;
        if (material == null || material.sheet().isEmpty()) return 0;
        if (sequence < 0 || sequence >= material.sheet().size()) sequence = 0;
        ParticleTable.Sequence seq = material.sheet().get(sequence);
        if (seq == null || seq.frames() == 0) return 0;
        float total = seq.total();
        if (total <= 0) { out[0] = rect(seq, 0); out[1] = out[0]; return 0; }
        if (seq.clamp()) t = Math.min(Math.max(t, 0), total);
        else t = ((t % total) + total) % total;
        float start = 0;
        for (int f = 0; f < seq.frames(); f++) {
            float end = start + seq.durations()[f];
            boolean last = f == seq.frames() - 1;
            if (t < end || last) {
                out[0] = rect(seq, f);
                if (last) {
                    if (seq.clamp()) { out[1] = out[0]; return 0; }
                    out[1] = rect(seq, 0);
                } else {
                    out[1] = rect(seq, f + 1);
                }
                float duration = seq.durations()[f];
                return duration > 0 ? clamp((t - start) / duration) : 0;
            }
            start = end;
        }
        return 0;
    }

    private static float[] rect(ParticleTable.Sequence seq, int frame) {
        float[] r = seq.rects()[frame];
        return new float[]{r[0], r[1], r[2], r[3]};
    }

    /** Sheet time of a particle under a renderer's rate rules (see {@link Renderers.Renderer}). */
    static float sheetTime(ParticleSystem s, int q, Renderers.Renderer renderer, ParticleTable.Material material) {
        float age = s.age(q);
        int sequence = (int) s.scalar[ParticleSystem.SEQUENCE][q];
        float total = 1;
        if (material != null && sequence >= 0 && sequence < material.sheet().size() && material.sheet().get(sequence) != null) {
            total = material.sheet().get(sequence).total();
        }
        if (renderer.fitLifetime()) {
            float life = s.scalar[ParticleSystem.LIFE][q];
            return life > 0 ? Math.min(age / life, 0.9999F) * total : 0;
        }
        if (renderer.rateAsFps()) return age * renderer.animationRate();
        return age * renderer.animationRate() * total;
    }

    private static int sprites(Writer w, ParticleSystem s, Renderers.Renderer renderer, Integer[] order, double[] right, double[] up, double[] back,
                               double cx, double cy, double cz, boolean spritecard) {
        Space space = s.effect.space;
        ParticleTable.Material material = s.material;
        int orientation = renderer.orientation();
        if (!spritecard) orientation = 0;
        float minSize = (float) material.parameter("$minsize", 0), maxSize = (float) material.parameter("$maxsize", 20);
        float startFade = (float) material.parameter("$startfadesize", 10), endFade = (float) material.parameter("$endfadesize", 20);
        float maxDistance = (float) material.parameter("$maxdistance", 100000), farFade = (float) material.parameter("$farfadeinterval", 400);
        float startFar = Math.max(1, maxDistance - farFade), farFactor = 1 / Math.max(1e-3F, maxDistance - startFar);
        double[] corners = new double[12];
        float[][] rects = new float[2][];
        double[] eye = new double[3];
        space.toSource(cx, cy, cz, eye);
        for (Integer index : order) {
            int q = index;
            float radius = s.scalar[ParticleSystem.RADIUS][q], alpha = s.scalar[ParticleSystem.ALPHA][q];
            if (radius <= 0 || alpha <= 0) continue;
            double dx = s.x[q] - eye[0], dy = s.y[q] - eye[1], dz = s.z[q] - eye[2];
            float l = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            // spritecard_vsxx: screen-size clamps and fades, then the far fade, in Source units.
            float tint = 1;
            radius = Math.max(radius, minSize * l);
            if (radius > startFade * l) {
                if (radius > endFade * l) continue;
                tint *= 1 - (radius - startFade * l) / (endFade * l - startFade * l);
            }
            float far = 1 - Math.min(1, Math.max(0, (l - startFar) * farFactor));
            if (far <= 0) continue;
            tint *= far;
            radius = Math.min(radius, maxSize * l);
            float blend = frames(material, (int) s.scalar[ParticleSystem.SEQUENCE][q], sheetTime(s, q, renderer, material), rects);
            float rotation = s.scalar[ParticleSystem.ROTATION][q], yaw = s.scalar[ParticleSystem.YAW][q];
            double sc = Math.sin(rotation), cc = Math.cos(rotation), sy = Math.sin(yaw), cyaw = Math.cos(yaw);
            double wx = space.worldX(s.x[q]), wy = space.worldY(s.z[q]), wz = space.worldZ(s.y[q]);
            double r = radius / Space.UNITS;
            for (int corner = 0; corner < 4; corner++) {
                double ixx = (corner == 1 || corner == 2) ? 1 : -1, ixy = corner >= 2 ? 1 : -1;
                double x1 = ixx * cc + ixy * sc, y1 = cc * ixy - sc * ixx;
                double ox, oy, oz;
                if (orientation == 2) {
                    // Parallel to the ground: Source offset (y1, x1, 0) times the radius.
                    ox = y1 * r; oy = 0; oz = -x1 * r;
                } else if (orientation == 1) {
                    // Upright: turned about Source's Z to face the eye.
                    // right = normalize(cross(up, eye to particle)), turned by the yaw, in Source's XY plane.
                    double vx = (wx - cx), vy = -(wz - cz);
                    double length = Math.sqrt(vx * vx + vy * vy);
                    double rsx = length > 1e-9 ? -vy / length : 1, rsy = length > 1e-9 ? vx / length : 0;
                    double tsx = rsx * cyaw + rsy * sy, tsy = rsy * cyaw - rsx * sy;
                    ox = tsx * x1 * r; oy = y1 * r; oz = -tsy * x1 * r;
                } else {
                    double px = -x1, pz = 0;
                    double tx = px * cyaw + pz * sy;
                    double tz = pz * cyaw - px * sy;
                    ox = (right[0] * tx + up[0] * y1 + back[0] * tz) * r;
                    oy = (right[1] * tx + up[1] * y1 + back[1] * tz) * r;
                    oz = (right[2] * tx + up[2] * y1 + back[2] * tz) * r;
                }
                corners[corner * 3] = wx + ox; corners[corner * 3 + 1] = wy + oy; corners[corner * 3 + 2] = wz + oz;
            }
            w.quad(corners, rects[0], rects[1], blend, s.r[q], s.g[q], s.b[q], alpha * tint);
        }
        return w.quads;
    }

    /** A sprite stretched back along the particle's velocity, its length the trail length in seconds of travel. */
    private static int trails(Writer w, ParticleSystem s, Renderers.Renderer renderer, Integer[] order, double cx, double cy, double cz) {
        Space space = s.effect.space;
        double[] corners = new double[12];
        float[][] rects = new float[2][];
        for (Integer index : order) {
            int q = index;
            float radius = s.scalar[ParticleSystem.RADIUS][q], alpha = s.scalar[ParticleSystem.ALPHA][q];
            if (radius <= 0 || alpha <= 0 || s.dt <= 0) continue;
            double vx = (s.x[q] - s.px[q]) / s.dt, vy = (s.y[q] - s.py[q]) / s.dt, vz = (s.z[q] - s.pz[q]) / s.dt;
            double speed = Math.sqrt(vx * vx + vy * vy + vz * vz);
            if (speed < 1e-6) continue;
            double length = speed * s.scalar[ParticleSystem.TRAIL][q];
            if (renderer.lengthFadeIn() > 0) length *= Math.min(1, s.age(q) / renderer.lengthFadeIn());
            length = Math.max(renderer.minLength(), Math.min(renderer.maxLength(), length));
            // Direction in world axes.
            double dx = vx / speed, dy = vz / speed, dz = -vy / speed;
            double hx = space.worldX(s.x[q]), hy = space.worldY(s.z[q]), hz = space.worldZ(s.y[q]);
            double l = length / Space.UNITS;
            double tx = hx - dx * l, ty = hy - dy * l, tz = hz - dz * l;
            double mx = (hx + tx) * 0.5 - cx, my = (hy + ty) * 0.5 - cy, mz = (hz + tz) * 0.5 - cz;
            double sx = dy * mz - dz * my, sy = dz * mx - dx * mz, sz = dx * my - dy * mx;
            double side = Math.sqrt(sx * sx + sy * sy + sz * sz);
            if (side < 1e-9) continue;
            double rr = radius / Space.UNITS / side;
            sx *= rr; sy *= rr; sz *= rr;
            frames(s.material, (int) s.scalar[ParticleSystem.SEQUENCE][q], s.age(q) * renderer.animationRate(), rects);
            // Corner y 1 (v from the rectangle's top) at the head.
            corners[0] = tx + sx; corners[1] = ty + sy; corners[2] = tz + sz;
            corners[3] = tx - sx; corners[4] = ty - sy; corners[5] = tz - sz;
            corners[6] = hx - sx; corners[7] = hy - sy; corners[8] = hz - sz;
            corners[9] = hx + sx; corners[10] = hy + sy; corners[11] = hz + sz;
            w.quad(corners, rects[0], rects[0], 0, s.r[q], s.g[q], s.b[q], alpha);
        }
        return w.quads;
    }

    /** All particles in order as one ribbon, Catmull-Rom between them, the texture along its length. */
    private static int rope(Writer w, ParticleSystem s, Renderers.Renderer renderer, double cx, double cy, double cz) {
        Space space = s.effect.space;
        int n = s.count;
        if (n < 2) return 0;
        int steps = renderer.subdivisions();
        int points = (n - 1) * steps + 1;
        double[] px = new double[points], py = new double[points], pz = new double[points];
        float[] radius = new float[points], alpha = new float[points], r = new float[points], g = new float[points], b = new float[points];
        int k = 0;
        for (int i = 0; i < n - 1; i++) {
            int i0 = Math.max(0, i - 1), i1 = i, i2 = i + 1, i3 = Math.min(n - 1, i + 2);
            for (int step = 0; step < steps || (i == n - 2 && step == steps); step++) {
                double t = (double) step / steps;
                px[k] = catmull(space.worldX(s.x[i0]), space.worldX(s.x[i1]), space.worldX(s.x[i2]), space.worldX(s.x[i3]), t);
                py[k] = catmull(space.worldY(s.z[i0]), space.worldY(s.z[i1]), space.worldY(s.z[i2]), space.worldY(s.z[i3]), t);
                pz[k] = catmull(space.worldZ(s.y[i0]), space.worldZ(s.y[i1]), space.worldZ(s.y[i2]), space.worldZ(s.y[i3]), t);
                float tf = (float) t;
                radius[k] = lerp(s.scalar[ParticleSystem.RADIUS][i1], s.scalar[ParticleSystem.RADIUS][i2], tf) / (float) Space.UNITS;
                alpha[k] = lerp(s.scalar[ParticleSystem.ALPHA][i1], s.scalar[ParticleSystem.ALPHA][i2], tf);
                r[k] = lerp(s.r[i1], s.r[i2], tf); g[k] = lerp(s.g[i1], s.g[i2], tf); b[k] = lerp(s.b[i1], s.b[i2], tf);
                k++;
                if (k >= points) break;
            }
        }
        double total = 0;
        double[] along = new double[k];
        for (int i = 1; i < k; i++) {
            double dx = px[i] - px[i - 1], dy = py[i] - py[i - 1], dz = pz[i] - pz[i - 1];
            total += Math.sqrt(dx * dx + dy * dy + dz * dz);
            along[i] = total;
        }
        if (total <= 0) return 0;
        float scroll = s.time * renderer.scrollRate();
        float texel = renderer.texelSize() > 0 ? renderer.texelSize() : 1;
        double[] corners = new double[12];
        for (int i = 0; i + 1 < k; i++) {
            double dx = px[i + 1] - px[i], dy = py[i + 1] - py[i], dz = pz[i + 1] - pz[i];
            double mx = px[i] - cx, my = py[i] - cy, mz = pz[i] - cz;
            double sx = dy * mz - dz * my, sy = dz * mx - dx * mz, sz = dx * my - dy * mx;
            double side = Math.sqrt(sx * sx + sy * sy + sz * sz);
            if (side < 1e-12) continue;
            sx /= side; sy /= side; sz /= side;
            float v0 = (float) (along[i] / total / texel) + scroll, v1 = (float) (along[i + 1] / total / texel) + scroll;
            corners[0] = px[i] + sx * radius[i]; corners[1] = py[i] + sy * radius[i]; corners[2] = pz[i] + sz * radius[i];
            corners[3] = px[i] - sx * radius[i]; corners[4] = py[i] - sy * radius[i]; corners[5] = pz[i] - sz * radius[i];
            corners[6] = px[i + 1] - sx * radius[i + 1]; corners[7] = py[i + 1] - sy * radius[i + 1]; corners[8] = pz[i + 1] - sz * radius[i + 1];
            corners[9] = px[i + 1] + sx * radius[i + 1]; corners[10] = py[i + 1] + sy * radius[i + 1]; corners[11] = pz[i + 1] + sz * radius[i + 1];
            float[] rect = {0, v1, 1, v0};
            w.quad(corners, rect, rect, 0, r[i], g[i], b[i], alpha[i]);
        }
        return w.quads;
    }

    private static double catmull(double p0, double p1, double p2, double p3, double t) {
        double t2 = t * t, t3 = t2 * t;
        return 0.5 * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
    }
}
