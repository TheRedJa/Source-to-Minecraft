package dev.theredja.src2mc.client.particles;

import static dev.theredja.src2mc.client.particles.ParticleSystem.*;

import dev.theredja.src2mc.bundle.ParticleTable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * The SDK's code-driven impact effects, which HL2-era games draw a bullet hit with
 * ({@code cl_new_impact_effects 0}): {@code FX_DebrisFlecks}, {@code FX_DustImpact} and
 * {@code FX_MetalSpark} of the public {@code c_impact_effects.cpp} and {@code fx_sparks.cpp},
 * with their emitters' motion ({@code CSimpleEmitter}, {@code CDustParticle}, {@code CFleckParticles},
 * {@code CTrailParticles}). Each is a system of this runtime whose particles are set up here and
 * moved by {@link Simulate}, so they draw as every other particle does.
 */
final class CodeEffects {
    private CodeEffects() {}

    /** The function name that selects {@link Simulate}. */
    static final String FUNCTION = "src2mc code particles";

    enum Mode { SIMPLE, DUST, FLECK, TRAIL }

    /** What a game's code effect needs of the bundle: the table and where its materials are. */
    record Context(ParticleTable table, Space space, Map<String, Integer> materials) {
        int material(String name) { Integer index = materials.get(name); return index == null ? -1 : index; }
    }

    /** A burst of one mode and material, its particles set up by the caller. */
    static final class Burst {
        final Effect effect;
        final ParticleSystem system;
        Burst(Context context, Mode mode, int material, int capacity, double gravity, double dampen, double velocityDampen, long seed) {
            this(context, mode, material, capacity, gravity, dampen, velocityDampen, true, true, seed);
        }

        /**
         * {@code collide}: the emitter traces its particles against the world
         * ({@code bitsPARTICLE_TRAIL_COLLIDE}); {@code fade}: a trail fades with the life left
         * ({@code bitsPARTICLE_TRAIL_FADE}).
         */
        Burst(Context context, Mode mode, int material, int capacity, double gravity, double dampen, double velocityDampen,
              boolean collide, boolean fade, long seed) {
            ParticleTable.Params params = new ParticleTable.Params(Map.of("max_particles", new double[]{capacity}));
            ParticleTable.Params simulate = new ParticleTable.Params(Map.of("mode", new double[]{mode.ordinal()}, "gravity", new double[]{gravity},
                "dampen", new double[]{dampen}, "velocity dampen", new double[]{velocityDampen}, "collide", new double[]{collide ? 1 : 0},
                "fade", new double[]{fade ? 1 : 0}));
            List<ParticleTable.Function> renderers = List.of(mode == Mode.TRAIL
                ? new ParticleTable.Function("render_sprite_trail", new ParticleTable.Params(Map.of("animation rate", new double[]{0})))
                : new ParticleTable.Function("render_animated_sprites", new ParticleTable.Params(Map.of("animation rate", new double[]{0}))));
            ParticleTable.System definition = new ParticleTable.System("code", material, params, renderers,
                List.of(new ParticleTable.Function(FUNCTION, simulate)), List.of(), List.of(), List.of(), List.of(), List.of());
            effect = new Effect(context.table(), definition, context.space(), seed);
            effect.stop();
            system = effect.root;
        }

        /** One particle at a Source point with a velocity (units per second); returns its index or -1. */
        int add(double[] at, double vx, double vy, double vz, float life) {
            if (system.create(1, system.time, 0) == 0) return -1;
            int q = system.count - 1;
            system.x[q] = (float) at[0]; system.y[q] = (float) at[1]; system.z[q] = (float) at[2];
            // Velocity is carried as the step back to the previous position, over a nominal step.
            system.px[q] = (float) (at[0] - vx * NOMINAL_STEP);
            system.py[q] = (float) (at[1] - vy * NOMINAL_STEP);
            system.pz[q] = (float) (at[2] - vz * NOMINAL_STEP);
            system.scalar[LIFE][q] = life;
            system.scalar[CREATION][q] = system.time;
            return q;
        }

