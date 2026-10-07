package dev.theredja.src2mc.client.render;

import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.bundle.LightTable;
import dev.theredja.src2mc.network.PlacementNetwork;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Lights an entity standing in a placed map -- the player, a mob, a dropped item -- by Source's
 * ambient cube at its position, as Source lights a model, instead of by Minecraft's sky. Minecraft
 * draws entities through its lightmap, which takes a light level, so the cube's brightness becomes
 * the entity's block light level (or Minecraft's own block light where a torch is brighter), with
 * no sky light; its colour tints a living entity's model. Outside every map nothing changes.
 */
public final class EntityLighting {
    private EntityLighting() {}

    /** The ambient cube around the entity, 18 floats; null outside every map or without baked light. */
    private static float[] cube(Entity entity, float partialTicks) {
        var level = entity.level();
        if (!level.isClientSide()) return null;
        Vec3 probe = entity.getLightProbePosition(partialTicks);
        var placement = PlacementNetwork.clientIndex(level.dimension().location()).at(BlockPos.containing(probe)).orElse(null);
        if (placement == null) return null;
        var map = Src2mc.bundles().active().findMap(placement.campaignId(), placement.mapId()).orElse(null);
        LightTable table = map == null ? null : map.light();
        if (table == null) return null;
        float[] cube = new float[18];
        BlockPos t = placement.translation();
        return table.ambientAt(probe.x - t.getX(), probe.y - t.getY(), probe.z - t.getZ(), cube) ? cube : null;
    }

    /** The cube's light averaged over its six sides, linear RGB, times the exposure. */
    private static float[] average(float[] cube) {
        float[] rgb = new float[3];
        for (int side = 0; side < 6; side++) for (int c = 0; c < 3; c++) rgb[c] += cube[side * 3 + c] / 6f;
        for (int c = 0; c < 3; c++) rgb[c] *= BakedLighting.exposure();
        return rgb;
    }

    /** Minecraft's packed light for an entity: the vanilla value outside a map. */
    public static int packedLight(Entity entity, float partialTicks, int vanilla) {
        float[] cube = cube(entity, partialTicks);
        if (cube == null) return vanilla;
        float[] rgb = average(cube);
        double luminance = 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2];
        // Linear light to the brightness it shows a white surface at, then to the level that
        // shows about that bright.
        double shown = Math.min(1.0, Math.pow(Math.max(0, luminance), 1 / 2.2));
        int level = (int) Math.round(shown * 15);
        return LightTexture.pack(Math.max(level, LightTexture.block(vanilla)), 0);
    }

    /** A living entity's model colour, ARGB, tinted by the cube's hue; unchanged outside a map. */
    public static int tint(Entity entity, float partialTicks, int color) {
        float[] cube = cube(entity, partialTicks);
        if (cube == null) return color;
        float[] rgb = average(cube);
        float max = Math.max(rgb[0], Math.max(rgb[1], rgb[2]));
        if (max <= 0) return color;
        int r = Math.round((color >> 16 & 255) * rgb[0] / max), g = Math.round((color >> 8 & 255) * rgb[1] / max);
        int b = Math.round((color & 255) * rgb[2] / max);
        return color & 0xFF000000 | r << 16 | g << 8 | b;
    }
}
