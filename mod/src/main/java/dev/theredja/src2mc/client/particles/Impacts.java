package dev.theredja.src2mc.client.particles;

import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.bundle.AudioTable;
import dev.theredja.src2mc.bundle.BundleMap;
import dev.theredja.src2mc.bundle.BundleMaterial;
import dev.theredja.src2mc.bundle.LightTable;
import dev.theredja.src2mc.bundle.ParticleTable;
import dev.theredja.src2mc.client.audio.SourceAudio;
import dev.theredja.src2mc.network.PlacementNetwork;
import dev.theredja.src2mc.world.ImpactNetwork;
import dev.theredja.src2mc.world.MapPlacement;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Draws a bullet impact on a map surface as the map's game does ({@code ImpactCallback}): the
 * surface's {@code $surfaceprop} names its game material and its {@code bulletimpact} sound; an
 * HL2-era game draws the SDK's code effects for that material
 * ({@link CodeEffects#impact}), a Portal 2-era one the material's {@code impact_*} particle system
 * with its control points as {@code PerformNewCustomEffects} sets them.
 */
public final class Impacts {
    private Impacts() {}

    static long impacts, drawn;
    private static long seeds = 0x51ED2701L;

    /** Runs on the client thread. */
    public static void apply(ImpactNetwork.ImpactPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        impacts++;
        Vec3 hit = new Vec3(payload.x(), payload.y(), payload.z());
        var face = payload.face();
        // The block hit is behind the face.
        BlockPos block = BlockPos.containing(hit.x - face.getStepX() * 1e-3, hit.y - face.getStepY() * 1e-3, hit.z - face.getStepZ() * 1e-3);
        var generation = Src2mc.bundles().active();
        for (MapPlacement placement : PlacementNetwork.clientIndex(minecraft.level.dimension().location()).view()) {
            if (!placement.contains(block)) continue;
            var located = generation.findLocatedMap(placement.campaignId(), placement.mapId()).orElse(null);
            if (located == null) continue;
            impact(placement, located.bundle(), located.map(), hit, block, face, new Vec3(payload.dx(), payload.dy(), payload.dz()), payload.bullet());
            return;
        }
    }

    private static void impact(MapPlacement placement, dev.theredja.src2mc.bundle.BundleManifest bundle, BundleMap map, Vec3 hit, BlockPos block,
                               net.minecraft.core.Direction face, Vec3 motion, boolean bullet) {
        var translation = placement.translation();
        int material = SourceAudio.surfaceMaterialAt(map.surfaces(), block.getX() - translation.getX(), block.getY() - translation.getY(),
            block.getZ() - translation.getZ());
        if (material < 0 || material >= map.materials().size()) return;
        BundleMaterial surfaceMaterial = map.materials().get(material);
        String surfaceProp = surfaceMaterial.surfaceProp();
        SourceAudio.playBulletImpact(placement, surfaceProp, hit);
        ParticleTable table = map.particles();
        if (table == null || map.logic() == null) return;
        char gameMaterial = 'C';
        if (map.audio() != null) {
            AudioTable.Surface surface = map.audio().surface(surfaceProp);
            if (surface != null && surface.gameMaterial() != 0) gameMaterial = surface.gameMaterial();
        }
        double[] origin = map.logic().sourceOrigin();
        Space space = new Space(translation.getX() + origin[0], translation.getY() + origin[1], translation.getZ() + origin[2]);
        double[] point = new double[3];
        space.toSource(hit.x, hit.y, hit.z, point);
        double[] normal = {face.getStepX(), -face.getStepZ(), face.getStepY()};
        double length = motion.length();
        double[] shot = length > 1e-6 ? new double[]{motion.x / length, -motion.z / length, motion.y / length} : new double[]{-normal[0], -normal[1], -normal[2]};
        int scale = 1;
        long seed = seeds++ * 0x9E3779B97F4A7C15L;
        // The game's ImpactTrace: the decal first, then the effect.
        dev.theredja.src2mc.client.render.DecalRenderer.shoot(placement, bundle, map, gameMaterial, hit.x, hit.y, hit.z,
            motion.x, motion.y, motion.z, new java.util.SplittableRandom(seed));
        if (table.impacts().style().equals("systems")) {
            Integer system = table.impacts().systems().get(gameMaterial);
            if (system == null) return;
            Effect effect = ParticleEffects.oneShot(placement, bundle, map, system, hit);
            if (effect == null) return;
            // SetImpactControlPoint: 0 faces out of the surface, 1 along the reflection, 2 back along the shot; 3 the scale.
            double dot = shot[0] * normal[0] + shot[1] * normal[1] + shot[2] * normal[2];
            effect.setForward(0, normal[0], normal[1], normal[2]);
            effect.setControlPoint(1, point[0], point[1], point[2]);
            effect.setForward(1, shot[0] - 2 * dot * normal[0], shot[1] - 2 * dot * normal[1], shot[2] - 2 * dot * normal[2]);
            effect.setControlPoint(2, point[0], point[1], point[2]);
            effect.setForward(2, -shot[0], -shot[1], -shot[2]);
            effect.setControlPoint(3, scale, scale, scale);
            drawn++;
            return;
        }
        float[] color = surfaceColor(map, surfaceMaterial, hit.x - translation.getX(), hit.y - translation.getY(), hit.z - translation.getZ(),
            face.getStepX(), face.getStepY(), face.getStepZ());
        List<Effect> effects = CodeEffects.impact(new CodeEffects.Context(table, space, table.impacts().materials()), gameMaterial, point, normal, shot,
            color, scale, seed);
        for (Effect effect : effects) {
            effect.sortX = hit.x; effect.sortY = hit.y; effect.sortZ = hit.z;
            ParticleEffects.addOneShot(effect, bundle, map);
        }
        if (!effects.isEmpty()) drawn++;
    }

    /**
     * {@code GetColorForSurface}: the material's average colour, brought to gamma, times the light
     * there -- here the map's baked ambient light at the point, facing out of the surface.
     */
    static float[] surfaceColor(BundleMap map, BundleMaterial material, double x, double y, double z, double nx, double ny, double nz) {
        double[] reflectivity = material.reflectivity();
        float[] light = {1, 1, 1};
        LightTable table = map.light();
        float[] cube = new float[18];
        if (table != null && table.ambientAt(x + nx * 0.1, y + ny * 0.1, z + nz * 0.1, cube)) LightTable.evaluate(cube, nx, ny, nz, light);
        float[] color = new float[3];
        for (int i = 0; i < 3; i++) {
            double base = reflectivity == null ? 0.5 : reflectivity[i];
            color[i] = (float) Math.min(1, Math.pow(Math.max(0, base), 1 / 2.2) * light[i]);
        }
        return color;
    }
}