        void size(int q, float start, float end) {
            system.scalar[RADIUS][q] = start; system.initial[RADIUS][q] = start; system.aux0[q] = end;
        }

        void alpha(int q, float start, float end) {
            system.scalar[ALPHA][q] = start; system.initial[ALPHA][q] = start; system.aux1[q] = end;
        }

        void color(int q, float r, float g, float b) { system.r[q] = r; system.g[q] = g; system.b[q] = b; }

        void roll(int q, float degrees, float delta) {
            system.scalar[ROTATION][q] = (float) Math.toRadians(degrees);
            system.scalar[ROTATION_SPEED][q] = delta;
        }
    }

    static final float NOMINAL_STEP = 1 / 64F;

    /** Moves a code effect's particles as its SDK emitter does. */
    static final class Simulate extends Functions.Operator {
        final Mode mode;
        final float gravity, dampen, velocityDampen;
        final boolean collide, fade;
        Simulate(ParticleTable.Params p) {
            super(p);
            mode = Mode.values()[Math.max(0, Math.min(Mode.values().length - 1, i("mode", 0)))];
            gravity = f("gravity", 0); dampen = f("dampen", 0); velocityDampen = f("velocity dampen", 0);
            collide = f("collide", 1) != 0; fade = f("fade", 1) != 0;
        }

        @Override void operate(ParticleSystem s, float weight) {
            float dt = s.dt;
            double[] hit = new double[7];
            for (int q = 0; q < s.count; q++) {
                float age = s.age(q), life = s.scalar[LIFE][q];
                if (age >= life) { s.kill(q); continue; }
                float t = life > 0 ? age / life : 1;
                // Every code particle is made before the first step, its velocity over the nominal step.
                float step = s.time <= dt + 1e-6F ? NOMINAL_STEP : s.previousDt;
                double vx = (s.x[q] - s.px[q]) / step, vy = (s.y[q] - s.py[q]) / step, vz = (s.z[q] - s.pz[q]) / step;
                switch (mode) {
                    case DUST -> {
                        // CDustParticle::UpdateVelocity: decays to 0.0001 in half a second, never below 32 units a second.
                        double decay = Math.exp(Math.log(0.0001) * dt / 0.5);
                        double speed = Math.sqrt(vx * vx + vy * vy + vz * vz);
                        double kept = speed * decay;
                        double k = speed > 1e-6 ? Math.max(kept, 32) / speed : 0;
                        vx *= k; vy *= k; vz *= k;
                        // UpdateRoll: the spin slows by 8 per second, never below 0.5.
                        float delta = s.scalar[ROTATION_SPEED][q];
                        s.scalar[ROTATION][q] += delta * dt;
                        delta += delta * (dt * -8);
                        if (Math.abs(delta) < 0.5F) delta = delta >= 0 ? 0.5F : -0.5F;
                        s.scalar[ROTATION_SPEED][q] = delta;
                        // UpdateAlpha: 1 - t, squared below 0.75.
                        float ramp = 1 - t;
                        if (ramp < 0.75F) ramp *= ramp;
                        s.scalar[ALPHA][q] = ramp;
                        s.scalar[RADIUS][q] = Functions.lerp(s.initial[RADIUS][q], s.aux0[q], t);
                    }
                    case SIMPLE -> {
                        s.scalar[ROTATION][q] += s.scalar[ROTATION_SPEED][q] * dt;
                        s.scalar[ALPHA][q] = Functions.lerp(s.initial[ALPHA][q], s.aux1[q], t);
                        s.scalar[RADIUS][q] = Functions.lerp(s.initial[RADIUS][q], s.aux0[q], t);
                    }
                    case FLECK -> {
                        s.scalar[ROTATION][q] += s.scalar[ROTATION_SPEED][q] * dt;
                        vz -= gravity * dt;
                        s.scalar[ALPHA][q] = 1 - t;
                    }
                    case TRAIL -> {
                        vz -= gravity * dt;
                        if (velocityDampen > 0) {
                            double attenuation = Math.max(0, 1 - dt * velocityDampen);
                            vx *= attenuation; vy *= attenuation; vz *= attenuation;
                        }
                        // CTrailParticles: the tail shrinks with the life left; with the fade flag the colour fades with it.
                        s.scalar[TRAIL][q] = Math.max(0.01F, s.aux0[q] * (1 - t));
                        s.scalar[ALPHA][q] = fade ? 1 - t : 1;
                    }
                }
                float ox = s.x[q], oy = s.y[q], oz = s.z[q];
                s.px[q] = ox; s.py[q] = oy; s.pz[q] = oz;
                s.x[q] = (float) (ox + vx * dt); s.y[q] = (float) (oy + vy * dt); s.z[q] = (float) (oz + vz * dt);
                if (collide && (mode == Mode.FLECK || mode == Mode.TRAIL) && s.effect.trace(ox, oy, oz, s.x[q], s.y[q], s.z[q], hit)) {
                    // CParticleCollision: reflect off the plane, keeping the damped share of the speed.
                    double along = vx * hit[3] + vy * hit[4] + vz * hit[5];
                    double rx = (vx - 2 * along * hit[3]) * dampen, ry = (vy - 2 * along * hit[4]) * dampen, rz = (vz - 2 * along * hit[5]) * dampen;
                    s.x[q] = (float) (hit[0] + hit[3] * 0.1); s.y[q] = (float) (hit[1] + hit[4] * 0.1); s.z[q] = (float) (hit[2] + hit[5] * 0.1);
                    s.px[q] = (float) (s.x[q] - rx * dt); s.py[q] = (float) (s.y[q] - ry * dt); s.pz[q] = (float) (s.z[q] - rz * dt);
                    if (mode == Mode.FLECK) s.scalar[ROTATION_SPEED][q] *= dampen;
                }
            }
        }
    }

