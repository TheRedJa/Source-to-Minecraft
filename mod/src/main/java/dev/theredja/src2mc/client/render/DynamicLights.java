package dev.theredja.src2mc.client.render;

import dev.theredja.src2mc.client.view.SecondaryView;
import dev.theredja.src2mc.client.view.ViewClient;
import dev.theredja.src2mc.view.ViewState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.joml.Matrix4f;
import org.joml.Vector3d;

/**
 * The dlights {@code light_dynamic}s make, as {@code C_DynamicLight::ClientThink} ({@code c_dynamiclight.cpp:129-236})
 * makes them each frame:
 * <ul>
 * <li>the light itself, unless it has no model illumination, lighting models only when it is a spotlight
 * ({@code _cone} above 0 adds {@code DLIGHT_NO_WORLD_ILLUMINATION}, :153-154);</li>
 * <li>a spotlight's "spot end" for the world: a point light of radius {@code spotlight_radius} where a line
 * {@code distance} long along the light hits the map, its colour times {@code (1 - fraction)^2}, lighting no models
 * (:175-233).</li>
 * </ul>
 * How strongly a dlight lights a point is the engine's and not in the public SDK; {@link #mode} picks the falloff
 * (linear by default, inherited from Quake's lightmap dlights) so it can be compared in game. The baked shader adds
 * the lights to the diffuse light of map surfaces (no Lambert term, as Quake's lightmap dlights) and props (Lambert).
 */
public final class DynamicLights {
    private DynamicLights() {}

    /** Lights the shader takes at once ({@code DLight0..7}). */
    static final int MAX_LIGHTS = 8;
    private static final int NO_WORLD = 0x1, NO_MODEL = 0x2;

    /** 0 off, 1 linear {@code 1 - d/r}, 2 quadratic {@code 1 - (d/r)^2}; a toggle while the engine's falloff is unknown. */
    public static int mode = 1;

    /** One light for the shader, in world blocks. */
    private record Entry(Vec3 position, float radius, float red, float green, float blue, int flags, Vec3 direction,
                         float cosInner, float cosOuter) {}

    private static List<Entry> entries = List.of();
    private static int lightCount = 0;
    /** What {@link #apply} last uploaded: for which light list and camera. */
    private static int version, appliedVersion = -1;
    private static Vec3 appliedCamera;
    private static ShaderInstance appliedShader;

    /** Counters for {@code /src2mcc status look}. */
    public static int lightsLastFrame() { return entries.size(); }

    /** Builds this frame's lights from the received view states; call before anything of the frame draws. */
    public static void update(float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        List<Entry> out = new ArrayList<>();
        if (mode != 0 && minecraft.level != null) {
            for (ViewClient.Placed placed : ViewClient.all()) {
                for (ViewState.Light light : placed.state().lights()) {
                    ViewClient.WorldLight pose = ViewClient.light(placed, light, partialTick);
                    add(out, light, pose, minecraft);
                }
            }
        }
        entries = out;
        version++;
    }

    private static void add(List<Entry> out, ViewState.Light light, ViewClient.WorldLight pose, Minecraft minecraft) {
        Vec3 origin = new Vec3(pose.position().x, pose.position().y, pose.position().z);
        Vector3d d = pose.direction();
        Vec3 forward = new Vec3(d.x, d.y, d.z);
        boolean spot = light.outer() > 0;
        float cosInner = (float) Math.cos(Math.toRadians(light.inner())), cosOuter = (float) Math.cos(Math.toRadians(light.outer()));
        if ((light.flags() & NO_MODEL) == 0 && light.radius() > 0) {
            int flags = light.flags() | (spot ? NO_WORLD : 0);
            out.add(new Entry(origin, light.radius(), light.red(), light.green(), light.blue(), flags, forward,
                spot ? cosInner : -2, spot ? cosOuter : -2));
        }
        if (spot && (light.flags() & NO_WORLD) == 0 && light.spotRadius() > 0 && light.radius() > 0) {
            // UTIL_TraceLine( GetAbsOrigin(), origin + m_Radius * forward, MASK_NPCWORLDSTATIC ): the map's collision.
            Vec3 end = origin.add(forward.scale(light.radius()));
            BlockHitResult hit = minecraft.level.clip(new ClipContext(origin, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                CollisionContext.empty()));
            double fraction = hit.getType() == HitResult.Type.MISS ? 1 : hit.getLocation().distanceTo(origin) / light.radius();
            float falloff = (float) ((1 - fraction) * (1 - fraction));
            if (falloff <= 0) return;
            Vec3 at = hit.getType() == HitResult.Type.MISS ? end : hit.getLocation();
            out.add(new Entry(at, light.spotRadius(), light.red() * falloff, light.green() * falloff, light.blue() * falloff,
                NO_MODEL, forward, -2, -2));
        }
    }

    /**
     * Gives the draw the lights nearest the camera it is drawn from, camera-relative; uploads only when the lights,
     * the camera or the shader changed. Render thread.
     */
    static void apply(ShaderInstance shader) {
        Minecraft minecraft = Minecraft.getInstance();
        Vec3 camera = SecondaryView.active() ? SecondaryView.cameraPosition() : minecraft.gameRenderer.getMainCamera().getPosition();
        if (camera == null) camera = Vec3.ZERO;
        if (appliedVersion == version && shader == appliedShader && camera.equals(appliedCamera)) return;
        appliedVersion = version;
        appliedShader = shader;
        appliedCamera = camera;
        List<Entry> chosen = entries;
        if (chosen.size() > MAX_LIGHTS) {
            Vec3 from = camera;
            chosen = new ArrayList<>(chosen);
            chosen.sort(Comparator.comparingDouble(e -> Math.max(0, e.position.distanceTo(from) - e.radius)));
            chosen = chosen.subList(0, MAX_LIGHTS);
        }
        lightCount = mode == 0 ? 0 : chosen.size();
        shader.safeGetUniform("DLightCount").set(lightCount);
        shader.safeGetUniform("DLightMode").set((float) mode);
        Matrix4f matrix = new Matrix4f();
        for (int i = 0; i < MAX_LIGHTS; i++) {
            if (i < lightCount) {
                Entry e = chosen.get(i);
                // Columns: position and radius, colour and flags, direction and cos outer, cos inner.
                matrix.set(
                    (float) (e.position.x - camera.x), (float) (e.position.y - camera.y), (float) (e.position.z - camera.z), e.radius,
                    e.red, e.green, e.blue, e.flags,
                    (float) e.direction.x, (float) e.direction.y, (float) e.direction.z, e.cosOuter,
                    e.cosInner, 0, 0, 0);
            } else {
                matrix.zero();
            }
            shader.safeGetUniform("DLight" + i).set(matrix);
        }
    }

    /** Forces the next {@link #apply} to upload (the shader reloaded, or a draw path reset uniforms). */
    static void invalidate() { appliedVersion = -1; }
}
