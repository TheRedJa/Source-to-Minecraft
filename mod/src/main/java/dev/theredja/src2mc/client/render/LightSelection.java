package dev.theredja.src2mc.client.render;

import dev.theredja.src2mc.bundle.WorldLightTable;
import dev.theredja.src2mc.bundle.WorldLightTable.Light;
import java.util.ArrayList;
import java.util.List;

/**
 * Which world lights light a prop locally, the way noclip.website's {@code LightCache} picks them
 * (the engine's own choice is closed source): the lights are ranked by their distance falloff
 * times the gray of their intensity and the best four stay local. The SDK says the model ambient
 * cube holds "ambient, and lights that aren't in locallight[]" (public/istudiorender.h:215,
 * {@code m_vecAmbientCube}), so every other
 * light in range is folded into the prop's cube rather than dropped as noclip does. The skylight
 * has no visibility test in noclip; here it counts with the weight the sun visibility gives it,
 * and not at all at 0. Positions are map-local blocks, so a distance is in blocks; noclip's
 * Source-unit falloffs are converted where they depend on it.
 */
final class LightSelection {
    private LightSelection() {}

    static final int MAX_LOCAL = 4;
    private static final double UNITS_PER_BLOCK = 32.0;
    /** noclip's {@code ntscGrayscale}. */
    private static final float[] GRAY = {0.299f, 0.587f, 0.114f};
    /** The cube sides, as the ambient cube lists them: +X, -X, +Y, -Y, +Z, -Z. */
    private static final float[][] AXES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    /**
     * The local lights of one prop, best first, {@code count} of them, each with the weight its
     * intensity counts by (1, or the sun visibility for the skylight).
     */
    record Result(Light[] lights, float[] weights, int count) {
        static final Result NONE = new Result(new Light[MAX_LOCAL], new float[MAX_LOCAL], 0);
    }

    /**
     * The lights that can be local at all: always on (style 0), and not the sky ambient, which is
     * only a colour. Lights in the leaf's cube are skipped per query, where the cube is known.
     */
    static List<Light> eligible(WorldLightTable table) {
        List<Light> lights = new ArrayList<>();
        if (table == null) return lights;
        for (Light light : table.lights()) {
            if (isEligible(light)) lights.add(light);
        }
        return lights;
    }

    /** The world light table index of each light {@link #eligible} returns, in its order. */
    static int[] eligibleIndices(WorldLightTable table) {
        if (table == null) return new int[0];
        int[] indices = new int[table.lights().size()];
        int n = 0;
        for (int i = 0; i < indices.length; i++) if (isEligible(table.lights().get(i))) indices[n++] = i;
        return java.util.Arrays.copyOf(indices, n);
    }

    private static boolean isEligible(Light light) {
        return light.style() == 0 && light.type() != WorldLightTable.TYPE_SKY_AMBIENT;
    }

    /**
     * Selects the local lights for a point and folds all the others in range into {@code cube} (18
     * floats). With {@code leafCube} false the point has no leaf ambient light and the lights
     * flagged as already in the cube are considered too. {@code sun} is the sun visibility (0 to 1)
     * the skylight is weighted by.
     */
    static Result select(LightGrid grid, boolean leafCube, double x, double y, double z, float sun, float[] cube) {
        return select(grid, leafCube, x, y, z, sun, null, cube);
    }

    /** {@link #select} with {@code vis} deciding how much of each non-sky light reaches the point (null: all of it). */
    static Result select(LightGrid grid, boolean leafCube, double x, double y, double z, float sun, LightVisibility vis, float[] cube) {
        Light[] slots = new Light[MAX_LOCAL];
        float[] weights = new float[MAX_LOCAL];
        int count = select(grid, leafCube, x, y, z, sun, vis, cube, slots, new float[MAX_LOCAL], weights);
        return count == 0 ? Result.NONE : new Result(slots, weights, count);
    }

    /**
     * {@link #select} into caller-owned scratch ({@code slots}, {@code ranks} and {@code weights},
     * {@link #MAX_LOCAL} long each, overwritten), for the moving props; returns how many of
     * {@code slots} are set.
     */
    static int select(LightGrid grid, boolean leafCube, double x, double y, double z, float sun, LightVisibility vis, float[] cube,
                      Light[] slots, float[] ranks, float[] weights) {
        java.util.Arrays.fill(slots, null);
        java.util.Arrays.fill(ranks, 0f);
        java.util.Arrays.fill(weights, 0f);
        for (int always : grid.always) consider(grid.lights.get(always), grid.worldIndex(always), vis, leafCube, x, y, z, sun, cube, slots, ranks, weights);
        int[] items = grid.items();
        for (int n = grid.cellStart(x, y, z), end = grid.cellEnd(x, y, z); n < end; n++) {
            consider(grid.lights.get(items[n]), grid.worldIndex(items[n]), vis, leafCube, x, y, z, sun, cube, slots, ranks, weights);
        }
        int count = 0;
        while (count < MAX_LOCAL && slots[count] != null) count++;
        return count;
    }