    // ---------------------------------------------------------------- the impacts

    static final float FLECK_MIN_SPEED = 64, FLECK_MAX_SPEED = 128, FLECK_GRAVITY = 800, FLECK_DAMPEN = 0.3F, FLECK_ANGULAR_SPRAY = 0.6F;
    static final float METAL_SPARK_SPREAD = 0.5F, METAL_SPARK_MINSPEED = 128, METAL_SPARK_MAXSPEED = 512, METAL_SPARK_GRAVITY = 400,
        METAL_SPARK_DAMPEN = 0.25F;

    /**
     * {@code PerformCustomEffects} with {@code cl_new_impact_effects 0}: what a bullet hit draws for
     * a game material. {@code color} is {@code GetColorForSurface}'s, 0..1. Returns the effects.
     */
    static List<Effect> impact(Context context, char material, double[] point, double[] normal, double[] shot, float[] color, int scale, long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        List<Effect> out = new ArrayList<>();
        switch (material) {
            case 'C', 'T', 'W' -> debrisFlecks(context, point, normal, material, color, scale, random, out);
            case 'D', 'N' -> dustImpact(context, point, normal, color, scale, random, out);
            case 'M', 'V' -> {
                double dot = shot[0] * normal[0] + shot[1] * normal[1] + shot[2] * normal[2];
                double[] reflect = {shot[0] - 2 * dot * normal[0] + range(random, -0.2, 0.2), shot[1] - 2 * dot * normal[1] + range(random, -0.2, 0.2),
                    shot[2] - 2 * dot * normal[2] + range(random, -0.2, 0.2)};
                metalSpark(context, point, reflect, normal, scale, random, out);
            }
            default -> { }
        }
        return out;
    }

    private static double range(SplittableRandom random, double min, double max) { return min + (max - min) * random.nextDouble(); }
    private static int range(SplittableRandom random, int min, int max) { return min + random.nextInt(max - min + 1); }

