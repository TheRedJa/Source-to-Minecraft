package dev.theredja.src2mc.client.logic;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.logic.ScreenEffects;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Vector3f;

/**
 * {@code C_EnvStarfield}: while it is on, a particle is added every frame (times the density, the
 * fraction carried over) in front of the view, flying across it as a short white streak
 * ({@code CTrailParticles}, fading in over 0.3 s and out over its 2 s of life). Everything is in Source
 * units divided by 32. The streaks are drawn as soft additive quads without depth testing, as
 * {@code effects/spark_noz} ("no z") asks; that material is not in the bundle, so its look is
 * an approximation (a falloff across the streak's width). Source adds one particle a frame; this
 * adds the same per second at 60 frames a second, so the field does not thicken with the frame rate.
 */
@EventBusSubscriber(modid = Src2mc.MOD_ID, value = Dist.CLIENT)
public final class Starfield {
    private Starfield() {}

    /** {@code cl_starfield_distance} and {@code cl_starfield_diameter}, in blocks. */
    private static final double DISTANCE = 256 / 32.0, DIAMETER = 128 / 32.0;
    private static final double UNITS_PER_BLOCK = 32;
    private static final double TRAVEL_TIME = 2.0;
    private static final double FRAMES_PER_SECOND = 60;
    private static final int MAX_PARTICLES = 4000;

    private static final class Particle {
        double x, y, z, vx, vy, vz, life, width, length;
    }

    private static final List<Particle> PARTICLES = new ArrayList<>();
    private static final Random RANDOM = new Random();
    private static boolean on;
    private static float density = 1;
    private static double carry;
    private static long lastNanos = -1;
    private static VertexBuffer buffer;

    public static void set(ScreenEffects.StarfieldPayload payload) {
        on = payload.on();
        density = payload.density();
    }

    public static void clear() {
        on = false;
        density = 1;
        carry = 0;
        PARTICLES.clear();
    }

