package dev.theredja.src2mc.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.theredja.src2mc.bundle.WorldLightTable;
import dev.theredja.src2mc.bundle.WorldLightTable.Light;
import java.util.List;
import org.junit.jupiter.api.Test;

final class LightSelectionTest {
    private static Light point(float x, float y, float z, float gray, int flags, int style) {
        return new Light(WorldLightTable.TYPE_POINT, flags, style, new float[] {x, y, z}, new float[] {gray, gray, gray},
            new float[3], 0, 0, 1, 100, 0, 0, 1);
    }

    private static LightSelection.Result sel(List<Light> lights, boolean leaf, double x, double y, double z, float[] cube) {
        return LightSelection.select(new LightGrid(lights), leaf, x, y, z, 1f, cube);
    }

    private static Light sun(float intensity) {
        return new Light(WorldLightTable.TYPE_SKYLIGHT, 0, 0, new float[] {900, 900, 900}, new float[] {intensity, intensity, intensity},
            new float[] {0, -1, 0}, 0, 0, 1, 100, 0, 0, 1);
    }

    @Test
    void ranksByFalloffTimesGrayAndKeepsTheBestFour() {
        // Intensity 1 at 1..6 blocks: falloff 1/d^2, so the nearest four stay.
        List<Light> lights = List.of(point(5, 0, 0, 1, 0, 0), point(2, 0, 0, 1, 0, 0), point(6, 0, 0, 1, 0, 0),
            point(1, 0, 0, 1, 0, 0), point(4, 0, 0, 1, 0, 0), point(3, 0, 0, 1, 0, 0));
        float[] cube = new float[18];
        LightSelection.Result result = sel(lights, true, 0, 0, 0, cube);
        assertEquals(4, result.count());
        assertEquals(1f, result.lights()[0].origin()[0]);
        assertEquals(2f, result.lights()[1].origin()[0]);
        assertEquals(3f, result.lights()[2].origin()[0]);
        assertEquals(4f, result.lights()[3].origin()[0]);
    }

    @Test
    void aLightPushedOutJoinsTheAmbientCubeFromItsDirection() {
        List<Light> lights = List.of(point(4, 0, 0, 1, 0, 0), point(1, 0, 0, 1, 0, 0), point(2, 0, 0, 1, 0, 0),
            point(3, 0, 0, 1, 0, 0), point(0, 0.5f, 0, 1, 0, 0));
        float[] cube = new float[18];
        LightSelection.Result result = sel(lights, true, 0, 0, 0, cube);
        assertEquals(4, result.count());
        // The light 4 blocks along +X was pushed out: 1 / 16 on the +X side, nothing on the others.
        assertEquals(1f / 16f, cube[0], 1e-6f);
        assertEquals(1f / 16f, cube[2], 1e-6f);
        assertEquals(0f, cube[3]);
        assertEquals(0f, cube[6]);
    }

    @Test
    void skipsStyledLightsThoseInTheCubeAndSkyAmbient() {
        Light styled = point(1, 0, 0, 1, 0, 5);
        Light inCube = point(1, 0, 0, 1, WorldLightTable.FLAG_IN_AMBIENT_CUBE, 0);
        Light skyAmbient = new Light(WorldLightTable.TYPE_SKY_AMBIENT, 0, 0, new float[3], new float[] {1, 1, 1}, new float[3], 0, 0, 1, 1, 0, 0, 1);
        List<Light> eligible = LightSelection.eligible(new WorldLightTable(List.of(styled, inCube, skyAmbient)));
        assertEquals(List.of(inCube), eligible);
        assertEquals(0, sel(eligible, true, 0, 0, 0, new float[18]).count());
        assertEquals(1, sel(eligible, false, 0, 0, 0, new float[18]).count());
    }

    @Test
    void outOfRangeLightsAreNotRanked() {
        Light far = new Light(WorldLightTable.TYPE_POINT, 0, 0, new float[] {50, 0, 0}, new float[] {1, 1, 1}, new float[3], 0, 0, 1, 10, 0, 0, 1);
        assertEquals(0, sel(List.of(far), true, 0, 0, 0, new float[18]).count());
        assertSame(LightSelection.Result.NONE, sel(List.of(), true, 0, 0, 0, new float[18]));
    }

    @Test
    void skylightRanksLowAndFoldsFromItsOwnDirection() {
        Light sun = new Light(WorldLightTable.TYPE_SKYLIGHT, 0, 0, new float[] {900, 900, 900}, new float[] {2, 2, 2},
            new float[] {0, -1, 0}, 0, 0, 1, 100, 0, 0, 1);
        assertEquals(0.1, LightSelection.distanceFalloff(sun, 1, 2, 3), 1e-9);
        float[] cube = new float[18];
        LightSelection.addToCube(cube, sun, 0, 0, 0);
        assertEquals(2f, cube[6], 1e-6f);  // +Y side: the light comes from above
        assertEquals(0f, cube[9]);
        assertEquals(0f, cube[0]);
    }