    private static void debrisFlecks(Context context, double[] origin, double[] normal, char material, float[] color, int scale,
                                     SplittableRandom random, List<Effect> out) {
        // CreateFleckParticles.
        String[] flecks = material == 'W' ? new String[]{"effects/fleck_wood1", "effects/fleck_wood2"} : new String[]{"effects/fleck_cement1", "effects/fleck_cement2"};
        double[] spawn = {origin[0] + normal[0], origin[1] + normal[1], origin[2] + normal[2]};
        float maxSpeed = FLECK_MAX_SPEED * scale;
        float spray = Math.max(0.2F, FLECK_ANGULAR_SPRAY - scale * 0.2F);
        int count = (int) (0.5F + scale * range(random, 4, 16));
        Burst[] bursts = new Burst[2];
        for (int k = 0; k < 2; k++) {
            int index = context.material(flecks[k]);
            if (index >= 0) bursts[k] = new Burst(context, Mode.FLECK, index, count, FLECK_GRAVITY, FLECK_DAMPEN, 0, random.nextLong());
        }
        for (int i = 0; i < count; i++) {
            Burst burst = bursts[random.nextInt(2)];
            if (burst == null) continue;
            double dx = normal[0] + range(random, -spray, spray), dy = normal[1] + range(random, -spray, spray), dz = normal[2] + range(random, -spray, spray);
            int size = range(random, 1, 2);
            double speed = range(random, FLECK_MIN_SPEED, maxSpeed) * (3 - size);
            int q = burst.add(spawn, dx * speed, dy * speed, dz * speed, 3);
            if (q < 0) continue;
            burst.size(q, size, size);
            burst.alpha(q, 1, 0);
            burst.roll(q, (float) range(random, 0.0, 360.0), (float) range(random, 0.0, 360.0));
            float ramp = (float) range(random, 0.75, 1.25);
            burst.color(q, Math.min(1, color[0] * ramp), Math.min(1, color[1] * ramp), Math.min(1, color[2] * ramp));
        }
        for (Burst burst : bursts) if (burst != null) out.add(burst.effect);

        // The dust trail, the small puffs and the bullet hole capper.
        double[] offset = {origin[0] + normal[0] * 2, origin[1] + normal[1] * 2, origin[2] + normal[2] * 2};
        int dust = context.material("particle/particle_smokegrenade"), blood = context.material("effects/blood");
        if (dust >= 0) {
            Burst trail = new Burst(context, Mode.SIMPLE, dust, 3, 0, 0, 0, random.nextLong());
            for (int i = 0; i < 2; i++) {
                double[] dir = spread(normal, 0.8, random);
                double speed = range(random, 2.0, 24.0) * (i + 1);
                int q = trail.add(offset, dir[0] * speed, dir[1] * speed, dir[2] * speed - range(random, 8.0, 32.0) * (i + 1), 1);
                if (q < 0) continue;
                int start = range(random, 2, 4) * scale;
                trail.size(q, start, start * 8 * scale);
                trail.alpha(q, range(random, 100, 200) / 255F, 0);
                trail.roll(q, (float) range(random, 0.0, 360.0), (float) range(random, -1.0, 1.0));
                tint(trail, q, color, random);
            }
            double[] dir = spread(normal, 0.8, random);
            double speed = range(random, 2.0, 24.0);
            int q = trail.add(offset, dir[0] * speed, dir[1] * speed, range(random, -2.0, 2.0), (float) range(random, 1.0, 1.5));
            if (q >= 0) {
                int start = range(random, 4, 8);
                trail.size(q, start, start * 4);
                trail.alpha(q, range(random, 100, 200) / 255F, 0);
                trail.roll(q, (float) range(random, 0.0, 360.0), (float) range(random, -2.0, 2.0));
                tint(trail, q, color, random);
            }
            out.add(trail.effect);
        }
        if (blood >= 0) {
            Burst puffs = new Burst(context, Mode.SIMPLE, blood, 4, 0, 0, 0, random.nextLong());
            for (int i = 0; i < 4; i++) {
                double[] dir = spread(normal, 0.8, random);
                double speed = range(random, 8.0, 32.0);
                int q = puffs.add(offset, dir[0] * speed, dir[1] * speed, dir[2] * speed - range(random, 8.0, 64.0), (float) range(random, 0.25, 0.5));
                if (q < 0) continue;
                int start = range(random, 1, 4);
                puffs.size(q, start, start * 4);
                puffs.alpha(q, 1, 0);
                puffs.roll(q, (float) range(random, 0.0, 360.0), (float) range(random, -2.0, 2.0));
                tint(puffs, q, color, random);
            }
            out.add(puffs.effect);
        }
    }