    private static void consider(Light light, int worldIndex, LightVisibility vis, boolean leafCube, double x, double y, double z, float sun, float[] cube,
                                 Light[] slots, float[] ranks, float[] weights) {
        if (leafCube && (light.flags() & WorldLightTable.FLAG_IN_AMBIENT_CUBE) != 0) return;
        float weight = 1f;
        if (light.type() == WorldLightTable.TYPE_SKYLIGHT) {
            weight = sun;
            if (!(weight > 0f)) return;
        }
        double dx = light.origin()[0] - x, dy = light.origin()[1] - y, dz = light.origin()[2] - z;
        // Out of range falls to 0 below anyway; skipped before the square root and the gray.
        if (light.type() != WorldLightTable.TYPE_SKYLIGHT && light.type() != WorldLightTable.TYPE_QUAKE
            && dx * dx + dy * dy + dz * dz > (double) light.radius() * light.radius()) return;
        if (vis != null && light.type() != WorldLightTable.TYPE_SKYLIGHT) {
            // Behind a wall, in the cube as much as in a slot: the visible share scales intensity and rank.
            weight = vis.weight(worldIndex);
            if (!(weight > 0f)) return;
        }
        float rank = (float) (distanceFalloff(light, dx, dy, dz) * weight
            * (light.intensity()[0] * GRAY[0] + light.intensity()[1] * GRAY[1] + light.intensity()[2] * GRAY[2]));
        if (!(rank > 0f)) return;
        for (int j = 0; j < MAX_LOCAL; j++) {
            if (rank <= ranks[j]) continue;
            // A better light than this slot's: make room, and the one pushed out joins the cube.
            Light ejected = slots[MAX_LOCAL - 1];
            if (ejected != null) addToCube(cube, ejected, x, y, z, weights[MAX_LOCAL - 1]);
            for (int k = MAX_LOCAL - 1; k > j; k--) {
                if (slots[k - 1] != null) { slots[k] = slots[k - 1]; ranks[k] = ranks[k - 1]; weights[k] = weights[k - 1]; }
            }
            slots[j] = light;
            ranks[j] = rank;
            weights[j] = weight;
            return;
        }
        // Not among the best four: it lights the prop through the cube.
        addToCube(cube, light, x, y, z, weight);
    }

    /** noclip's {@code worldLightDistanceFalloff} for a vector from the point to the light, in blocks. */
    static double distanceFalloff(Light light, double dx, double dy, double dz) {
        double squared = dx * dx + dy * dy + dz * dz;
        switch (light.type()) {
            case WorldLightTable.TYPE_SURFACE:
                if (squared > (double) light.radius() * light.radius()) return 0.0;
                return 1.0 / Math.max(1.0, squared * UNITS_PER_BLOCK * UNITS_PER_BLOCK);
            case WorldLightTable.TYPE_POINT:
            case WorldLightTable.TYPE_SPOT: {
                if (squared > (double) light.radius() * light.radius()) return 0.0;
                double distance = Math.sqrt(squared);
                return 1.0 / (light.constant() + distance * light.linear() + squared * light.quadratic());
            }
            case WorldLightTable.TYPE_SKYLIGHT:
                // noclip's rank for it, scaled by the sun visibility where it is selected.
                return 0.1;
            case WorldLightTable.TYPE_QUAKE:
                // noclip: linear attenuation minus the distance, both in Source units (the exporter
                // stored the linear term times 32 per block).
                return Math.max(0.0, light.linear() / UNITS_PER_BLOCK - Math.sqrt(squared) * UNITS_PER_BLOCK);
            default:
                return 0.0;
        }
    }

    /**
     * noclip's {@code addWorldLightToAmbientCube}: the light as seen from the point adds to each
     * side by its direction along it. A skylight is added from its own direction at full strength,
     * not from its entity's position at the rank's tenth that noclip uses.
     */
    static void addToCube(float[] cube, Light light, double x, double y, double z) {
        addToCube(cube, light, x, y, z, 1f);
    }

    /** {@link #addToCube} with the light's intensity scaled by {@code scale} (the sun visibility of a skylight). */
    static void addToCube(float[] cube, Light light, double x, double y, double z, float scale) {
        double dx = light.origin()[0] - x, dy = light.origin()[1] - y, dz = light.origin()[2] - z;
        double ratio = distanceFalloff(light, dx, dy, dz);
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (light.type() == WorldLightTable.TYPE_SKYLIGHT) {
            dx = -light.direction()[0]; dy = -light.direction()[1]; dz = -light.direction()[2];
            ratio = 1.0;
        } else if (length > 1.0e-9) {
            dx /= length; dy /= length; dz /= length;
        }
        double angular = angleFalloff(light, dx, dy, dz);
        for (int side = 0; side < 6; side++) {
            double mul = (dx * AXES[side][0] + dy * AXES[side][1] + dz * AXES[side][2]) * ratio * angular * scale;
            if (mul <= 0.0) continue;
            for (int c = 0; c < 3; c++) cube[side * 3 + c] += (float) (light.intensity()[c] * mul);
        }
    }

