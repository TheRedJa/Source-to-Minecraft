package dev.theredja.src2mc.client.particles;

import static org.junit.jupiter.api.Assertions.*;

import dev.theredja.src2mc.bundle.BundleMap;
import dev.theredja.src2mc.bundle.BundleValidator;
import dev.theredja.src2mc.bundle.ParticleTable;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ParticleSimulationTest {
    @BeforeAll static void noWorld() {
        Effect.world = new Effect.World() {
            @Override public boolean trace(Effect effect, double sx, double sy, double sz, double ex, double ey, double ez, double[] out) {
                // A floor at Source z = 0.
                if (sz < 0 || ez >= 0) return false;
                double t = sz / (sz - ez);
                out[0] = sx + (ex - sx) * t; out[1] = sy + (ey - sy) * t; out[2] = 0;
                out[3] = 0; out[4] = 0; out[5] = 1; out[6] = t;
                return true;
            }
            @Override public double[] playerEye(Effect effect) { return new double[]{0, 0, 64}; }
        };
    }

    private static ParticleTable.Params params(Object... pairs) {
        Map<String, Object> values = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            Object v = pairs[i + 1];
            values.put((String) pairs[i], v instanceof Number n ? new double[]{n.doubleValue()} : v instanceof double[] d ? d : v);
        }
        return new ParticleTable.Params(values);
    }

    private static ParticleTable.Function fn(String name, Object... pairs) { return new ParticleTable.Function(name, params(pairs)); }

    @Test void localSpeedYRunsAlongTheControlPointsRight() {
        // INFRA's furnace pipe flames: angles 0 270 -90, local speed (0, 90..140, 0). Right is +Z there,
        // and C_INIT_CreateWithinSphere adds y * right, so the flames rise.
        ParticleTable.System system = new ParticleTable.System("flames", -1, params("max_particles", 100),
            List.of(fn("render_animated_sprites")),
            List.of(fn("Movement Basic"), fn("Lifespan Decay")),
            List.of(fn("Lifetime Random", "lifetime_min", 1, "lifetime_max", 1),
                fn("Position Within Sphere Random", "speed_in_local_coordinate_system_min", new double[]{0, 90, 0},
                    "speed_in_local_coordinate_system_max", new double[]{0, 140, 0})),
            List.of(fn("emit_continuously", "emission_rate", 20)), List.of(), List.of(), List.of());
        ParticleTable table = new ParticleTable(List.of(system), List.of(), new ParticleTable.Impacts("code", Map.of(), Map.of()));
        Effect effect = new Effect(table, 0, new Space(0, 0, 0), 1);
        effect.setOrigin(0, 0, 100, 0, 270, -90);
        effect.simulate(0.5F);
        ParticleSystem s = effect.root;
        assertTrue(s.count > 0);
        float highest = 0;
        for (int q = 0; q < s.count; q++) highest = Math.max(highest, s.z[q]);
        assertTrue(highest > 130, "the oldest have risen: " + highest);
        for (int q = 0; q < s.count; q++) {
            assertTrue(s.z[q] >= 100, "rises: " + s.z[q]);
            assertEquals(0, s.x[q], 1e-3);
            assertEquals(0, s.y[q], 1e-3);
        }
    }

    @Test void dragIsPerThirtiethOfASecondNotPerFrame() {
        // C_OP_BasicMovement damps by (1 - drag)^(30 dt): the distance covered does not depend on the frame rate.
        float[] heights = new float[2];
        float[] steps = {1 / 30F, 1 / 240F};
        for (int run = 0; run < 2; run++) {
            ParticleTable.System system = new ParticleTable.System("rise", -1, params("max_particles", 1, "initial_particles", 1),
                List.of(fn("render_animated_sprites")), List.of(fn("Movement Basic", "drag", 0.02)),
                List.of(fn("Position Within Sphere Random", "speed_in_local_coordinate_system_min", new double[]{0, 0, 100},
                    "speed_in_local_coordinate_system_max", new double[]{0, 0, 100})),
                List.of(), List.of(), List.of(), List.of());
            ParticleTable table = new ParticleTable(List.of(system), List.of(), new ParticleTable.Impacts("code", Map.of(), Map.of()));
            Effect effect = new Effect(table, 0, new Space(0, 0, 0), 1);
            effect.setOrigin(0, 0, 0, 0, 0, 0);
            for (float t = 0; t < 1; t += steps[run]) effect.simulate(steps[run]);
            heights[run] = effect.root.z[0];
        }
        // 100 units a second, 0.98 kept per thirtieth: about 78 units in the first second.
        assertEquals(heights[0], heights[1], heights[0] * 0.05, "30 fps " + heights[0] + " vs 240 fps " + heights[1]);
        assertTrue(heights[1] > 70, "240 fps: " + heights[1]);
    }

    @Test void unevenFramesDoNotLaunchParticlesTooFast() {
        // A short frame then a long one: a particle born in the long frame keeps the speed it was given.
        ParticleTable.System system = new ParticleTable.System("burst", -1, params("max_particles", 1000),
            List.of(fn("render_animated_sprites")), List.of(fn("Movement Basic")),
            List.of(fn("Position Within Sphere Random", "speed_in_local_coordinate_system_min", new double[]{0, 0, 100},
                "speed_in_local_coordinate_system_max", new double[]{0, 0, 100})),
            List.of(fn("emit_continuously", "emission_rate", 200)), List.of(), List.of(), List.of());
        ParticleTable table = new ParticleTable(List.of(system), List.of(), new ParticleTable.Impacts("code", Map.of(), Map.of()));
        Effect effect = new Effect(table, 0, new Space(0, 0, 0), 1);
        effect.setOrigin(0, 0, 0, 0, 0, 0);
        for (int i = 0; i < 20; i++) { effect.simulate(0.002F); effect.simulate(0.05F); }
        ParticleSystem s = effect.root;
        // Nothing is older than the run, so nothing can be above 100 units a second times its length.
        float total = 20 * 0.052F, highest = 0;
        for (int q = 0; q < s.count; q++) highest = Math.max(highest, s.z[q]);
        assertTrue(highest <= 100 * total + 1, "highest " + highest + " after " + total + " s");
    }

    @Test void aContinuousEmitterFillsAndFallsUnderGravity() {
        ParticleTable.System system = new ParticleTable.System("test", -1, params("max_particles", 100),
            List.of(fn("render_animated_sprites")),
            List.of(fn("Movement Basic", "gravity", new double[]{0, 0, -800}), fn("Lifespan Decay")),
            List.of(fn("Lifetime Random", "lifetime_min", 1, "lifetime_max", 1), fn("Position Within Sphere Random", "distance_max", 4)),
            List.of(fn("emit_continuously", "emission_rate", 10)), List.of(), List.of(), List.of());
        ParticleTable table = new ParticleTable(List.of(system), List.of(), new ParticleTable.Impacts("code", Map.of(), Map.of()));
        Effect effect = new Effect(table, 0, new Space(0, 0, 0), 1);
        effect.setOrigin(0, 0, 100, 0, 0, 0);
        effect.simulate(0.5F);
        int early = effect.particles();
        assertTrue(early >= 4 && early <= 6, "half a second at 10 a second: " + early);
        effect.simulate(2);
        // A second of life each: about ten alive at a time.
        assertTrue(effect.particles() >= 8 && effect.particles() <= 12, "steady: " + effect.particles());
        ParticleSystem s = effect.root;
        float lowest = Float.MAX_VALUE;
        for (int q = 0; q < s.count; q++) lowest = Math.min(lowest, s.z[q]);
        assertTrue(lowest < 100 - 100, "the oldest have fallen: " + lowest);
        effect.stop();
        effect.simulate(1.5F);
        assertTrue(effect.finished(), "stopped and lived out");
    }

    @Test void collisionStopsParticlesAtTheFloor() {
        ParticleTable.System system = new ParticleTable.System("test", -1, params("max_particles", 10),
            List.of(), List.of(fn("Movement Basic", "gravity", new double[]{0, 0, -800})),
            List.of(fn("Lifetime Random", "lifetime_min", 5, "lifetime_max", 5)),
            List.of(fn("emit_instantaneously", "num_to_emit", 5)), List.of(), List.of(fn("Collision via traces")), List.of());
        ParticleTable table = new ParticleTable(List.of(system), List.of(), new ParticleTable.Impacts("code", Map.of(), Map.of()));
        Effect effect = new Effect(table, 0, new Space(0, 0, 0), 2);
        effect.setOrigin(0, 0, 50, 0, 0, 0);
        effect.simulate(3);
        for (int q = 0; q < effect.root.count; q++) assertTrue(effect.root.z[q] >= -0.01, "above the floor: " + effect.root.z[q]);
        assertEquals(5, effect.root.count);
    }

    @Test void sheetFramesFollowTheirDurations() {
        ParticleTable.Sequence sequence = new ParticleTable.Sequence(false, new float[]{1, 1}, new float[][]{{0, 0, 0.5F, 1}, {0.5F, 0, 1, 1}});
        ParticleTable.Material material = new ParticleTable.Material("m", 0, "spritecard", false, Map.of(), List.of(sequence));
        float[][] out = new float[2][];
        float blend = ParticleRenderer.frames(material, 0, 1.25F, out);
        assertEquals(0.25F, blend, 1e-5);
        assertEquals(0.5F, out[0][0]);
        assertEquals(0F, out[1][0], "loops back to the first frame");
    }

    /** With {@code -Dsrc2mc.testBundle}: runs every particle system of every map for ten seconds. */
    @Test void realSystemsRun() throws Exception {
        String path = System.getProperty("src2mc.testBundle");
        Assumptions.assumeTrue(path != null, "set -Dsrc2mc.testBundle to run a real map's particles");
        var manifest = new BundleValidator().validate(Path.of(path));
        for (BundleMap map : manifest.maps()) {
            ParticleTable table = map.particles();
            if (table == null) continue;
            for (int i = 0; i < table.systems().size(); i++) {
                Effect effect = new Effect(table, i, new Space(0, 0, 0), i);
                effect.setOrigin(0, 0, 100, 0, 0, 0);
                int most = 0;
                for (int step = 0; step < 600; step++) { effect.simulate(1 / 60F); most = Math.max(most, effect.particles()); }
                ParticleSystem s = effect.root;
                for (int q = 0; q < s.count; q++) {
                    assertTrue(Float.isFinite(s.x[q]) && Float.isFinite(s.y[q]) && Float.isFinite(s.z[q]), table.systems().get(i).name());
                }
                System.out.println(map.mapId() + " " + table.systems().get(i).name() + ": most " + most + ", now " + effect.particles());
                if (System.getProperty("src2mc.particleDetail") != null) detail(map, effect.root, "  ");
            }
        }
    }

    @Test void electricSparksRun() throws Exception {
        String path = System.getProperty("src2mc.testBundle");
        Assumptions.assumeTrue(path != null, "set -Dsrc2mc.testBundle to run a real map's sparks");
        var manifest = new BundleValidator().validate(Path.of(path));
        for (BundleMap map : manifest.maps()) {
            ParticleTable table = map.particles();
            if (table == null || table.impacts().materials().get("effects/spark") == null) continue;
            var context = new CodeEffects.Context(table, new Space(0, 0, 0), table.impacts().materials());
            List<Effect> effects = CodeEffects.electricSpark(context, new double[] {0, 0, 100}, 1, 1, null, 7);
            assertFalse(effects.isEmpty(), map.mapId());
            int most = 0;
            for (int step = 0; step < 120; step++) {
                int now = 0;
                for (Effect effect : effects) { effect.simulate(1 / 60F); now += effect.particles(); }
                most = Math.max(most, now);
            }
            assertTrue(most > 0, map.mapId());
            for (Effect effect : effects) assertTrue(effect.finished(), map.mapId() + " sparks end within two seconds");
            System.out.println(map.mapId() + " electric spark: " + effects.size() + " emitters, most " + most + " particles");
        }
    }

    /** Per system of the tree: count, mean alpha, radius and colour, and whether its texture places on the atlas. */
    private static void detail(BundleMap map, ParticleSystem s, String indent) {
        double alpha = 0, radius = 0, r = 0;
        for (int q = 0; q < s.count; q++) {
            alpha += s.scalar[ParticleSystem.ALPHA][q]; radius += s.scalar[ParticleSystem.RADIUS][q]; r += s.r[q];
        }
        int n = Math.max(1, s.count);
        System.out.println(indent + s.definition.name() + " count " + s.count + " alpha " + (float) (alpha / n) + " radius " + (float) (radius / n)
            + " r " + (float) (r / n) + " placement " + ParticleRenderer.placement(map, s.material) + " renderers " + s.renderers.size());
        for (ParticleSystem child : s.children) detail(map, child, indent + "  ");
    }
}