    private static void dustImpact(Context context, double[] origin, double[] normal, float[] color, int scale, SplittableRandom random, List<Effect> out) {
        int dust = context.material("particle/particle_smokegrenade"), blood = context.material("effects/blood");
        int[] ids = {3, 1, 2, 0};
        if (dust >= 0) {
            Burst puffs = new Burst(context, Mode.DUST, dust, 8, 0, 0, 0, random.nextLong());
            for (int i = 0; i < 4; i++) {
                int id = ids[i];
                double[] dir = normalized(normal, 0.2, 1.0, 6.0, random);
                double force = range(random, 250.0, 500.0) * id * scale;
                int q = puffs.add(origin, dir[0] * force, dir[1] * force, dir[2] * force, (float) range(random, 0.5, 1.0));
                if (q < 0) continue;
                int start = scale * range(random, 3, 4) * (id + 1);
                puffs.size(q, start, scale * start * 4);
                puffs.alpha(q, range(random, 32, 255) / 255F, 0);
                puffs.roll(q, range(random, 0, 360), (float) range(random, -8.0, 8.0));
                tint(puffs, q, color, random, 0.75, 1.25);
            }
            for (int i = 0; i < 4; i++) {
                double[] dir = normalized(normal, 1.0, 1.0, 1.0, random);
                double force = range(random, 0.0, 50.0);
                double[] at = {origin[0] + range(random, -8.0, 8.0), origin[1] + range(random, -8.0, 8.0), origin[2]};
                int q = puffs.add(at, dir[0] * force, dir[1] * force, dir[2] * force, (float) range(random, 0.5, 1.0));
                if (q < 0) continue;
                int start = range(random, 1, 4);
                puffs.size(q, start, start * 4);
                puffs.alpha(q, range(random, 32, 64) / 255F, 0);
                puffs.roll(q, range(random, 0, 360), (float) range(random, -16.0, 16.0));
                tint(puffs, q, color, random, 0.75, 1.25);
            }
            out.add(puffs.effect);
        }
        if (blood >= 0) {
            Burst specs = new Burst(context, Mode.DUST, blood, 4, 0, 0, 0, random.nextLong());
            for (int i = 0; i < 4; i++) {
                int id = ids[i];
                double[] dir = normalized(normal, 0.2, 1.0, 6.0, random);
                double force = range(random, 250.0, 500.0) * id;
                int q = specs.add(origin, dir[0] * force, dir[1] * force, dir[2] * force, (float) range(random, 0.25, 0.75));
                if (q < 0) continue;
                int start = range(random, 2, 4) * (id + 1);
                specs.size(q, start, start * 2);
                specs.alpha(q, 1, 0);
                specs.roll(q, range(random, 0, 360), (float) range(random, -2.0, 2.0));
                tint(specs, q, color, random, 0.75, 1.25);
            }
            out.add(specs.effect);
        }
    }