    /**
     * noclip's {@code worldLightAngleFalloff} for a surface facing the light, {@code (dx, dy, dz)}
     * the unit direction to it.
     */
    static double angleFalloff(Light light, double dx, double dy, double dz) {
        double towards = -(dx * light.direction()[0] + dy * light.direction()[1] + dz * light.direction()[2]);
        switch (light.type()) {
            case WorldLightTable.TYPE_SURFACE:
                return towards <= 0.0 ? 0.0 : towards;
            case WorldLightTable.TYPE_POINT:
            case WorldLightTable.TYPE_QUAKE:
                return 1.0;
            case WorldLightTable.TYPE_SPOT:
                if (towards <= light.stopdot2()) return 0.0;
                if (towards >= light.stopdot() || !(light.stopdot() > light.stopdot2())) return 1.0;
                return Math.pow((towards - light.stopdot2()) / (light.stopdot() - light.stopdot2()), light.exponent());
            case WorldLightTable.TYPE_SKYLIGHT:
                return towards <= 0.0 ? 0.0 : towards;
            default:
                return 1.0;
        }
    }

    /**
     * What a light adds to a surface facing {@code (nx, ny, nz)} at a point, linear RGB: its
     * intensity, distance and cone falloff, and Lambert (or half-Lambert squared) of the normal
     * against the direction to it, as the baked shader computes per pixel.
     */
    static void lambert(Light light, double x, double y, double z, double nx, double ny, double nz, boolean half, float[] rgb) {
        double dx, dy, dz, atten;
        if (light.type() == WorldLightTable.TYPE_SKYLIGHT) {
            dx = -light.direction()[0]; dy = -light.direction()[1]; dz = -light.direction()[2];
            atten = 1.0;
        } else {
            dx = light.origin()[0] - x; dy = light.origin()[1] - y; dz = light.origin()[2] - z;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance > light.radius()) return;
            if (distance > 1.0e-6) { dx /= distance; dy /= distance; dz /= distance; }
            atten = shadingAttenuation(light, distance, -(dx * light.direction()[0] + dy * light.direction()[1] + dz * light.direction()[2]));
        }
        double ndl = nx * dx + ny * dy + nz * dz;
        double term = half ? square(Math.max(0.0, Math.min(1.0, ndl * 0.5 + 0.5))) : Math.max(0.0, Math.min(1.0, ndl));
        for (int c = 0; c < 3; c++) rgb[c] += (float) (light.intensity()[c] * atten * term);
    }

    /**
     * The per-pixel attenuation of a point, spot, quake or surface light at {@code distance}
     * blocks, {@code cos} the cosine between its axis and the direction from it to the point.
     */
    static double shadingAttenuation(Light light, double distance, double cos) {
        switch (light.type()) {
            case WorldLightTable.TYPE_SURFACE:
                return 1.0 / Math.max(1.0, distance * distance * UNITS_PER_BLOCK * UNITS_PER_BLOCK) * Math.max(0.0, cos);
            case WorldLightTable.TYPE_QUAKE: {
                // Linear falloff to zero at its range, which is the stored linear term in blocks
                // (Source units / 32); the ranking uses the same range in Source units.
                double range = light.linear() / (UNITS_PER_BLOCK * UNITS_PER_BLOCK);
                return range > 0.0 ? Math.max(0.0, 1.0 - distance / range) : 0.0;
            }
            case WorldLightTable.TYPE_SPOT: {
                if (!(light.stopdot() > light.stopdot2())) return cos >= light.stopdot() ? light.attenuation((float) distance) : 0.0;
                double t = (cos - light.stopdot2()) / (light.stopdot() - light.stopdot2());
                t = Math.max(0.0, Math.min(1.0, t));
                return t > 0.0 ? light.attenuation((float) distance) * Math.pow(t, light.exponent()) : 0.0;
            }
            default:
                return light.attenuation((float) distance);
        }
    }

    /** Source's three-range Fresnel ({@code Fresnel(N, V, ranges)}) of {@code n.v}. */
    static float fresnel(float nDotV, float[] ranges) {
        float f = 1f - Math.max(0f, Math.min(1f, nDotV));
        f *= f;
        return f > 0.5f ? ranges[1] + (ranges[2] - ranges[1]) * (2f * f - 1f) : ranges[0] + (ranges[1] - ranges[0]) * 2f * f;
    }

    private static double square(double v) { return v * v; }
}