    /** The buffer holds GPU memory that is not freed with the object: it is closed when the player leaves the world. */
    @SubscribeEvent
    static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        VertexBuffer old = buffer;
        buffer = null;
        if (old == null) return;
        if (RenderSystem.isOnRenderThread()) old.close();
        else RenderSystem.recordRenderCall(old::close);
    }

    private static double between(double low, double high) { return low + RANDOM.nextDouble() * (high - low); }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        if (dev.theredja.src2mc.client.render.IrisCompat.shadowPass()) return;
        Minecraft minecraft = Minecraft.getInstance();
        long now = System.nanoTime();
        double dt = lastNanos < 0 ? 0 : Math.min(0.25, (now - lastNanos) / 1e9);
        lastNanos = now;
        if (minecraft.level == null) { clear(); return; }
        if (minecraft.isPaused()) dt = 0;
        Camera camera = event.getCamera();
        if (on && density > 0 && dt > 0) spawn(camera, dt);
        // CTrailParticles::SimulateParticles: it moves and ages, and dies at its time.
        for (var it = PARTICLES.iterator(); it.hasNext(); ) {
            Particle particle = it.next();
            particle.x += particle.vx * dt;
            particle.y += particle.vy * dt;
            particle.z += particle.vz * dt;
            particle.life += dt;
            if (particle.life >= TRAVEL_TIME) it.remove();
        }
        if (PARTICLES.isEmpty()) return;
        draw(camera, event.getModelViewMatrix(), event.getProjectionMatrix());
    }

    /** {@code C_EnvStarfield::ClientThink}: from straight ahead of the view to a random point across it, a random way along. */
    private static void spawn(Camera camera, double dt) {
        carry += density * FRAMES_PER_SECOND * dt;
        int count = (int) Math.floor(carry);
        carry -= count;
        Vector3f look = camera.getLookVector(), left = camera.getLeftVector(), up = camera.getUpVector();
        var eye = camera.getPosition();
        for (int i = 0; i < count && PARTICLES.size() < MAX_PARTICLES; i++) {
            double startX = eye.x + look.x * DISTANCE, startY = eye.y + look.y * DISTANCE, startZ = eye.z + look.z * DISTANCE;
            double right = between(-DIAMETER, DIAMETER), above = between(-DIAMETER, DIAMETER);
            double endX = eye.x - left.x * right + up.x * above, endY = eye.y - left.y * right + up.y * above,
                endZ = eye.z - left.z * right + up.z * above;
            double dx = endX - startX, dy = endY - startY, dz = endZ - startZ;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance < 1e-6) continue;
            dx /= distance; dy /= distance; dz /= distance;
            double along = between(0.1, 0.3) * distance;
            Particle particle = new Particle();
            particle.x = startX + dx * along;
            particle.y = startY + dy * along;
            particle.z = startZ + dz * along;
            particle.vx = dx * distance / TRAVEL_TIME;
            particle.vy = dy * distance / TRAVEL_TIME;
            particle.vz = dz * distance / TRAVEL_TIME;
            particle.width = between(1, 3) / UNITS_PER_BLOCK;
            particle.length = between(0.05, 0.4);
            PARTICLES.add(particle);
        }
    }

    /**
     * {@code CTrailParticles::RenderParticles} and {@code Tracer_ComputeVerts}: each streak runs from the
     * particle along its velocity for {@code length * (1 - life / lifetime)} seconds of travel, as
     * wide as {@code width} but no wider than it is long, turned to face the view; its brightness is
     * the life fraction (fade in) for the first 0.3 s and the remaining life after.
     */
    private static void draw(Camera camera, org.joml.Matrix4f modelView, org.joml.Matrix4f projection) {
        var eye = camera.getPosition();
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        int quads = 0;
        for (Particle particle : PARTICLES) {
            double remaining = 1 - particle.life / TRAVEL_TIME;
            double scale = Math.max(particle.length * remaining, 0.01);
            double ramp = particle.life <= 0.3 ? particle.life : remaining;
            if (ramp <= 0) continue;
            double sx = particle.x - eye.x, sy = particle.y - eye.y, sz = particle.z - eye.z;
            double dx = particle.vx * scale, dy = particle.vy * scale, dz = particle.vz * scale;
            double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double width = Math.min(length, particle.width);
            // The quad's width axis: the streak's direction crossed with the way to it, so it faces the view.
            double nx = dy * sz - dz * sy, ny = dz * sx - dx * sz, nz = dx * sy - dy * sx;
            double size = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (size < 1e-9) continue;
            double factor = 0.5 * width / size;
            nx *= factor; ny *= factor; nz *= factor;
            int alpha = (int) Math.round(Math.max(0, Math.min(1, ramp)) * 255);
            // Three columns, transparent at the edges and solid down the middle: a soft streak.
            float[] x = new float[6], y = new float[6], z = new float[6];
            for (int end = 0; end < 2; end++) {
                double ex = sx + (end == 0 ? 0 : dx), ey = sy + (end == 0 ? 0 : dy), ez = sz + (end == 0 ? 0 : dz);
                x[end * 3] = (float) (ex - nx); y[end * 3] = (float) (ey - ny); z[end * 3] = (float) (ez - nz);
                x[end * 3 + 1] = (float) ex; y[end * 3 + 1] = (float) ey; z[end * 3 + 1] = (float) ez;
                x[end * 3 + 2] = (float) (ex + nx); y[end * 3 + 2] = (float) (ey + ny); z[end * 3 + 2] = (float) (ez + nz);
            }
            for (int column = 0; column < 2; column++) {
                int a = column, b = column + 1, c = column + 3, d = column + 4;
                vertex(builder, x, y, z, a, alpha); vertex(builder, x, y, z, b, alpha); vertex(builder, x, y, z, d, alpha);
                vertex(builder, x, y, z, a, alpha); vertex(builder, x, y, z, d, alpha); vertex(builder, x, y, z, c, alpha);
            }
            quads += 2;
        }
        if (quads == 0) { builder.build(); return; }
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        try {
            if (buffer == null) buffer = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
            buffer.bind();
            buffer.upload(builder.buildOrThrow());
            buffer.drawWithShader(new org.joml.Matrix4f(modelView), new org.joml.Matrix4f(projection), RenderSystem.getShader());
            VertexBuffer.unbind();
        } finally {
            RenderSystem.enableCull();
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
        }
    }

    /** Index 1 and 4 of each end are the middle column; the others fade to nothing at the edge. */
    private static void vertex(BufferBuilder builder, float[] x, float[] y, float[] z, int index, int alpha) {
        boolean middle = index % 3 == 1;
        builder.addVertex(x[index], y[index], z[index]).setColor(255, 255, 255, middle ? alpha : 0);
    }
}