    private static void metalSpark(Context context, double[] position, double[] direction, double[] normal, int scale, SplittableRandom random, List<Effect> out) {
        double[] offset = {position[0] + normal[0], position[1] + normal[1], position[2] + normal[2]};
        int spark = context.material("effects/spark");
        if (spark >= 0) {
            int count = range(random, 4, 8) * scale * 2;
            // FX_MetalSpark: velocity dampen 8, neither the collide nor the fade flag.
            Burst sparks = new Burst(context, Mode.TRAIL, spark, count, METAL_SPARK_GRAVITY, METAL_SPARK_DAMPEN, 8, false, false, random.nextLong());
            for (int i = 0; i < count; i++) {
                float life = scale > 1 && i % 3 == 0 ? (float) range(random, 0.15, 0.25) : (float) range(random, 0.05, 0.1);
                double spread = range(random, 0.0, 2.0) * METAL_SPARK_SPREAD;
                double dx = direction[0] + range(random, -spread, spread), dy = direction[1] + range(random, -spread, spread), dz = direction[2] + range(random, -spread, spread);
                double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (length < 1e-9) continue;
                double factor = 2 - spread / METAL_SPARK_SPREAD;
                double speed = range(random, METAL_SPARK_MINSPEED * factor, METAL_SPARK_MAXSPEED * factor);
                int q = sparks.add(offset, dx / length * speed, dy / length * speed, dz / length * speed, life);
                if (q < 0) continue;
                float width = (float) range(random, 1.0, 4.0);
                sparks.size(q, width / 2, width / 2);
                sparks.system.aux0[q] = (float) range(random, 0.1 * 0.25, 0.1);
                sparks.system.scalar[TRAIL][q] = sparks.system.aux0[q];
                sparks.alpha(q, 1, 0);
                sparks.color(q, 1, 1, 1);
            }
            out.add(sparks.effect);
        }
        int flare = context.material("effects/yellowflare");
        if (flare >= 0) {
            // FX_AddQuad: the impact glow, 0.1 s, from a scale of 24 to 28 down to nothing.
            Burst glow = new Burst(context, Mode.SIMPLE, flare, 1, 0, 0, 0, random.nextLong());
            int q = glow.add(offset, 0, 0, 0, 0.1F);
            if (q >= 0) {
                glow.size(q, range(random, 24, 28) / 2F, 0);
                glow.alpha(q, 1, 0);
                glow.roll(q, range(random, 0, 360), 0);
                glow.color(q, 1, 1, 1);
                out.add(glow.effect);
            }
        }
    }

    static final float SPARK_ELECTRIC_MINSPEED = 64, SPARK_ELECTRIC_MAXSPEED = 300, SPARK_ELECTRIC_GRAVITY = 800, SPARK_ELECTRIC_DAMPEN = 0.3F;