    @Test
    void distanceFalloffMatchesNoclipPerType() {
        Light p = point(0, 0, 0, 1, 0, 0);
        assertEquals(1.0 / 4.0, LightSelection.distanceFalloff(p, 2, 0, 0), 1e-9);
        Light surface = new Light(WorldLightTable.TYPE_SURFACE, 0, 0, new float[3], new float[] {1, 1, 1}, new float[] {0, 1, 0}, 0, 0, 1, 100, 0, 0, 1);
        // 1 / max(1, d^2) in Source units: 2 blocks is 64 units.
        assertEquals(1.0 / 4096.0, LightSelection.distanceFalloff(surface, 2, 0, 0), 1e-12);
        assertEquals(1.0, LightSelection.distanceFalloff(surface, 0.01, 0, 0), 1e-9);
        Light quake = new Light(WorldLightTable.TYPE_QUAKE, 0, 0, new float[3], new float[] {1, 1, 1}, new float[3], 0, 0, 1, 100, 0, 320, 0);
        assertEquals(10.0 - 32.0 * 0.25, LightSelection.distanceFalloff(quake, 0.25, 0, 0), 1e-9);
    }

    @Test
    void aLightBelowAllFourSlotsIsFoldedIntoTheCube() {
        // The SDK's ambient cube holds the lights that are not in locallight[]: the fifth joins it.
        List<Light> lights = List.of(point(1, 0, 0, 1, 0, 0), point(2, 0, 0, 1, 0, 0), point(3, 0, 0, 1, 0, 0),
            point(4, 0, 0, 1, 0, 0), point(0, 0, 5, 1, 0, 0));
        float[] cube = new float[18];
        LightSelection.Result result = sel(lights, true, 0, 0, 0, cube);
        assertEquals(4, result.count());
        assertEquals(1f / 25f, cube[12], 1e-6f);  // +Z side, 1 / 5^2
        assertEquals(0f, cube[15]);
        assertEquals(0f, cube[0]);
        // Out of range lights are still not folded.
        Light far = new Light(WorldLightTable.TYPE_POINT, 0, 0, new float[] {0, 0, 50}, new float[] {1, 1, 1}, new float[3], 0, 0, 1, 10, 0, 0, 1);
        float[] other = new float[18];
        sel(List.of(far), true, 0, 0, 0, other);
        for (float side : other) assertEquals(0f, side);
    }

    @Test
    void skylightCountsByItsSunVisibilityAndNotAtAllWithoutIt() {
        LightGrid grid = new LightGrid(List.of(sun(2)));
        LightSelection.Result lit = LightSelection.select(grid, true, 0, 0, 0, 0.5f, new float[18]);
        assertEquals(1, lit.count());
        assertEquals(0.5f, lit.weights()[0]);
        assertEquals(0, LightSelection.select(grid, true, 0, 0, 0, 0f, new float[18]).count());
        // Pushed out of the best four, it folds into the cube scaled by the visibility.
        List<Light> lights = List.of(point(1, 0, 0, 5, 0, 0), point(2, 0, 0, 5, 0, 0), point(3, 0, 0, 5, 0, 0), point(4, 0, 0, 5, 0, 0), sun(2));
        float[] cube = new float[18];
        LightSelection.select(new LightGrid(lights), true, 0, 0, 0, 0.5f, cube);
        assertEquals(1f, cube[6], 1e-6f);  // 2 * 0.5 on the +Y side
    }

