package dev.theredja.src2mc.client.look;

import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.bundle.BundleMap;
import dev.theredja.src2mc.bundle.LogicTable;
import dev.theredja.src2mc.bundle.LookTable;
import dev.theredja.src2mc.logic.LookSync;
import dev.theredja.src2mc.network.PlacementNetwork;
import dev.theredja.src2mc.world.LookState;
import dev.theredja.src2mc.world.MapPlacement;
import dev.theredja.src2mc.world.PropStates;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * The client half of how a map's view looks (D30): the view state each running map's server
 * sends ({@link LookSync}), or for a map whose logic is not running the state its entities spawn
 * with; and what the camera's map makes of it each frame, as Source's client does: the fog of
 * the controller the player follows ({@code C_BasePlayer::UpdateFogBlend},
 * {@code viewrender.cpp}'s {@code GetFog*}), the tonemap controller's exposure range and bloom
 * scale, and the colour lookups' weights ({@code C_ColorCorrection::ClientThink}, the material
 * system's {@code GetNormalizedWeights}).
 */
@EventBusSubscriber(modid = Src2mc.MOD_ID, value = Dist.CLIENT)
public final class LookClient {
    private LookClient() {}

    private static final double UNITS_PER_BLOCK = 32;

    /** A view the server sent, with the map time it was sent at and when it arrived. */
    private record Received(LookState.View view, double now, long arrived) {}

    private static final Map<PropStates.Key, Received> RECEIVED = new HashMap<>();
    /** Spawn-time views of maps without running logic; keyed by identity, never by the record. */
    private static final Map<LogicTable, LookState.View> INITIAL = new IdentityHashMap<>();

    public static void receive(LookSync.ViewPayload payload) {
        PropStates.Key key = new PropStates.Key(payload.dimension(), payload.anchor());
        if (payload.view() == null) RECEIVED.remove(key);
        else RECEIVED.put(key, new Received(payload.view(), payload.now(), System.nanoTime()));
    }

    /** Forgets everything, for leaving a world. */
    public static void clear() {
        RECEIVED.clear();
        INITIAL.clear();
        frame = null;
        fogController = -2;
    }

    /** Forgets the spawn-time views, for a new bundle generation's tables. */
    public static void clearTables() { INITIAL.clear(); }

    // ---- The camera's map, once per frame.

    /** One colour lookup and its weight for this frame. */
    public record Lookup(byte[] cells, float weight) {}

    /**
     * What this frame's view takes from the camera's map. {@code fogStart}/{@code fogEnd} in
     * blocks; {@code fogColor} gamma 0..1; {@code lookups} at most four, heaviest first;
     * {@code defaultWeight} the uncorrected image's share.
     */
    public record Frame(BundleMap map, LookTable look, boolean fog, float fogStart, float fogEnd, float fogMaxDensity,
                        float[] fogColor, float exposureMin, float exposureMax, boolean customBloom, float bloomScale,
                        float rate, List<Lookup> lookups, float defaultWeight) {}

    private static Frame frame;
    /** {@code /src2mc_look fog off}: Minecraft's fog everywhere again. */
    static boolean fogOn = true;
    /** The fog controller the player followed last frame; -2 before any. */
    private static int fogController = -2;
    /** {@code m_PlayerFog}'s transition between controllers. */
    private static int oldColor, newColor;
    private static float oldStart, oldEnd, newStart, newEnd;
    private static double transitionStart = -1;

    /** This frame's view, or null when the camera is in no map. */
    public static Frame frame() { return frame; }

    /** Works out this frame's view from where the camera is. */
    public static void update(Camera camera) {
        Minecraft mc = Minecraft.getInstance();
        frame = null;
        if (mc.level == null || mc.player == null) return;
        Vec3 eye = camera.getPosition();
        MapPlacement placement = PlacementNetwork.clientIndex(mc.level.dimension().location()).at(BlockPos.containing(eye)).orElse(null);
        if (placement == null) return;
        BundleMap map = Src2mc.bundles().active().findMap(placement.campaignId(), placement.mapId()).orElse(null);
        if (map == null) return;
        PropStates.Key key = new PropStates.Key(mc.level.dimension().location(), placement.anchorWorld().asLong());
        Received received = RECEIVED.get(key);
        LookState.View view;
        double mapTime;
        if (received != null) {
            view = received.view();
            mapTime = received.now() + (System.nanoTime() - received.arrived()) / 1e9;
        } else if (map.logic() != null) {
            view = INITIAL.computeIfAbsent(map.logic(), LookState::initial);
            mapTime = 0;
        } else {
            view = new LookState.View(List.of(), -1, LookState.DEFAULT_RATE, List.of(), -1, -1, List.of(), Map.of());
            mapTime = 0;
        }
        BlockPos t = placement.translation();
        Vec3 local = mc.player.position().subtract(t.getX(), t.getY(), t.getZ());
        LookState.Player controllers = view.of(mc.player.getUUID());

        // Tonemap: the 2013 client follows the controller changed last, the later branch the player's own.
        LookTable look = map.look();
        boolean laterBranch = look != null && look.bloomType() >= 0;
        LookState.Tonemap tonemap = view.tonemap(laterBranch ? controllers.tonemap() : view.tonemapInUse());
        float min = tonemap != null && tonemap.useMin() && tonemap.min() > 0 ? tonemap.min() : 0.5f;
        float max = tonemap != null && tonemap.useMax() && tonemap.max() > 0 ? tonemap.max() : 2.0f;
        if (min > max) max = min;
        boolean customBloom = tonemap != null && tonemap.useBloom();
        float bloom = customBloom ? tonemap.bloom() : 1.0f;

        // Fog.
        LookState.Fog fog = view.fog(controllers.fog());
        double now = System.nanoTime() / 1e9;
        if (fog == null) {
            fogController = -1;
        } else if (fog.entity() != fogController) {
            // C_BasePlayer::FogControllerChanged: blend from the fog as it was, unless there was none.
            boolean snap = fogController == -2;
            oldColor = newColor; oldStart = newStart; oldEnd = newEnd;
            transitionStart = snap ? -1 : now;
            fogController = fog.entity();
        }
        boolean fogOn = fog != null && fog.enable();
        float start = 0, end = 0, density = 1;
        float[] color = new float[3];
        if (fog != null) {
            newColor = fog.color(); newStart = fog.start(); newEnd = fog.end();
            int primary = fog.color();
            start = fog.start();
            end = fog.end();
            if (transitionStart >= 0) {
                double delta = now - transitionStart;
                if (delta < fog.duration()) {
                    float scale = (float) (delta / fog.duration());
                    primary = mix(oldColor, newColor, scale);
                    start = newStart * scale + oldStart * (1 - scale);
                    end = newEnd * scale + oldEnd * (1 - scale);
                } else {
                    transitionStart = -1;
                }
            }
            int secondary = fog.color2();
            // StartFogTransition: colours, start and end move to their targets until lerptime.
            if (fog.lerpTime() >= mapTime && fog.duration() > 0) {
                float percent = (float) (1 - (fog.lerpTime() - mapTime) / fog.duration());
                if (fog.colorLerp() != primary) primary = mix(primary, fog.colorLerp(), percent);
                if (fog.color2Lerp() != secondary) secondary = mix(secondary, fog.color2Lerp(), percent);
            }
            if (fog.lerpTime() > mapTime && fog.duration() > 0) {
                float percent = (float) (1 - (fog.lerpTime() - mapTime) / fog.duration());
                if (fog.start() != fog.startLerp()) start = fog.start() + (fog.startLerp() - fog.start()) * percent;
                if (fog.end() != fog.endLerp()) end = fog.end() + (fog.endLerp() - fog.end()) * percent;
            }
            float blend = 1;
            if (fog.blend()) {
                Vec3 forward = new Vec3(camera.getLookVector());
                double length = Math.sqrt(fog.dirX() * fog.dirX() + fog.dirY() * fog.dirY() + fog.dirZ() * fog.dirZ());
                double dot = length == 0 ? 0 : (forward.x * fog.dirX() + forward.y * fog.dirY() + forward.z * fog.dirZ()) / length;
                blend = (float) (0.5 * dot + 0.5);
            }
            for (int c = 0; c < 3; c++) {
                int shift = 16 - 8 * c;
                color[c] = (((primary >> shift) & 0xFF) * blend + ((secondary >> shift) & 0xFF) * (1 - blend)) / 255f;
            }
            density = fog.maxDensity();
        }

        // Colour correction.
        List<Lookup> weighted = new ArrayList<>();
        if (look != null) {
            for (LookState.Correction correction : view.corrections()) {
                byte[] cells = look.lookup(correction.file());
                if (cells == null || (!correction.enabled() && correction.weight() == 0)) continue;
                float falloff = 0;
                if (correction.minFalloff() != -1 && correction.maxFalloff() != -1 && correction.minFalloff() != correction.maxFalloff()) {
                    double dx = local.x - correction.x(), dy = local.y - correction.y(), dz = local.z - correction.z();
                    double distance = Math.sqrt(dx * dx + dy * dy + dz * dz) * UNITS_PER_BLOCK;
                    falloff = (float) Math.max(0, Math.min(1, (distance - correction.minFalloff()) / (correction.maxFalloff() - correction.minFalloff())));
                }
                float weight = correction.weight() * (1 - falloff);
                if (weight > 0) weighted.add(new Lookup(cells, weight));
            }
        }
        weighted.sort((a, b) -> Float.compare(b.weight(), a.weight()));
        if (weighted.size() > 4) weighted = new ArrayList<>(weighted.subList(0, 4));
        float sum = 0;
        for (Lookup lookup : weighted) sum += lookup.weight();
        float defaultWeight = 1 - sum;
        if (sum > 0.999f) {
            defaultWeight = 0;
            List<Lookup> normalized = new ArrayList<>(weighted.size());
            for (Lookup lookup : weighted) normalized.add(new Lookup(lookup.cells(), lookup.weight() / sum));
            weighted = normalized;
        }

        frame = new Frame(map, look, fogOn, start / (float) UNITS_PER_BLOCK, end / (float) UNITS_PER_BLOCK, density, color,
            min, max, customBloom, bloom, view.rate(), List.copyOf(weighted), defaultWeight);
    }

    // ---- Fog.

    /**
     * The map shaders' {@code SourceFog} (start, end in blocks, maximum density, mode) and
     * {@code SourceFogColor} (linear; the shader multiplies by the tone map scale, as
     * {@code ComputeGammaCorrectedFogColor} does). Mode 0: Minecraft's fog, outside a map or with
     * Source fog switched off; 1: the map's range fog; 2: the map has none.
     */
    public static void applyFog(ShaderInstance shader) {
        Frame f = frame;
        int mode = f == null || !fogOn ? 0 : f.fog() && f.fogEnd() != f.fogStart() ? 1 : 2;
        shader.safeGetUniform("SourceFog").set(mode == 0 ? 0 : f.fogStart(), mode == 0 ? 0 : f.fogEnd(),
            mode == 0 ? 0 : f.fogMaxDensity(), (float) mode);
        if (mode == 1) {
            float[] c = f.fogColor();
            shader.safeGetUniform("SourceFogColor").set((float) Math.pow(c[0], 2.2), (float) Math.pow(c[1], 2.2), (float) Math.pow(c[2], 2.2));
        }
    }

    /** Minecraft's own things (players, mobs, items) fog at the map's range too. */
    @SubscribeEvent
    static void onRenderFog(ViewportEvent.RenderFog event) {
        Frame f = frame;
        if (f == null || !fogOn || !f.fog() || event.getMode() != FogRenderer.FogMode.FOG_TERRAIN || event.getType() != FogType.NONE) return;
        event.setNearPlaneDistance(f.fogStart());
        event.setFarPlaneDistance(Math.max(f.fogEnd(), f.fogStart() + 1e-3f));
        event.setCanceled(true);
    }

    @SubscribeEvent
    static void onFogColor(ViewportEvent.ComputeFogColor event) {
        Frame f = frame;
        if (f == null || !fogOn || !f.fog()) return;
        event.setRed(f.fogColor()[0]);
        event.setGreen(f.fogColor()[1]);
        event.setBlue(f.fogColor()[2]);
    }

    private static int mix(int from, int to, float t) {
        int out = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            float a = (from >> shift) & 0xFF, b = (to >> shift) & 0xFF;
            out |= Math.max(0, Math.min(255, (int) (a + (b - a) * t))) << shift;
        }
        return out;
    }
}