    /**
     * {@code FX_ElectricSpark} (the SDK's {@code fx_sparks.cpp}, what {@code env_spark} draws): big
     * sparks that bounce, little ones that do not, two glow caps for 0.2 s and a puff of smoke.
     * {@code direction} is null or the entity's forward (Source axes). The caps' glow emitter
     * draws only when its point is seen; here, when nothing is between the eye and the point.
     */
    static List<Effect> electricSpark(Context context, double[] pos, int magnitude, int trailLength, double[] direction, long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        List<Effect> out = new ArrayList<>();
        double[] dir0 = direction == null ? new double[3] : direction;
        int spark = context.material("effects/spark");
        if (spark >= 0) {
            int count = (int) (magnitude * magnitude * range(random, 2.0, 4.0));
            Burst big = new Burst(context, Mode.TRAIL, spark, Math.max(1, count), SPARK_ELECTRIC_GRAVITY, SPARK_ELECTRIC_DAMPEN, 0, true, false,
                random.nextLong());
            for (int i = 0; i < count; i++) {
                double[] dir = {range(random, -1.0, 1.0), range(random, -1.0, 1.0), range(random, 0.5, 1.0)};
                dir[0] += 2 * dir0[0]; dir[1] += 2 * dir0[1]; dir[2] += 2 * dir0[2];
                normalize(dir);
                float life = magnitude * (float) range(random, 1.0, 2.0);
                double speed = range(random, SPARK_ELECTRIC_MINSPEED, SPARK_ELECTRIC_MAXSPEED);
                int q = big.add(pos, dir[0] * speed, dir[1] * speed, dir[2] * speed, life);
                if (q < 0) break;
                float width = (float) range(random, 2.0, 5.0);
                big.size(q, width / 2, width / 2);
                big.system.aux0[q] = trailLength * (float) range(random, 0.02, 0.05);
                big.system.scalar[TRAIL][q] = big.system.aux0[q];
                big.color(q, 1, 1, 1);
            }
            out.add(big.effect);
            int little = magnitude * range(random, 16, 32);
            Burst small = new Burst(context, Mode.TRAIL, spark, Math.max(1, little), 400, 0.5, 0, false, false, random.nextLong());
            for (int i = 0; i < little; i++) {
                double[] dir = {range(random, -1.0, 1.0), range(random, -1.0, 1.0), range(random, -1.0, 1.0)};
                dir[0] += dir0[0]; dir[1] += dir0[1]; dir[2] += dir0[2];
                normalize(dir);
                float width = (float) range(random, 2.0, 4.0);
                float length = trailLength * (float) range(random, 0.02, 0.03);
                float life = magnitude * (float) range(random, 0.1, 0.2);
                double speed = range(random, 128.0, 256.0);
                int q = small.add(pos, dir[0] * speed, dir[1] * speed, dir[2] * speed, life);
                if (q < 0) break;
                small.size(q, width / 2, width / 2);
                small.system.aux0[q] = length;
                small.system.scalar[TRAIL][q] = length;
                small.color(q, 1, 1, 1);
            }
            out.add(small.effect);
        }
        int flare = context.material("effects/yellowflare_noz");
        if (flare >= 0) {
            Burst caps = new Burst(context, Mode.SIMPLE, flare, 2, 0, 0, 0, random.nextLong());
            double[] eye = caps.effect.playerEye();
            if (eye == null || !caps.effect.trace(eye[0], eye[1], eye[2], pos[0], pos[1], pos[2], new double[7])) {
                // Inner glow, then the grey halo.
                int q = caps.add(pos, 0, 0, 0, 0.2F);
                if (q >= 0) {
                    caps.size(q, magnitude * range(random, 4, 8), 0);
                    caps.alpha(q, 1, 1);
                    caps.color(q, 1, 1, 1);
                    caps.roll(q, range(random, 0, 360), 0);
                }
                float grey = range(random, 32, 64) / 255F;
                q = caps.add(pos, 0, 0, 0, 0.2F);
                if (q >= 0) {
                    caps.size(q, magnitude * range(random, 32, 64), 0);
                    caps.alpha(q, grey, 0);
                    caps.color(q, grey, grey, grey);
                    caps.roll(q, range(random, 0, 360), (float) range(random, -1.0, 1.0));
                }
                out.add(caps.effect);
            }
        }
        int smoke = context.material("particle/particle_noisesphere");
        if (smoke >= 0) {
            Burst puff = new Burst(context, Mode.SIMPLE, smoke, 1, 0, 0, 0, random.nextLong());
            double[] at = {pos[0] + range(random, -4.0, 4.0), pos[1] + range(random, -4.0, 4.0), pos[2]};
            int q = puff.add(at, range(random, -16.0, 16.0), range(random, -16.0, 16.0), 16, 1.0F);
            if (q >= 0) {
                int size = range(random, 4, 8);
                puff.size(q, size, size * 4);
                puff.alpha(q, range(random, 16, 32) / 255F, 0);
                puff.color(q, 1, 1, 200 / 255F);
                puff.roll(q, range(random, 0, 360), (float) range(random, -2.0, 2.0));
                out.add(puff.effect);
            }
        }
        return out;
    }

    private static void normalize(double[] v) {
        double length = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (length > 1e-9) { v[0] /= length; v[1] /= length; v[2] /= length; }
    }

    private static double[] spread(double[] normal, double amount, SplittableRandom random) {
        return new double[]{normal[0] + range(random, -amount, amount), normal[1] + range(random, -amount, amount), normal[2] + range(random, -amount, amount)};
    }

    /** {@code m_vecVelocity.Random(-spread, spread) + normal * RandomFloat(min, max)}, normalized. */
    private static double[] normalized(double[] normal, double spread, double min, double max, SplittableRandom random) {
        double along = range(random, min, max);
        double[] v = {range(random, -spread, spread) + normal[0] * along, range(random, -spread, spread) + normal[1] * along,
            range(random, -spread, spread) + normal[2] * along};
        double length = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (length > 1e-9) { v[0] /= length; v[1] /= length; v[2] /= length; }
        return v;
    }

    private static void tint(Burst burst, int q, float[] color, SplittableRandom random) { tint(burst, q, color, random, 0.5, 1.25); }

    private static void tint(Burst burst, int q, float[] color, SplittableRandom random, double min, double max) {
        float ramp = (float) range(random, min, max);
        burst.color(q, Math.min(1, color[0] * ramp), Math.min(1, color[1] * ramp), Math.min(1, color[2] * ramp));
    }
}