    @Test
    void gridSelectionMatchesTheBruteForceOne() {
        java.util.Random random = new java.util.Random(7);
        java.util.ArrayList<Light> lights = new java.util.ArrayList<>();
        for (int i = 0; i < 300; i++) {
            lights.add(new Light(WorldLightTable.TYPE_POINT, 0, 0,
                new float[] {random.nextFloat() * 200, random.nextFloat() * 40, random.nextFloat() * 200},
                new float[] {1 + random.nextFloat(), 1, 1}, new float[3], 0, 0, 1, 5 + random.nextFloat() * 30, 0, 0, 1));
        }
        lights.add(sun(1));
        LightGrid grid = new LightGrid(lights);
        for (int n = 0; n < 200; n++) {
            double x = random.nextDouble() * 220 - 10, y = random.nextDouble() * 50 - 5, z = random.nextDouble() * 220 - 10;
            float[] cube = new float[18], reference = new float[18];
            LightSelection.Result got = LightSelection.select(grid, true, x, y, z, 1f, cube);
            // The plain scan: rank every light in range, the best four are local, the rest fold.
            final double fx = x, fy = y, fz = z;
            java.util.function.ToDoubleFunction<Light> rank = light -> {
                double dx = light.origin()[0] - fx, dy = light.origin()[1] - fy, dz = light.origin()[2] - fz;
                if (light.type() != WorldLightTable.TYPE_SKYLIGHT && dx * dx + dy * dy + dz * dz > (double) light.radius() * light.radius()) return 0;
                return LightSelection.distanceFalloff(light, dx, dy, dz) * light.intensity()[0] * 0.299 + LightSelection.distanceFalloff(light, dx, dy, dz) * (light.intensity()[1] * 0.587 + light.intensity()[2] * 0.114);
            };
            List<Light> ranked = lights.stream().filter(light -> rank.applyAsDouble(light) > 0)
                .sorted((p1, p2) -> Double.compare(rank.applyAsDouble(p2), rank.applyAsDouble(p1))).toList();
            assertEquals(Math.min(4, ranked.size()), got.count());
            for (int i = 0; i < got.count(); i++) assertSame(ranked.get(i), got.lights()[i]);
            for (int i = 4; i < ranked.size(); i++) LightSelection.addToCube(reference, ranked.get(i), x, y, z);
            org.junit.jupiter.api.Assertions.assertArrayEquals(reference, cube, 1e-4f);
        }
    }

    @Test
    void nanRankIsSkipped() {
        Light nan = new Light(WorldLightTable.TYPE_POINT, 0, 0, new float[3], new float[] {Float.NaN, 1, 1},
            new float[3], 0, 0, 1, 100, 0, 0, 1);
        assertEquals(0, sel(List.of(nan), true, 1, 0, 0, new float[18]).count());
    }

    @Test
    void quakeShadingFallsLinearlyToTheRankingsRange() {
        Light quake = new Light(WorldLightTable.TYPE_QUAKE, 0, 0, new float[3], new float[] {1, 1, 1}, new float[3], 0, 0, 1, 100, 0, 320, 0);
        // Range 320 / 32 = 10 Source units = 0.3125 blocks, where the ranking's falloff reaches 0.
        assertEquals(0.0, LightSelection.distanceFalloff(quake, 0.3125, 0, 0), 1e-9);
        assertEquals(0.2, LightSelection.shadingAttenuation(quake, 0.25, 1.0), 1e-9);
        assertEquals(0.0, LightSelection.shadingAttenuation(quake, 0.3125, 1.0), 1e-9);
    }

    @Test
    void shadingAttenuationAppliesTheCone() {
        Light spot = new Light(WorldLightTable.TYPE_SPOT, 0, 0, new float[3], new float[] {1, 1, 1}, new float[] {0, -1, 0}, 0.9f, 0.5f, 2, 100, 1, 0, 0);
        assertEquals(1.0, LightSelection.shadingAttenuation(spot, 3, 1.0), 1e-9);
        assertEquals(0.0, LightSelection.shadingAttenuation(spot, 3, 0.4), 1e-9);
        assertEquals(0.25, LightSelection.shadingAttenuation(spot, 3, 0.7), 1e-6);
    }

    @Test
    void lambertAndHalfLambertLightASurface() {
        Light p = point(0, 2, 0, 3, 0, 0);
        float[] rgb = new float[3];
        LightSelection.lambert(p, 0, 0, 0, 0, 1, 0, false, rgb);
        assertEquals(3f / 4f, rgb[0], 1e-6f);
        rgb = new float[3];
        LightSelection.lambert(p, 0, 0, 0, 0, -1, 0, false, rgb);
        assertEquals(0f, rgb[0]);
        rgb = new float[3];
        LightSelection.lambert(p, 0, 0, 0, 0, -1, 0, true, rgb);
        assertEquals(0f, rgb[0], 1e-6f);
        rgb = new float[3];
        LightSelection.lambert(p, 0, 0, 0, 1, 0, 0, true, rgb); // grazing: t = 0.5, squared 0.25
        assertEquals(3f / 4f * 0.25f, rgb[0], 1e-6f);
    }

    @Test
    void fresnelBlendsThreeRanges() {
        float[] ranges = {0.2f, 0.5f, 1f};
        assertEquals(0.2f, LightSelection.fresnel(1f, ranges), 1e-6f);   // facing: f 0
        assertEquals(1f, LightSelection.fresnel(0f, ranges), 1e-6f);     // edge-on: f 1
        // n.v 0.2929: (1 - 0.2929)^2 = 0.5, the mid value
        assertEquals(0.5f, LightSelection.fresnel(1f - (float) Math.sqrt(0.5), ranges), 1e-5f);
        assertTrue(LightSelection.fresnel(0.5f, ranges) < 0.5f);
    }
}
