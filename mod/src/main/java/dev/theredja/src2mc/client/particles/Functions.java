package dev.theredja.src2mc.client.particles;

import static dev.theredja.src2mc.client.particles.ParticleSystem.*;

import dev.theredja.src2mc.bundle.ParticleTable;
import java.util.Locale;

/**
 * Source's particle functions, by the {@code functionName} the {@code .pcf} gives them. The
 * particle library is not public: where noclip.website (MIT, reverse-engineered) implements a
 * function this follows it; the rest follow the Valve Developer Wiki's description of each
 * parameter and need checking in game. Every parameter arrives with the library's default filled
 * in by the converter, so the fallbacks here only matter for functions newer than that table.
 */
final class Functions {
    private Functions() {}

    // ---------------------------------------------------------------- bases

    abstract static class Base {
        final ParticleTable.Params p;
        private final float startFadeIn, endFadeIn, startFadeOut, endFadeOut, oscillate;

        Base(ParticleTable.Params p) {
            this.p = p;
            startFadeIn = f("operator start fadein", 0);
            endFadeIn = f("operator end fadein", 0);
            startFadeOut = f("operator start fadeout", 0);
            endFadeOut = f("operator end fadeout", 0);
            oscillate = f("operator fade oscillate", 0);
        }

        final float f(String key, double fallback) { return (float) p.number(key, fallback); }
        final int i(String key, int fallback) { return p.integer(key, fallback); }
        final boolean bool(String key, boolean fallback) { return p.bool(key, fallback); }
        final float[] v(String key, double... fallback) {
            double[] d = p.vector(key, fallback);
            float[] out = new float[d.length];
            for (int k = 0; k < d.length; k++) out[k] = (float) d[k];
            return out;
        }

        /**
         * How much the function acts at system time {@code time}: the operator fade in and out
         * window, which the wiki describes relative to the system's life, and with an oscillation
         * period, relative to that period. 1 when no window is set.
         */
        float weight(float time) {
            if (startFadeIn == 0 && endFadeIn == 0 && startFadeOut == 0 && endFadeOut == 0) return 1;
            float t = oscillate > 0 ? (time / oscillate) % 1F : time;
            float weight = 1;
            if (endFadeIn > startFadeIn) {
                if (t < startFadeIn) return 0;
                if (t < endFadeIn) weight = (t - startFadeIn) / (endFadeIn - startFadeIn);
            }
            if (endFadeOut > startFadeOut) {
                if (t >= endFadeOut) return 0;
                if (t > startFadeOut) weight = Math.min(weight, 1 - (t - startFadeOut) / (endFadeOut - startFadeOut));
            }
            return weight;
        }
    }

    abstract static class Initializer extends Base {
        Initializer(ParticleTable.Params p) { super(p); }
        abstract void init(ParticleSystem s, int from, int to);
    }

    abstract static class Operator extends Base {
        Operator(ParticleTable.Params p) { super(p); }
        abstract void operate(ParticleSystem s, float weight);
    }

    abstract static class Emitter extends Base {
        Emitter(ParticleTable.Params p) { super(p); }
        abstract void emit(ParticleSystem s);
        abstract boolean active(ParticleSystem s);
    }

    abstract static class Force extends Base {
        Force(ParticleTable.Params p) { super(p); }
        /** Adds this force's acceleration, units per second squared, for every particle. */
        abstract void apply(ParticleSystem s, float[] ax, float[] ay, float[] az, float weight);
    }

    abstract static class Constraint extends Base {
        Constraint(ParticleTable.Params p) { super(p); }
        abstract void apply(ParticleSystem s);
    }

    // ---------------------------------------------------------------- helpers

    static float clamp01(float v) { return v < 0 ? 0 : Math.min(v, 1); }
    static float lerp(float a, float b, float t) { return a + (b - a) * t; }
    static float smoothstep(float t) { t = clamp01(t); return t * t * (3 - 2 * t); }
    static float schlickBias(float t, float bias) { return t / ((1 / bias - 2) * (1 - t) + 1); }
    static int cp(int index) { return Math.max(0, Math.min(CONTROL_POINTS - 1, index)); }

    /** A uniformly random unit vector. */
    static void randomUnit(ParticleSystem s, double[] out) {
        double zz = s.random() * 2 - 1, a = s.random() * Math.PI * 2, r = Math.sqrt(Math.max(0, 1 - zz * zz));
        out[0] = r * Math.cos(a); out[1] = r * Math.sin(a); out[2] = zz;
    }

    /** Fields that hold angles: their remap outputs and ramp rates are written in degrees. */
    static boolean isAngle(int field) { return field == ROTATION || field == YAW || field == ROTATION_SPEED; }

    /**
     * Sets a particle's velocity, units per second, through its previous position: over the
     * previous step, as the initializers in particles.a write it ({@code m_flPreviousDt}), since
     * movement scales that step by {@code dt / previous dt}. Over this step instead, a short step
     * followed by a long one would launch new particles many times too fast.
     */
    static void setVelocity(ParticleSystem s, int p, double vx, double vy, double vz) {
        float step = s.previousDt > 0 ? s.previousDt : s.dt;
        s.px[p] = (float) (s.x[p] - vx * step);
        s.py[p] = (float) (s.y[p] - vy * step);
        s.pz[p] = (float) (s.z[p] - vz * step);
    }

    static void addVelocity(ParticleSystem s, int p, double vx, double vy, double vz) {
        float step = s.previousDt > 0 ? s.previousDt : s.dt;
        s.px[p] -= (float) (vx * step);
        s.py[p] -= (float) (vy * step);
        s.pz[p] -= (float) (vz * step);
    }

    /** Writes a scalar output, or scales the value the initializers left. */
    static void write(ParticleSystem s, int field, int particle, float value, boolean scaleInitial) {
        float[] target = field >= 0 && field < s.scalar.length ? s.scalar[field] : null;
        if (target == null) return;
        if (isAngle(field)) value = (float) Math.toRadians(value);
        target[particle] = scaleInitial ? s.initial[field][particle] * value : value;
    }

    static float read(ParticleSystem s, int field, int particle) {
        float[] source = field >= 0 && field < s.scalar.length ? s.scalar[field] : null;
        if (source == null) return 0;
        float value = source[particle];
        return isAngle(field) ? (float) Math.toDegrees(value) : value;
    }

    static String key(ParticleTable.Function function) { return function.name().toLowerCase(Locale.ROOT); }

    // ---------------------------------------------------------------- factories

    static Initializer initializer(ParticleTable.Function function) {
        ParticleTable.Params p = function.parameters();
        return switch (key(function)) {
            case "position within sphere random" -> new PositionWithinSphere(p);
            case "position within box random" -> new PositionWithinBox(p);
            case "position modify offset random" -> new PositionOffset(p);
            case "position modify warp random" -> new PositionWarp(p);
            case "position along path sequential" -> new PositionAlongPath(p);
            case "position from parent particles" -> new PositionFromParent(p);
            case "position along ring" -> new PositionAlongRing(p);
            case "position modify place on ground" -> new PlaceOnGround(p);
            case "lifetime random" -> new RandomScalar(p, LIFE, "lifetime_min", "lifetime_max", "lifetime_random_exponent", 0, 0, 1);
            case "radius random" -> new RandomScalar(p, RADIUS, "radius_min", "radius_max", "radius_random_exponent", 1, 1, 1);
            case "trail length random" -> new RandomScalar(p, TRAIL, "length_min", "length_max", "length_random_exponent", 0.1, 0.1, 1);
            case "alpha random" -> new AlphaRandom(p);
            case "color random" -> new ColorRandom(p);
            case "rotation random" -> new RotationRandom(p, ROTATION, "rotation_initial", "rotation_offset_min", "rotation_offset_max", "rotation_random_exponent");
            case "rotation yaw random" -> new RotationRandom(p, YAW, "yaw_initial", "yaw_offset_min", "yaw_offset_max", "yaw_random_exponent");
            case "rotation yaw flip random" -> new YawFlip(p);
            case "rotation speed random" -> new RotationSpeedRandom(p);
            case "sequence random" -> new SequenceRandom(p, SEQUENCE);
            case "sequence two random" -> new SequenceRandom(p, SEQUENCE1);
            case "lifetime from sequence" -> new LifetimeFromSequence(p);
            case "lifetime pre-age noise" -> new PreAgeNoise(p);
            case "velocity random" -> new VelocityRandom(p);
            case "velocity noise" -> new VelocityNoise(p);
            case "velocity inherit from control point" -> new VelocityInherit(p);
            case "remap control point to scalar" -> new RemapControlPoint(p);
            case "remap initial scalar" -> new RemapInitialScalar(p);
            case "remap initial distance to control point to scalar" -> new RemapInitialDistance(p);
            case "remap noise to scalar" -> new RemapNoise(p);
            default -> null;
        };
    }

    static Operator operator(ParticleTable.Function function) {
        ParticleTable.Params p = function.parameters();
        return switch (key(function)) {
            case "lifespan decay" -> new LifespanDecay(p);
            case "movement basic" -> new MovementBasic(p);
            case "alpha fade in random" -> new AlphaFadeIn(p);
            case "alpha fade out random" -> new AlphaFadeOut(p);
            case "alpha fade in simple" -> new AlphaFadeInSimple(p);
            case "alpha fade out simple" -> new AlphaFadeOutSimple(p);
            case "alpha fade and decay" -> new AlphaFadeAndDecay(p);
            case "color fade" -> new ColorFade(p);
            case "radius scale" -> new RadiusScale(p);
            case "rotation spin roll" -> new Spin(p, ROTATION);
            case "rotation spin yaw" -> new Spin(p, YAW);
            case "rotation basic" -> new RotationBasic(p);
            case "oscillate scalar" -> new OscillateScalar(p);
            case "oscillate scalar simple" -> new OscillateScalarSimple(p);
            case "oscillate vector" -> new OscillateVector(p);
            case "ramp scalar linear random" -> new RampScalar(p, false);
            case "ramp scalar spline random" -> new RampScalar(p, true);
            case "remap distance to control point to scalar" -> new RemapDistanceToControlPoint(p);
            case "remap distance between two control points to scalar" -> new RemapDistanceBetweenControlPoints(p);
            case "remap percentage between two control points" -> new RemapPercentageBetweenControlPoints(p);
            case "remap cp speed to cp" -> new RemapSpeedToControlPoint(p);
            case "movement dampen relative to control point" -> new DampenToControlPoint(p);
            case "movement lock to control point" -> new LockToControlPoint(p);
            case "movement max velocity" -> new MaxVelocity(p);
            case "cull when crossing plane" -> new PlaneCull(p);
            case "set control point positions" -> new SetControlPointPositions(p);
            case "set child control points from particle positions" -> new SetChildControlPoints(p);
            case "set control point to player" -> new SetControlPointToPlayer(p);
            case "set control point to impact point" -> new SetControlPointToImpact(p);
            case CodeEffects.FUNCTION -> new CodeEffects.Simulate(p);
            default -> null;
        };
    }

    static Emitter emitter(ParticleTable.Function function) {
        ParticleTable.Params p = function.parameters();
        return switch (key(function)) {
            case "emit_continuously" -> new Continuous(p);
            case "emit_instantaneously" -> new Instantaneous(p);
            case "emit noise" -> new NoiseEmitter(p);
            default -> null;
        };
    }

    static Force force(ParticleTable.Function function) {
        ParticleTable.Params p = function.parameters();
        return switch (key(function)) {
            case "random force" -> new RandomForce(p);
            case "twist around axis" -> new TwistAroundAxis(p);
            case "pull towards control point" -> new PullToControlPoint(p);
            default -> null;
        };
    }

    static Constraint constraint(ParticleTable.Function function) {
        ParticleTable.Params p = function.parameters();
        return switch (key(function)) {
            case "collision via traces" -> new CollisionViaTraces(p);
            case "constrain distance to control point" -> new ConstrainDistance(p);
            case "constrain distance to path between two control points" -> new ConstrainToPath(p);
            default -> null;
        };
    }

    // ---------------------------------------------------------------- initializers

    /** noclip's {@code Initializer_PositionWithinSphereRandom}, with the absolute-value bias and CP spread. */
    static final class PositionWithinSphere extends Initializer {
        final float distMin = f("distance_min", 0), distMax = f("distance_max", 0);
        final float[] bias = v("distance_bias", 1, 1, 1), biasAbs = v("distance_bias_absolute_value", 0, 0, 0);
        final boolean local = bool("bias in local system", false), spread = bool("randomly distribute to highest supplied Control Point", false);
        final int controlPoint = cp(i("control_point_number", 0));
        final float speedMin = f("speed_min", 0), speedMax = f("speed_max", 0), speedExponent = f("speed_random_exponent", 1);
        final float[] localMin = v("speed_in_local_coordinate_system_min", 0, 0, 0), localMax = v("speed_in_local_coordinate_system_max", 0, 0, 0);
        PositionWithinSphere(ParticleTable.Params p) { super(p); }

        @Override void init(ParticleSystem s, int from, int to) {
            double[] dir = new double[3], offset = new double[3], localSpeed = new double[3];
            for (int q = from; q < to; q++) {
                int c = controlPoint;
                if (spread && s.highestControlPoint > c) c = cp(c + (int) (s.random() * (s.highestControlPoint - c + 1)));
                randomUnit(s, dir);
                if (bias[0] != 1 || bias[1] != 1 || bias[2] != 1) {
                    dir[0] *= bias[0]; dir[1] *= bias[1]; dir[2] *= bias[2];
                    double length = Math.sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2]);
                    if (length > 1e-9) { dir[0] /= length; dir[1] /= length; dir[2] /= length; }
                }
                for (int a = 0; a < 3; a++) if (biasAbs[a] != 0) dir[a] = Math.abs(dir[a]);
                if (local) { s.toWorld(c, dir[0], dir[1], dir[2], offset); System.arraycopy(offset, 0, dir, 0, 3); }
                float distance = distMin == distMax ? distMin : lerp(distMin, distMax, 1 - (float) Math.pow(s.random(), 3));
                s.x[q] = (float) (s.cpPosition[c][0] + dir[0] * distance);
                s.y[q] = (float) (s.cpPosition[c][1] + dir[1] * distance);
                s.z[q] = (float) (s.cpPosition[c][2] + dir[2] * distance);
                float speed = s.random(speedMin, speedMax, speedExponent);
                double vx = dir[0] * speed, vy = dir[1] * speed, vz = dir[2] * speed;
                for (int a = 0; a < 3; a++) localSpeed[a] = localMin[a] == localMax[a] ? localMin[a] : lerp(localMin[a], localMax[a], s.random());
                s.toWorldRight(c, localSpeed[0], localSpeed[1], localSpeed[2], offset);
                setVelocity(s, q, vx + offset[0], vy + offset[1], vz + offset[2]);
            }
        }
    }

    static final class PositionWithinBox extends Initializer {
        final float[] min = v("min", 0, 0, 0), max = v("max", 0, 0, 0);
        final int controlPoint = cp(i("control point number", 0));
        PositionWithinBox(ParticleTable.Params p) { super(p); }

        @Override void init(ParticleSystem s, int from, int to) {
            double[] c = s.cpPosition[controlPoint];
            for (int q = from; q < to; q++) {
                s.x[q] = (float) (c[0] + lerp(min[0], max[0], s.random()));
                s.y[q] = (float) (c[1] + lerp(min[1], max[1], s.random()));
                s.z[q] = (float) (c[2] + lerp(min[2], max[2], s.random()));
                s.px[q] = s.x[q]; s.py[q] = s.y[q]; s.pz[q] = s.z[q];
            }
        }
    }

    /** noclip's {@code Initializer_PositionModifyOffsetRandom}. */
    static final class PositionOffset extends Initializer {
        final float[] min = v("offset min", 0, 0, 0), max = v("offset max", 0, 0, 0);
        final boolean local = bool("offset in local space 0/1", false), proportional = bool("offset proportional to radius 0/1", false);
        final int controlPoint = cp(i("control_point_number", 0));
        PositionOffset(ParticleTable.Params p) { super(p); }

        @Override void init(ParticleSystem s, int from, int to) {
            double[] out = new double[3];
            for (int q = from; q < to; q++) {
                double ox = lerp(min[0], max[0], s.random()), oy = lerp(min[1], max[1], s.random()), oz = lerp(min[2], max[2], s.random());
                if (proportional) { float r = s.scalar[RADIUS][q]; ox *= r; oy *= r; oz *= r; }
                if (local) { s.toWorld(controlPoint, ox, oy, oz, out); ox = out[0]; oy = out[1]; oz = out[2]; }
                s.x[q] += (float) ox; s.y[q] += (float) oy; s.z[q] += (float) oz;
                s.px[q] += (float) ox; s.py[q] += (float) oy; s.pz[q] += (float) oz;
            }
        }
    }

    static final class PositionWarp extends Initializer {
        final float[] min = v("warp min", 1, 1, 1), max = v("warp max", 1, 1, 1);
        final int controlPoint = cp(i("control point number", 0));
        final float transition = f("warp transition time (treats min/max as start/end sizes)", 0), transitionStart = f("warp transition start time", 0);
        final boolean reverse = bool("reverse warp (0/1)", false);
        PositionWarp(ParticleTable.Params p) { super(p); }

        @Override void init(ParticleSystem s, int from, int to) {
            double[] c = s.cpPosition[controlPoint];
            for (int q = from; q < to; q++) {
                float[] warp = new float[3];
                if (transition > 0) {
                    float t = clamp01((s.time - transitionStart) / transition);
                    if (reverse) t = 1 - t;
                    for (int a = 0; a < 3; a++) warp[a] = lerp(min[a], max[a], t);
                } else {
                    for (int a = 0; a < 3; a++) warp[a] = lerp(min[a], max[a], s.random());
                }
                s.x[q] = (float) (c[0] + (s.x[q] - c[0]) * warp[0]);
                s.y[q] = (float) (c[1] + (s.y[q] - c[1]) * warp[1]);
                s.z[q] = (float) (c[2] + (s.z[q] - c[2]) * warp[2]);
                s.px[q] = (float) (c[0] + (s.px[q] - c[0]) * warp[0]);
                s.py[q] = (float) (c[1] + (s.py[q] - c[1]) * warp[1]);
                s.pz[q] = (float) (c[2] + (s.pz[q] - c[2]) * warp[2]);
            }
        }
    }

    /** Places particles one after another along the line between two control points. */
    static final class PositionAlongPath extends Initializer {
        final float maxDistance = f("maximum distance", 0), bulge = f("bulge", 0);
        final int start = cp(i("start control point number", 0)), end = cp(i("end control point number", 0));
        final float steps = Math.max(1, f("particles to map from start to end", 100));
        final boolean loop = bool("restart behavior (0 = bounce, 1 = loop )", true);
        private int sequence;
        PositionAlongPath(ParticleTable.Params p) { super(p); }

        @Override void init(ParticleSystem s, int from, int to) {
            double[] a = s.cpPosition[start], b = s.cpPosition[end], dir = new double[3];
            for (int q = from; q < to; q++) {
                int k = sequence++;
                float t;
                if (loop) t = (k % (int) steps) / steps;
                else { int period = (int) steps * 2; int m = k % Math.max(1, period); t = (m <= steps ? m : period - m) / steps; }
                double bulgeAmount = bulge * Math.sin(t * Math.PI);
                randomUnit(s, dir);
                float distance = maxDistance * s.random();
                s.x[q] = (float) (a[0] + (b[0] - a[0]) * t + dir[0] * (distance + bulgeAmount));
                s.y[q] = (float) (a[1] + (b[1] - a[1]) * t + dir[1] * (distance + bulgeAmount));
                s.z[q] = (float) (a[2] + (b[2] - a[2]) * t + dir[2] * (distance + bulgeAmount));
                s.px[q] = s.x[q]; s.py[q] = s.y[q]; s.pz[q] = s.z[q];
            }
        }
    }

    /** Starts each particle at a particle of the parent system; none there, the particle dies. */
    static final class PositionFromParent extends Initializer {
        final float inherit = f("Inherited Velocity Scale", 0);
        final boolean randomly = bool("Random Parent Particle Distribution", false);
        PositionFromParent(ParticleTable.Params p) { super(p); }

        @Override void init(ParticleSystem s, int from, int to) {
            ParticleSystem parent = s.parent;
            for (int q = from; q < to; q++) {
                if (parent == null || parent.count == 0) { s.kill(q); continue; }
                int source = randomly ? (int) (s.random() * parent.count) : (int) (s.scalar[ID][q] % parent.count);
                source = Math.min(source, parent.count - 1);
                s.x[q] = parent.x[source]; s.y[q] = parent.y[source]; s.z[q] = parent.z[source];
                float scale = parent.dt > 0 ? inherit / parent.dt : 0;
                setVelocity(s, q, (parent.x[source] - parent.px[source]) * scale, (parent.y[source] - parent.py[source]) * scale,
                    (parent.z[source] - parent.pz[source]) * scale);
            }
        }
    }

    /**
     * A ring around a control point, turned by pitch, yaw and roll in degrees; speed outward, in the
     * ring's plane when {@code XY velocity only}. Newer than the SDK's library; read from the
     * parameter names alone.
     */
    static final class PositionAlongRing extends Initializer {
        final int controlPoint = cp(i("control point number", 0));
        final float radius = f("initial radius", 0), thickness = f("thickness", 0);
        final float speedMin = f("min initial speed", 0), speedMax = f("max initial speed", 0);
        final float roll = f("roll", 0), pitch = f("pitch", 0), yaw = f("yaw", 0);
        final boolean even = bool("even distribution", false), flat = bool("XY velocity only", true);
        final float evenCount = f("even distribution count", -1);
        private int sequence;
        PositionAlongRing(ParticleTable.Params p) { super(p); }

        @Override void init(ParticleSystem s, int from, int to) {
            double[] world = new double[3], dir = new double[3];
            for (int q = from; q < to; q++) {
                double angle = even && evenCount > 0 ? (sequence++ % (int) evenCount) / evenCount * Math.PI * 2 : s.random() * Math.PI * 2;
                double r = radius + (s.random() - 0.5) * thickness;
                double lx = Math.cos(angle) * r, ly = Math.sin(angle) * r, lz = flat ? 0 : (s.random() - 0.5) * thickness;
                double[] turned = Angles.rotate(pitch, yaw, roll, lx, ly, lz);
                s.toWorld(controlPoint, turned[0], turned[1], turned[2], world);
                double[] c = s.cpPosition[controlPoint];
                s.x[q] = (float) (c[0] + world[0]); s.y[q] = (float) (c[1] + world[1]); s.z[q] = (float) (c[2] + world[2]);
                double length = Math.sqrt(world[0] * world[0] + world[1] * world[1] + world[2] * world[2]);
                float speed = s.random(speedMin, speedMax, 1);
                if (length > 1e-9) { dir[0] = world[0] / length; dir[1] = world[1] / length; dir[2] = world[2] / length; }
                else { dir[0] = dir[1] = dir[2] = 0; }
                setVelocity(s, q, dir[0] * speed, dir[1] * speed, dir[2] * speed);
            }
        }
    }

    /** Drops each new particle onto the ground below it, up to the trace length. */
    static final class PlaceOnGround extends Initializer {
        final float length = f("max trace length", 128), offset = f("offset", 0);
        PlaceOnGround(ParticleTable.Params p) { super(p); }

        @Override void init(ParticleSystem s, int from, int to) {
            double[] hit = new double[7];
            for (int q = from; q < to; q++) {
                if (!s.effect.trace(s.x[q], s.y[q], s.z[q], s.x[q], s.y[q], s.z[q] - length, hit)) continue;
                float dx = (float) (hit[0] - s.x[q]), dy = (float) (hit[1] - s.y[q]), dz = (float) (hit[2] + offset - s.z[q]);
                s.x[q] += dx; s.y[q] += dy; s.z[q] += dz;
                s.px[q] += dx; s.py[q] += dy; s.pz[q] += dz;
            }
        }
    }

    static final class RandomScalar extends Initializer {
        final int field;
        final float min, max, exponent;
        RandomScalar(ParticleTable.Params p, int field, String minKey, String maxKey, String exponentKey, double minDefault, double maxDefault, double exponentDefault) {
            super(p);
            this.field = field;
            min = f(minKey, minDefault); max = f(maxKey, maxDefault); exponent = f(exponentKey, exponentDefault);
        }

        @Override void init(ParticleSystem s, int from, int to) {
            for (int q = from; q < to; q++) s.scalar[field][q] = s.random(min, max, exponent);
        }
    }

    static final class AlphaRandom extends Initializer {
        final float min = f("alpha_min", 255) / 255F, max = f("alpha_max", 255) / 255F, exponent = f("alpha_random_exponent", 1);
        AlphaRandom(ParticleTable.Params p) { super(p); }
        @Override void init(ParticleSystem s, int from, int to) {
            for (int q = from; q < to; q++) s.scalar[ALPHA][q] = s.random(min, max, exponent);
        }
    }

    /** noclip's {@code Initializer_ColorRandom}; the lighting tint ({@code tint_perc}) is not done. */
    static final class ColorRandom extends Initializer {
        final float[] a = v("color1", 255, 255, 255, 255), b = v("color2", 255, 255, 255, 255);
        ColorRandom(ParticleTable.Params p) { super(p); }
        @Override void init(ParticleSystem s, int from, int to) {
            for (int q = from; q < to; q++) {
                float t = s.random();
                s.r[q] = lerp(a[0], b[0], t) / 255F;
                s.g[q] = lerp(a[1], b[1], t) / 255F;
                s.b[q] = lerp(a[2], b[2], t) / 255F;
            }
        }
    }

    /** noclip's {@code Initializer_RotationRandom}: degrees in, radians stored. */
    static final class RotationRandom extends Initializer {
        final int field;
        final float initial, min, max, exponent;
        RotationRandom(ParticleTable.Params p, int field, String initialKey, String minKey, String maxKey, String exponentKey) {
            super(p);
            this.field = field;
            initial = f(initialKey, 0); min = f(minKey, 0); max = f(maxKey, 360); exponent = f(exponentKey, 1);
        }
        @Override void init(ParticleSystem s, int from, int to) {
            for (int q = from; q < to; q++) s.scalar[field][q] = (float) Math.toRadians(initial + s.random(min, max, exponent));
        }
    }

    static final class YawFlip extends Initializer {
        final float percentage = f("Flip Percentage", 0.5);
        YawFlip(ParticleTable.Params p) { super(p); }
        @Override void init(ParticleSystem s, int from, int to) {
            for (int q = from; q < to; q++) if (s.random() < percentage) s.scalar[YAW][q] += (float) Math.PI;
        }
    }

    static final class RotationSpeedRandom extends Initializer {
        final float constant = f("rotation_speed_constant", 0), min = f("rotation_speed_random_min", 0), max = f("rotation_speed_random_max", 360),
            exponent = f("rotation_speed_random_exponent", 1);
        final boolean flip = bool("randomly_flip_direction", true);
        RotationSpeedRandom(ParticleTable.Params p) { super(p); }
        @Override void init(ParticleSystem s, int from, int to) {
            for (int q = from; q < to; q++) {
                float speed = constant + s.random(min, max, exponent);
                if (flip && s.random() < 0.5F) speed = -speed;
                s.scalar[ROTATION_SPEED][q] = (float) Math.toRadians(speed);
            }
        }
    }

    /** noclip's {@code Initializer_SequenceRandom}: min to max inclusive. */
    static final class SequenceRandom extends Initializer {
        final int field, min, max;
        SequenceRandom(ParticleTable.Params p, int field) { super(p); this.field = field; min = i("sequence_min", 0); max = i("sequence_max", 0); }
        @Override void init(ParticleSystem s, int from, int to) {
            for (int q = from; q < to; q++) s.scalar[field][q] = Math.min(max, (int) lerp(min, max + 1, s.random()));
        }
    }

    /** noclip's {@code Initializer_LifetimeFromSequence}: the sequence's frames at the given rate. */
    static final class LifetimeFromSequence extends Initializer {
        final float fps = f("Frames Per Second", 30);
        LifetimeFromSequence(ParticleTable.Params p) { super(p); }
        @Override void init(ParticleSystem s, int from, int to) {
            if (s.material == null || s.material.sheet().isEmpty() || fps <= 0) return;
            for (int q = from; q < to; q++) {
                int sequence = (int) s.scalar[SEQUENCE][q];
                if (sequence < 0 || sequence >= s.material.sheet().size() || s.material.sheet().get(sequence) == null) continue;
                s.scalar[LIFE][q] = s.material.sheet().get(sequence).frames() / fps;
            }
        }
    }

    /** Ages each particle by a noise-picked share of its life, so it starts part way through. */
    static final class PreAgeNoise extends Initializer {
        final Noise.Field noise;
        final float ageMin = f("start age minimum", 0), ageMax = f("start age maximum", 1);
        PreAgeNoise(ParticleTable.Params p) {
            super(p);
            noise = new Noise.Field(f("time noise coordinate scale", 1), f("spatial noise coordinate scale", 1), f("time coordinate offset", 0),
                v("spatial coordinate offset", 0, 0, 0), bool("absolute value", false), bool("invert absolute value", false));
        }
        @Override void init(ParticleSystem s, int from, int to) {
            for (int q = from; q < to; q++) {
                float age = lerp(ageMin, ageMax, noise.sample(s, q, 0));
                s.scalar[CREATION][q] -= age * s.scalar[LIFE][q];
            }
        }
    }

    static final class VelocityRandom extends Initializer {
        final int controlPoint = cp(i("control_point_number", 0));
        final float min = f("random_speed_min", 0), max = f("random_speed_max", 0);
        final float[] localMin = v("speed_in_local_coordinate_system_min", 0, 0, 0), localMax = v("speed_in_local_coordinate_system_max", 0, 0, 0);
        VelocityRandom(ParticleTable.Params p) { super(p); }
        @Override void init(ParticleSystem s, int from, int to) {
            double[] dir = new double[3], local = new double[3];
            for (int q = from; q < to; q++) {
                randomUnit(s, dir);
                float speed = s.random(min, max, 1);
                s.toWorldRight(controlPoint, lerp(localMin[0], localMax[0], s.random()), lerp(localMin[1], localMax[1], s.random()),
                    lerp(localMin[2], localMax[2], s.random()), local);
                addVelocity(s, q, dir[0] * speed + local[0], dir[1] * speed + local[1], dir[2] * speed + local[2]);
            }
        }
    }

    static final class VelocityNoise extends Initializer {
        final int controlPoint = cp(i("Control Point Number", 0));
        final Noise.Field[] axes = new Noise.Field[3];
        final float[] min = v("output minimum", 0, 0, 0), max = v("output maximum", 1, 1, 1);
        final boolean local = bool("Apply Velocity in Local Space (0/1)", false);
        VelocityNoise(ParticleTable.Params p) {
            super(p);
            float[] abs = v("Absolute Value", 0, 0, 0), invert = v("Invert Abs Value", 0, 0, 0);
            for (int a = 0; a < 3; a++) {
                axes[a] = new Noise.Field(f("Time Noise Coordinate Scale", 1), f("Spatial Noise Coordinate Scale", 0.01),
                    f("Time Coordinate Offset", 0), v("Spatial Coordinate Offset", 0, 0, 0), abs[a] != 0, invert[a] != 0);
            }
        }
        @Override void init(ParticleSystem s, int from, int to) {
            double[] out = new double[3];
            for (int q = from; q < to; q++) {
                double vx = lerp(min[0], max[0], axes[0].sample(s, q, 11)), vy = lerp(min[1], max[1], axes[1].sample(s, q, 23)),
                    vz = lerp(min[2], max[2], axes[2].sample(s, q, 37));
                if (local) { s.toWorld(controlPoint, vx, vy, vz, out); vx = out[0]; vy = out[1]; vz = out[2]; }
                addVelocity(s, q, vx, vy, vz);
            }
        }
    }

    static final class VelocityInherit extends Initializer {
        final int controlPoint = cp(i("control point number", 0));
        final float scale = f("velocity scale", 1);
        VelocityInherit(ParticleTable.Params p) { super(p); }
        @Override void init(ParticleSystem s, int from, int to) {
            double[] velocity = new double[3];
            s.controlPointVelocity(controlPoint, velocity);
            for (int q = from; q < to; q++) addVelocity(s, q, velocity[0] * scale, velocity[1] * scale, velocity[2] * scale);
        }
    }

    abstract static class Remap extends Initializer {
        final int output = i("output field", 3);
        final float outMin = f("output minimum", 0), outMax = f("output maximum", 1);
        final boolean scaleInitial = bool("output is scalar of initial random range", false);
        final float lifeStart = f("emitter lifetime start time (seconds)", -1), lifeEnd = f("emitter lifetime end time (seconds)", -1);
        Remap(ParticleTable.Params p) { super(p); }

        boolean inWindow(ParticleSystem s) {
            if (lifeStart >= 0 && s.time < lifeStart) return false;
            return !(lifeEnd >= 0 && s.time > lifeEnd);
        }

        void put(ParticleSystem s, int q, float t) {
            float value = lerp(outMin, outMax, t);
            write(s, output, q, value, scaleInitial);
        }
    }

    static final class RemapControlPoint extends Remap {
        final int controlPoint = cp(i("input control point number", 0)), axis = Math.max(0, Math.min(2, i("input field 0-2 X/Y/Z", 0)));
        final float inMin = f("input minimum", 0), inMax = f("input maximum", 1);
        RemapControlPoint(ParticleTable.Params p) { super(p); }
        @Override void init(ParticleSystem s, int from, int to) {
            if (!inWindow(s)) return;
            float value = (float) s.cpPosition[controlPoint][axis];
            float t = inMax == inMin ? 0 : clamp01((value - inMin) / (inMax - inMin));
            for (int q = from; q < to; q++) put(s, q, t);
        }
    }

    static final class RemapInitialScalar extends Remap {
        final int input = i("input field", 8);
        final float inMin = f("input minimum", 0), inMax = f("input maximum", 1);
        final boolean onlyInRange = bool("only active within specified input range", false);
        RemapInitialScalar(ParticleTable.Params p) { super(p); }
        @Override void init(ParticleSystem s, int from, int to) {
            if (!inWindow(s)) return;
            for (int q = from; q < to; q++) {
                float value = read(s, input, q);
                if (onlyInRange && (value < Math.min(inMin, inMax) || value > Math.max(inMin, inMax))) continue;
                put(s, q, inMax == inMin ? 0 : clamp01((value - inMin) / (inMax - inMin)));
            }
        }
    }

    static final class RemapInitialDistance extends Remap {
        final int controlPoint = cp(i("control point", 0));
        final float min = f("distance minimum", 0), max = f("distance maximum", 128);
        final boolean onlyWithin = bool("only active within specified distance", false);
        RemapInitialDistance(ParticleTable.Params p) { super(p); }
        @Override void init(ParticleSystem s, int from, int to) {
            double[] c = s.cpPosition[controlPoint];
            for (int q = from; q < to; q++) {
                double dx = s.x[q] - c[0], dy = s.y[q] - c[1], dz = s.z[q] - c[2];
                float distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (onlyWithin && (distance < min || distance > max)) continue;
                put(s, q, max == min ? 0 : clamp01((distance - min) / (max - min)));
            }
        }
    }

    static final class RemapNoise extends Remap {
        final Noise.Field noise;
        RemapNoise(ParticleTable.Params p) {
            super(p);
            noise = new Noise.Field(f("time noise coordinate scale", 0.1), f("spatial noise coordinate scale", 0.001), f("time coordinate offset", 0),
                v("spatial coordinate offset", 0, 0, 0), bool("absolute value", false), bool("invert absolute value", false));
        }
        @Override void init(ParticleSystem s, int from, int to) {
            for (int q = from; q < to; q++) put(s, q, noise.sample(s, q, 5));
        }
    }

    // ---------------------------------------------------------------- operators

    /** noclip's {@code Operator_LifespanDecay}. */
    static final class LifespanDecay extends Operator {
        LifespanDecay(ParticleTable.Params p) { super(p); }
        @Override void operate(ParticleSystem s, float weight) {
            for (int q = 0; q < s.count; q++) if (s.age(q) >= s.scalar[LIFE][q]) s.kill(q);
        }
    }

    /**
     * noclip's {@code Operator_MovementBasic}, with the forces and constraints it leaves out:
     * Verlet steps from the previous position, the last step's velocity scaled to this step's
     * length, gravity and the force generators as acceleration, then up to the constraint passes.
     */
    static final class MovementBasic extends Operator {
        final float[] gravity = v("gravity", 0, 0, 0);
        final float drag = f("drag", 0);
        final int passes = Math.max(1, Math.min(8, i("max constraint passes", 3)));
        private float[] ax = new float[0], ay = new float[0], az = new float[0];
        MovementBasic(ParticleTable.Params p) { super(p); }

        @Override void operate(ParticleSystem s, float weight) {
            if (ax.length < s.max) { ax = new float[s.max]; ay = new float[s.max]; az = new float[s.max]; }
            for (int q = 0; q < s.count; q++) { ax[q] = gravity[0]; ay[q] = gravity[1]; az[q] = gravity[2]; }
            for (Force force : s.forces) {
                float w = force.weight(s.time);
                if (w > 0) force.apply(s, ax, ay, az, w);
            }
            // C_OP_BasicMovement in SDK 2013's particles.a: the step back to the previous position,
            // scaled to this step and damped by (1 - drag) per thirtieth of a second, plus the
            // undamped acceleration times dt squared.
            float dt = s.dt, dt2 = dt * dt, scale = s.previousDt > 0 ? dt / s.previousDt : 1;
            float keep = (float) (scale * Math.pow(Math.max(0, 1 - drag), 30 * dt));
            for (int q = 0; q < s.count; q++) {
                float vx = (s.x[q] - s.px[q]) * keep, vy = (s.y[q] - s.py[q]) * keep, vz = (s.z[q] - s.pz[q]) * keep;
                s.px[q] = s.x[q]; s.py[q] = s.y[q]; s.pz[q] = s.z[q];
                s.x[q] += vx + ax[q] * dt2;
                s.y[q] += vy + ay[q] * dt2;
                s.z[q] += vz + az[q] * dt2;
            }
            for (int pass = 0; pass < passes && !s.constraints.isEmpty(); pass++) {
                for (Constraint constraint : s.constraints) constraint.apply(s);
            }
        }
    }

    /** noclip's {@code Operator_AlphaFadeInRandom}. */
    static final class AlphaFadeIn extends Operator {
        final float min = f("fade in time min", 0.25), max = f("fade in time max", 0.25), exponent = f("fade in time exponent", 1);
        final boolean proportional = bool("proportional 0/1", true);
        AlphaFadeIn(ParticleTable.Params p) { super(p); }
        @Override void operate(ParticleSystem s, float weight) {
            for (int q = 0; q < s.count; q++) {
                float end = s.stableRandom(q, 1, min, max, exponent);
                float t = proportional ? s.lifeFraction(q) : s.age(q);
                if (t >= end || end <= 0) continue;
                s.scalar[ALPHA][q] = s.initial[ALPHA][q] * clamp01(smoothstep(t / end));
            }
        }
    }

    /** noclip's {@code Operator_AlphaFadeOutRandom}, with the library's ease and bias parameters. */
    static final class AlphaFadeOut extends Operator {
        final float min = f("fade out time min", 0.25), max = f("fade out time max", 0.25), exponent = f("fade out time exponent", 1), bias = f("fade bias", 0.5);
        final boolean proportional = bool("proportional 0/1", true), ease = bool("ease in and out", true);
        AlphaFadeOut(ParticleTable.Params p) { super(p); }
        @Override void operate(ParticleSystem s, float weight) {
            for (int q = 0; q < s.count; q++) {
                float start = s.stableRandom(q, 2, min, max, exponent);
                float t = proportional ? s.lifeFraction(q) : s.age(q);
                float end = proportional ? 1 : s.scalar[LIFE][q];
                if (proportional) start = 1 - start; else start = end - start;
                if (t <= start) continue;
                float u = clamp01((end - t) / Math.max(1e-6F, end - start));
                u = ease ? smoothstep(u) : (bias != 0.5F ? schlickBias(u, bias) : u);
                s.scalar[ALPHA][q] = s.initial[ALPHA][q] * u;
            }
        }
    }

    /** noclip's {@code Operator_AlphaFadeInSimple}. */
    static final class AlphaFadeInSimple extends Operator {
        final float end = f("proportional fade in time", 0.25);
        AlphaFadeInSimple(ParticleTable.Params p) { super(p); }
        @Override void operate(ParticleSystem s, float weight) {
            if (end <= 0) return;
            for (int q = 0; q < s.count; q++) {
                float t = s.lifeFraction(q);
                if (t >= end) continue;
                s.scalar[ALPHA][q] = s.initial[ALPHA][q] * clamp01(smoothstep(t / end));
            }
        }
    }

    /** noclip's {@code Operator_AlphaFadeOutSimple}. */
    static final class AlphaFadeOutSimple extends Operator {
        final float duration = f("proportional fade out time", 0.25);
        AlphaFadeOutSimple(ParticleTable.Params p) { super(p); }
        @Override void operate(ParticleSystem s, float weight) {
            if (duration <= 0) return;
            float start = 1 - duration;
            for (int q = 0; q < s.count; q++) {
                float t = s.lifeFraction(q);
                if (t <= start) continue;
                s.scalar[ALPHA][q] = s.initial[ALPHA][q] * clamp01(smoothstep(1 - (t - start) / duration));
            }
        }
    }

    /** noclip's {@code Operator_AlphaFadeAndDecay}. */
    static final class AlphaFadeAndDecay extends Operator {
        final float startAlpha = f("start_alpha", 1), endAlpha = f("end_alpha", 0), startIn = f("start_fade_in_time", 0), endIn = f("end_fade_in_time", 0.5),
            startOut = f("start_fade_out_time", 0.5), endOut = f("end_fade_out_time", 1);
        AlphaFadeAndDecay(ParticleTable.Params p) { super(p); }
        @Override void operate(ParticleSystem s, float weight) {
            for (int q = 0; q < s.count; q++) {
                float life = s.scalar[LIFE][q], age = s.age(q);
                if (age >= life) { s.kill(q); continue; }
                float t = age / life, alpha = s.initial[ALPHA][q];
                if (t <= endIn) alpha *= lerp(startAlpha, 1, clamp01(smoothstep(inverse(startIn, endIn, t))));
                if (t >= startOut) alpha *= lerp(1, endAlpha, clamp01(smoothstep(inverse(startOut, endOut, t))));
                s.scalar[ALPHA][q] = alpha;
            }
        }
    }

    static float inverse(float a, float b, float t) { return b == a ? (t >= b ? 1 : 0) : (t - a) / (b - a); }

    /** noclip's {@code Operator_ColorFade}. */
    static final class ColorFade extends Operator {
        final float[] color = v("color_fade", 255, 255, 255, 255);
        final float start = f("fade_start_time", 0), end = f("fade_end_time", 1);
        final boolean ease = bool("ease_in_and_out", true);
        ColorFade(ParticleTable.Params p) { super(p); }
        @Override void operate(ParticleSystem s, float weight) {
            float fr = color[0] / 255F, fg = color[1] / 255F, fb = color[2] / 255F;
            for (int q = 0; q < s.count; q++) {
                float t = clamp01(inverse(start, end, s.lifeFraction(q)));
                if (ease) t = smoothstep(t);
                s.r[q] = lerp(s.ir[q], fr, t); s.g[q] = lerp(s.ig[q], fg, t); s.b[q] = lerp(s.ib[q], fb, t);
            }
        }
    }

    /** noclip's {@code Operator_RadiusScale}. */
    static final class RadiusScale extends Operator {
        final float start = f("start_time", 0), end = f("end_time", 1), from = f("radius_start_scale", 1), to = f("radius_end_scale", 1), bias = f("scale_bias", 0.5);
        final boolean ease = bool("ease_in_and_out", false);
        RadiusScale(ParticleTable.Params p) { super(p); }
        @Override void operate(ParticleSystem s, float weight) {
            for (int q = 0; q < s.count; q++) {
                float t = clamp01(inverse(start, end, s.lifeFraction(q)));
                if (ease) t = smoothstep(t); else if (bias != 0.5F) t = schlickBias(t, bias);
                s.scalar[RADIUS][q] = lerp(from, to, t) * s.initial[RADIUS][q];
            }
        }
    }

    /** A fixed spin in degrees per second, slowing to {@code spin_rate_min} by {@code spin_stop_time}. */
    static final class Spin extends Operator {
        final int field;
        final float rate = f("spin_rate_degrees", 0), stop = f("spin_stop_time", 0), min = f("spin_rate_min", 0);
        Spin(ParticleTable.Params p, int field) { super(p); this.field = field; }
        @Override void operate(ParticleSystem s, float weight) {
            for (int q = 0; q < s.count; q++) {
                float current = rate;
                if (stop > 0) {
                    float t = clamp01(s.age(q) / stop);
                    current = lerp(rate, Math.signum(rate) * Math.min(Math.abs(rate), Math.abs(min)), t);
                }
                s.scalar[field][q] += (float) Math.toRadians(current) * s.dt * weight;
            }
        }
    }

    static final class RotationBasic extends Operator {
        RotationBasic(ParticleTable.Params p) { super(p); }
        @Override void operate(ParticleSystem s, float weight) {
            for (int q = 0; q < s.count; q++) s.scalar[ROTATION][q] += s.scalar[ROTATION_SPEED][q] * s.dt;
        }
    }

    /**
     * Adds {@code rate * sin(...)} per second to a field during a part of each particle's life, with
     * a per-particle rate and frequency. Source's exact wave is not public.
     */
    static final class OscillateScalar extends Operator {
        final int field = i("oscillation field", 7);
        final float rateMin = f("oscillation rate min", 0), rateMax = f("oscillation rate max", 0), freqMin = f("oscillation frequency min", 1),
            freqMax = f("oscillation frequency max", 1), startMin = f("start time min", 0), startMax = f("start time max", 0),
            endMin = f("end time min", 1), endMax = f("end time max", 1), multiplier = f("oscillation multiplier", 2), phase = f("oscillation start phase", 0.5);
        final boolean proportionalFrequency = bool("proportional 0/1", true), proportionalTimes = bool("start/end proportional", true);
        OscillateScalar(ParticleTable.Params p) { super(p); }
        @Override void operate(ParticleSystem s, float weight) {
            float[] target = field >= 0 && field < s.scalar.length ? s.scalar[field] : null;
            if (target == null) return;
            for (int q = 0; q < s.count; q++) {
                float t = proportionalTimes ? s.lifeFraction(q) : s.age(q);
                float start = s.stableRandom(q, 3, startMin, startMax, 1), end = s.stableRandom(q, 4, endMin, endMax, 1);
                if (t < start || t > end) continue;
                float rate = s.stableRandom(q, 5, rateMin, rateMax, 1), frequency = s.stableRandom(q, 6, freqMin, freqMax, 1);
                float x = proportionalFrequency ? s.lifeFraction(q) : s.age(q);
                float wave = (float) Math.sin((x * frequency * multiplier + phase) * Math.PI);
                float delta = rate * wave * s.dt * weight;
                if (isAngle(field)) delta = (float) Math.toRadians(delta);
                target[q] += delta;
            }
        }
    }

    static final class OscillateScalarSimple extends Operator {
        final int field = i("oscillation field", 7);
        final float rate = f("oscillation rate", 0), frequency = f("oscillation frequency", 1), multiplier = f("oscillation multiplier", 2), phase = f("oscillation start phase", 0);
        OscillateScalarSimple(ParticleTable.Params p) { super(p); }
        @Override void operate(ParticleSystem s, float weight) {
            float[] target = field >= 0 && field < s.scalar.length ? s.scalar[field] : null;
            if (target == null) return;
            float wave = (float) Math.sin((s.time * frequency * multiplier + phase) * Math.PI);
            float delta = rate * wave * s.dt * weight;
            if (isAngle(field)) delta = (float) Math.toRadians(delta);
            for (int q = 0; q < s.count; q++) target[q] += delta;
        }
    }

    static final class OscillateVector extends Operator {
        final int field = i("oscillation field", 0);
        final float[] rateMin = v("oscillation rate min", 0, 0, 0), rateMax = v("oscillation rate max", 0, 0, 0),
            freqMin = v("oscillation frequency min", 1, 1, 1), freqMax = v("oscillation frequency max", 1, 1, 1);
        final float startMin = f("start time min", 0), startMax = f("start time max", 0), endMin = f("end time min", 1), endMax = f("end time max", 1),
            multiplier = f("oscillation multiplier", 2), phase = f("oscillation start phase", 0.5);
        final boolean proportionalFrequency = bool("proportional 0/1", true), proportionalTimes = bool("start/end proportional", true);
        OscillateVector(ParticleTable.Params p) { super(p); }
        @Override void operate(ParticleSystem s, float weight) {
            float[][] target = switch (field) {
                case XYZ -> new float[][]{s.x, s.y, s.z};
                case PREV_XYZ -> new float[][]{s.px, s.py, s.pz};
                case TINT -> new float[][]{s.r, s.g, s.b};
                default -> null;
            };
            if (target == null) return;
            for (int q = 0; q < s.count; q++) {
                float t = proportionalTimes ? s.lifeFraction(q) : s.age(q);
                if (t < s.stableRandom(q, 3, startMin, startMax, 1) || t > s.stableRandom(q, 4, endMin, endMax, 1)) continue;
                float x = proportionalFrequency ? s.lifeFraction(q) : s.age(q);
                for (int a = 0; a < 3; a++) {
                    float rate = s.stableRandom(q, 5 + a, rateMin[a], rateMax[a], 1), frequency = s.stableRandom(q, 9 + a, freqMin[a], freqMax[a], 1);
                    target[a][q] += rate * (float) Math.sin((x * frequency * multiplier + phase) * Math.PI) * s.dt * weight;
                }
            }
        }
    }

    /** Changes a field by a per-particle rate per second from a start time; the spline form eases out. */
    static final class RampScalar extends Operator {
        final int field = i("ramp field", 3);
        final float rateMin = f("ramp rate min", 0), rateMax = f("ramp rate max", 0), startMin = f("start time min", 0), startMax = f("start time max", 0),
            endMin = f("end time min", 1), endMax = f("end time max", 1);
        final boolean proportional = bool("proportional 0/1", false), spline, easeOut = bool("ease out", false);
        RampScalar(ParticleTable.Params p, boolean spline) { super(p); this.spline = spline; }
        @Override void operate(ParticleSystem s, float weight) {
            float[] target = field >= 0 && field < s.scalar.length ? s.scalar[field] : null;
            if (target == null) return;
            for (int q = 0; q < s.count; q++) {
                float t = proportional ? s.lifeFraction(q) : s.age(q);
                float start = s.stableRandom(q, 13, startMin, startMax, 1), end = s.stableRandom(q, 14, endMin, endMax, 1);
                if (t < start) continue;
                // The end time is newer than the defaults table: only an end the file sets stops the ramp.
                if ((p.has("end time min") || p.has("end time max")) && end > start && t > end) continue;
                float rate = s.stableRandom(q, 15, rateMin, rateMax, 1);
                if (spline && easeOut) rate *= 1 - clamp01(s.lifeFraction(q));
                float delta = rate * s.dt * weight;
                if (isAngle(field)) delta = (float) Math.toRadians(delta);
                target[q] += delta;
            }
        }
    }

    static final class RemapDistanceToControlPoint extends Operator {
        final int controlPoint = cp(i("control point", 0)), output = i("output field", 3);
        final float min = f("distance minimum", 0), max = f("distance maximum", 128), outMin = f("output minimum", 0), outMax = f("output maximum", 1);
        final boolean scaleInitial = bool("output is scalar of initial random range", false), onlyWithin = bool("only active within specified distance", false);
        RemapDistanceToControlPoint(ParticleTable.Params p) { super(p); }
        @Override void operate(ParticleSystem s, float weight) {
            double[] c = s.cpPosition[controlPoint];
            for (int q = 0; q < s.count; q++) {
                double dx = s.x[q] - c[0], dy = s.y[q] - c[1], dz = s.z[q] - c[2];
                float distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (onlyWithin && (distance < min || distance > max)) continue;
                write(s, output, q, lerp(outMin, outMax, max == min ? 0 : clamp01((distance - min) / (max - min))), scaleInitial);
            }
        }
    }

    static final class RemapDistanceBetweenControlPoints extends Operator {
        final int from = cp(i("starting control point", 0)), to = cp(i("ending control point", 1)), output = i("output field", 3);
        final float min = f("distance minimum", 0), max = f("distance maximum", 128), outMin = f("output minimum", 0), outMax = f("output maximum", 1);
        final boolean scaleInitial = bool("output is scalar of initial random range", false);
        RemapDistanceBetweenControlPoints(ParticleTable.Params p) { super(p); }
        @Override void operate(ParticleSystem s, float weight) {
            double[] a = s.cpPosition[from], b = s.cpPosition[to];
            double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
            float distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            float value = lerp(outMin, outMax, max == min ? 0 : clamp01((distance - min) / (max - min)));
            for (int q = 0; q < s.count; q++) write(s, output, q, value, scaleInitial);
        }
    }

    /** How far along the line between two control points the particle is, as a scalar. */
    static final class RemapPercentageBetweenControlPoints extends Operator {
        final int from = cp(i("starting control point", 0)), to = cp(i("ending control point", 1)), output = i("output field", 3);
        final float inMin = f("percentage minimum", 0), inMax = f("percentage maximum", 1), outMin = f("output minimum", 0), outMax = f("output maximum", 1);
        final boolean scaleInitial = bool("output is scalar of initial random range", false);
        RemapPercentageBetweenControlPoints(ParticleTable.Params p) { super(p); }
        @Override void operate(ParticleSystem s, float weight) {
            double[] a = s.cpPosition[from], b = s.cpPosition[to];
            double lx = b[0] - a[0], ly = b[1] - a[1], lz = b[2] - a[2], length2 = lx * lx + ly * ly + lz * lz;
            if (length2 < 1e-9) return;
            for (int q = 0; q < s.count; q++) {
                float t = (float) (((s.x[q] - a[0]) * lx + (s.y[q] - a[1]) * ly + (s.z[q] - a[2]) * lz) / length2);
                write(s, output, q, lerp(outMin, outMax, inMax == inMin ? 0 : clamp01((t - inMin) / (inMax - inMin))), scaleInitial);
            }
        }
    }

    /** Writes a control point's speed, remapped, into one coordinate of another control point. */
    static final class RemapSpeedToControlPoint extends Operator {
        final int input = cp(i("input control point", 0)), output = i("output control point", -1), axis = Math.max(0, Math.min(2, i("Output field 0-2 X/Y/Z", 0)));
        final float inMin = f("input minimum", 0), inMax = f("input maximum", 1), outMin = f("output minimum", 0), outMax = f("output maximum", 1);
        RemapSpeedToControlPoint(ParticleTable.Params p) { super(p); }
        @Override void operate(ParticleSystem s, float weight) {
            if (output < 0 || output >= CONTROL_POINTS) return;
            double[] velocity = new double[3];
            s.controlPointVelocity(input, velocity);
            float speed = (float) Math.sqrt(velocity[0] * velocity[0] + velocity[1] * velocity[1] + velocity[2] * velocity[2]);
            float value = lerp(outMin, outMax, inMax == inMin ? 0 : clamp01((speed - inMin) / (inMax - inMin)));
            double[] position = s.cpPosition[output].clone();
            position[axis] = value;
            s.setControlPoint(output, position[0], position[1], position[2]);
        }
    }

    static final class DampenToControlPoint extends Operator {
        final int controlPoint = cp(i("control_point_number", 0));
        final float range = f("falloff range", 100), scale = f("dampen scale", 1);
        DampenToControlPoint(ParticleTable.Params p) { super(p); }
        @Override void operate(ParticleSystem s, float weight) {
            if (range <= 0) return;
            double[] c = s.cpPosition[controlPoint];
            for (int q = 0; q < s.count; q++) {
                double dx = s.x[q] - c[0], dy = s.y[q] - c[1], dz = s.z[q] - c[2];
                float distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (distance >= range) continue;
                // C_OP_DampenToCP in particles.a: the step since the previous position is scaled by
                // (distance / range)^scale, at the operator's strength, moving the particle back.
                float change = (float) (Math.pow(distance / range, scale) - 1) * weight;
                s.x[q] += (s.x[q] - s.px[q]) * change;
                s.y[q] += (s.y[q] - s.py[q]) * change;
                s.z[q] += (s.z[q] - s.pz[q]) * change;
            }
        }
    }

    /** Moves particles with their control point's motion, less and less between the fade-out times. */
    static final class LockToControlPoint extends Operator {
        final int controlPoint = cp(i("control_point_number", 0));
        final float startMin = f("start_fadeout_min", 1), startMax = f("start_fadeout_max", 1), startExp = f("start_fadeout_exponent", 1),
            endMin = f("end_fadeout_min", 1), endMax = f("end_fadeout_max", 1), endExp = f("end_fadeout_exponent", 1), range = f("distance fade range", 0);
        LockToControlPoint(ParticleTable.Params p) { super(p); }
        @Override void operate(ParticleSystem s, float weight) {
            double[] now = s.cpPosition[controlPoint], before = s.cpPrevious[controlPoint];
            double dx = now[0] - before[0], dy = now[1] - before[1], dz = now[2] - before[2];
            if (dx == 0 && dy == 0 && dz == 0) return;
            for (int q = 0; q < s.count; q++) {
                float t = s.lifeFraction(q);
                float start = s.stableRandom(q, 17, startMin, startMax, startExp), end = s.stableRandom(q, 18, endMin, endMax, endExp);
                float lock = t <= start ? 1 : (end > start ? 1 - clamp01((t - start) / (end - start)) : 0);
                if (range > 0) {
                    double ex = s.x[q] - now[0], ey = s.y[q] - now[1], ez = s.z[q] - now[2];
                    lock *= 1 - clamp01((float) Math.sqrt(ex * ex + ey * ey + ez * ez) / range);
                }
                lock *= weight;
                s.x[q] += (float) (dx * lock); s.y[q] += (float) (dy * lock); s.z[q] += (float) (dz * lock);
                s.px[q] += (float) (dx * lock); s.py[q] += (float) (dy * lock); s.pz[q] += (float) (dz * lock);
            }
        }
    }

    static final class MaxVelocity extends Operator {
        final float max = f("Maximum Velocity", 0);
        MaxVelocity(ParticleTable.Params p) { super(p); }
        @Override void operate(ParticleSystem s, float weight) {
            if (max <= 0 || s.dt <= 0) return;
            float limit = max * s.dt;
            for (int q = 0; q < s.count; q++) {
                float vx = s.x[q] - s.px[q], vy = s.y[q] - s.py[q], vz = s.z[q] - s.pz[q];
                float length = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
                if (length <= limit) continue;
                float k = limit / length;
                s.px[q] = s.x[q] - vx * k; s.py[q] = s.y[q] - vy * k; s.pz[q] = s.z[q] - vz * k;
            }
        }
    }

    /** Kills a particle once it is behind the plane through the control point, offset along its normal. */
    static final class PlaneCull extends Operator {
        final int controlPoint = cp(i("Control Point for point on plane", 0));
        final float offset = f("Cull plane offset", 0);
        final float[] normal = v("Plane Normal", 0, 0, 1);
        PlaneCull(ParticleTable.Params p) { super(p); }
        @Override void operate(ParticleSystem s, float weight) {
            double[] c = s.cpPosition[controlPoint];
            for (int q = 0; q < s.count; q++) {
                double d = (s.x[q] - c[0]) * normal[0] + (s.y[q] - c[1]) * normal[1] + (s.z[q] - c[2]) * normal[2];
                if (d < offset) s.kill(q);
            }
        }
    }

    static final class SetControlPointPositions extends Operator {
        final int[] numbers = {i("First Control Point Number", 1), i("Second Control Point Number", 2), i("Third Control Point Number", 3), i("Fourth Control Point Number", 4)};
        final float[][] locations = {v("First Control Point Location", 128, 0, 0), v("Second Control Point Location", 0, 128, 0),
            v("Third Control Point Location", -128, 0, 0), v("Fourth Control Point Location", 0, -128, 0)};
        final boolean world = bool("Set positions in world space", false);
        final int base = cp(i("Control Point to offset positions from", 0));
        SetControlPointPositions(ParticleTable.Params p) { super(p); }
        @Override void operate(ParticleSystem s, float weight) {
            double[] out = new double[3];
            for (int k = 0; k < 4; k++) {
                int n = numbers[k];
                if (n <= 0 || n >= CONTROL_POINTS) continue;
                float[] l = locations[k];
                if (world) s.setControlPoint(n, l[0], l[1], l[2]);
                else {
                    s.toWorld(base, l[0], l[1], l[2], out);
                    double[] c = s.cpPosition[base];
                    s.setControlPoint(n, c[0] + out[0], c[1] + out[1], c[2] + out[2]);
                }
            }
        }
    }

    static final class SetChildControlPoints extends Operator {
        final int first = i("First control point to set", 0), count = i("# of control points to set", 1), firstParticle = i("first particle to copy", 0);
        SetChildControlPoints(ParticleTable.Params p) { super(p); }
        @Override void operate(ParticleSystem s, float weight) {
            for (int k = 0; k < count; k++) {
                int particle = firstParticle + k, point = first + k;
                if (particle >= s.count || point < 0 || point >= CONTROL_POINTS) break;
                for (ParticleSystem child : s.children) child.setControlPoint(point, s.x[particle], s.y[particle], s.z[particle]);
            }
        }
    }

    static final class SetControlPointToPlayer extends Operator {
        final int number = i("Control Point Number", 1);
        final float[] offset = v("Control Point Offset", 0, 0, 0);
        SetControlPointToPlayer(ParticleTable.Params p) { super(p); }
        @Override void operate(ParticleSystem s, float weight) {
            double[] eye = s.effect.playerEye();
            if (eye != null) s.setControlPoint(number, eye[0] + offset[0], eye[1] + offset[1], eye[2] + offset[2]);
        }
    }

    /** Traces from a control point along a direction and puts another control point where it hits. */
    static final class SetControlPointToImpact extends Operator {
        final int set = i("Control Point to Set", 1), from = cp(i("Control Point to Trace From", 0));
        final float length = f("Max Trace Length", 1024), offset = f("Offset End Point Amount", 0), rate = f("Trace Update Rate", 0);
        final float[] direction = v("Trace Direction Override", 0, 0, 0);
        private float next = -1;
        SetControlPointToImpact(ParticleTable.Params p) { super(p); }
        @Override void operate(ParticleSystem s, float weight) {
            if (set < 0 || set >= CONTROL_POINTS || s.time < next) return;
            next = rate > 0 ? s.time + rate : Float.MAX_VALUE;
            double[] dir = new double[3];
            if (direction[0] == 0 && direction[1] == 0 && direction[2] == 0) System.arraycopy(s.cpForward[from], 0, dir, 0, 3);
            else s.toWorld(from, direction[0], direction[1], direction[2], dir);
            double length2 = Math.sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2]);
            if (length2 < 1e-9) return;
            double[] c = s.cpPosition[from], hit = new double[7];
            double ex = c[0] + dir[0] / length2 * length, ey = c[1] + dir[1] / length2 * length, ez = c[2] + dir[2] / length2 * length;
            if (s.effect.trace(c[0], c[1], c[2], ex, ey, ez, hit)) s.setControlPoint(set, hit[0] + hit[3] * offset, hit[1] + hit[4] * offset, hit[2] + hit[5] * offset);
            else s.setControlPoint(set, ex, ey, ez);
        }
    }

    // ---------------------------------------------------------------- emitters

    /** noclip's {@code Emitter_Continuously}: spawn times spread over the step. */
    static final class Continuous extends Emitter {
        final float rate = f("emission_rate", 100), duration = f("emission_duration", 0), start = f("emission_start_time", 0);
        private double counter;
        private long emitted;
        Continuous(ParticleTable.Params p) { super(p); }

        @Override boolean active(ParticleSystem s) { return !(duration > 0 && s.time >= start + duration); }

        @Override void emit(ParticleSystem s) {
            if (rate <= 0 || s.time <= start || !active(s)) return;
            float previous = Math.max(start, s.time - s.dt);
            counter += rate * (s.time - previous);
            long total = (long) counter;
            int n = (int) Math.min(Integer.MAX_VALUE, total - emitted);
            if (n <= 0) return;
            emitted = total;
            s.create(n, previous, 1F / rate);
        }
    }

    static final class Instantaneous extends Emitter {
        final float start = f("emission_start_time", 0);
        final int minimum = i("num_to_emit_minimum", -1), number = i("num_to_emit", 100), perFrame = i("maximum emission per frame", -1),
            scaleControlPoint = i("emission count scale control point", -1), scaleField = Math.max(0, Math.min(2, i("emission count scale control point field", 0)));
        private int remaining = -1;
        Instantaneous(ParticleTable.Params p) { super(p); }

        @Override boolean active(ParticleSystem s) { return remaining != 0; }

        @Override void emit(ParticleSystem s) {
            if (s.time < start || remaining == 0) return;
            if (remaining < 0) {
                int n = minimum >= 0 && minimum < number ? minimum + (int) (s.random() * (number - minimum + 1)) : number;
                if (scaleControlPoint >= 0 && scaleControlPoint < CONTROL_POINTS) n = (int) (n * s.cpPosition[scaleControlPoint][scaleField]);
                remaining = Math.max(0, n);
            }
            int now = perFrame > 0 ? Math.min(perFrame, remaining) : remaining;
            s.create(now, s.time - s.dt, 0);
            remaining -= now;
        }
    }

    /** Emits at a rate that follows a noise of time between the emission minimum and maximum. */
    static final class NoiseEmitter extends Emitter {
        final float start = f("emission_start_time", 0), duration = f("emission_duration", 0), scale = f("time noise coordinate scale", 0.1),
            offset = f("time coordinate offset", 0), min = f("emission minimum", 0), max = f("emission maximum", 100);
        final boolean abs = bool("absolute value", false), invert = bool("invert absolute value", false);
        private double counter;
        NoiseEmitter(ParticleTable.Params p) { super(p); }

        @Override boolean active(ParticleSystem s) { return !(duration > 0 && s.time >= start + duration); }

        @Override void emit(ParticleSystem s) {
            if (s.time <= start || !active(s)) return;
            float n = Noise.unit(Noise.noise(s.time * scale + offset, 0.5, 0.5), abs, invert);
            float rate = lerp(min, max, n);
            counter += rate * s.dt;
            int count = (int) counter;
            if (count <= 0) return;
            counter -= count;
            s.create(count, s.time - s.dt, rate > 0 ? 1F / rate : 0);
        }
    }

    // ---------------------------------------------------------------- forces

    static final class RandomForce extends Force {
        final float[] min = v("min force", 0, 0, 0), max = v("max force", 0, 0, 0);
        RandomForce(ParticleTable.Params p) { super(p); }
        @Override void apply(ParticleSystem s, float[] ax, float[] ay, float[] az, float weight) {
            for (int q = 0; q < s.count; q++) {
                ax[q] += lerp(min[0], max[0], s.random()) * weight;
                ay[q] += lerp(min[1], max[1], s.random()) * weight;
                az[q] += lerp(min[2], max[2], s.random()) * weight;
            }
        }
    }

    /** Pushes particles around an axis through control point 0, along the tangent. */
    static final class TwistAroundAxis extends Force {
        final float amount = f("amount of force", 0);
        final float[] axis = v("twist axis", 0, 0, 1);
        final boolean local = bool("object local space axis 0/1", false);
        TwistAroundAxis(ParticleTable.Params p) { super(p); }
        @Override void apply(ParticleSystem s, float[] ax, float[] ay, float[] az, float weight) {
            double[] a = {axis[0], axis[1], axis[2]};
            if (local) { double[] out = new double[3]; s.transformAxis(0, a[0], a[1], a[2], out); a = out; }
            double length = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
            if (length < 1e-9) return;
            a[0] /= length; a[1] /= length; a[2] /= length;
            double[] c = s.cpPosition[0];
            for (int q = 0; q < s.count; q++) {
                double ox = s.x[q] - c[0], oy = s.y[q] - c[1], oz = s.z[q] - c[2];
                double tx = a[1] * oz - a[2] * oy, ty = a[2] * ox - a[0] * oz, tz = a[0] * oy - a[1] * ox;
                double t = Math.sqrt(tx * tx + ty * ty + tz * tz);
                if (t < 1e-9) continue;
                float k = (float) (amount * weight / t);
                ax[q] += (float) tx * k; ay[q] += (float) ty * k; az[q] += (float) tz * k;
            }
        }
    }

    /** Pulls towards a control point with {@code amount / distance^falloff}. */
    static final class PullToControlPoint extends Force {
        final float amount = f("amount of force", 0), falloff = f("falloff power", 2);
        final int controlPoint = cp(i("control point number", 0));
        PullToControlPoint(ParticleTable.Params p) { super(p); }
        @Override void apply(ParticleSystem s, float[] ax, float[] ay, float[] az, float weight) {
            double[] c = s.cpPosition[controlPoint];
            for (int q = 0; q < s.count; q++) {
                double dx = c[0] - s.x[q], dy = c[1] - s.y[q], dz = c[2] - s.z[q];
                double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (distance < 1e-3) continue;
                double force = amount * weight / Math.pow(distance, falloff);
                ax[q] += (float) (dx / distance * force); ay[q] += (float) (dy / distance * force); az[q] += (float) (dz / distance * force);
            }
        }
    }

    // ---------------------------------------------------------------- constraints

    /** Stops particles at the world: they bounce, slide, or die where their step meets a surface. */
    static final class CollisionViaTraces extends Constraint {
        final float bounce = f("amount of bounce", 0), slide = f("amount of slide", 0), radiusScale = f("radius scale", 1);
        final boolean kill = bool("kill particle on collision", false);
        CollisionViaTraces(ParticleTable.Params p) { super(p); }
        @Override void apply(ParticleSystem s) {
            double[] hit = new double[7];
            for (int q = 0; q < s.count; q++) {
                if (s.isDead(q)) continue;
                if (!s.effect.trace(s.px[q], s.py[q], s.pz[q], s.x[q], s.y[q], s.z[q], hit)) continue;
                if (kill) { s.kill(q); continue; }
                double nx = hit[3], ny = hit[4], nz = hit[5];
                double vx = s.x[q] - s.px[q], vy = s.y[q] - s.py[q], vz = s.z[q] - s.pz[q];
                double along = vx * nx + vy * ny + vz * nz;
                double tx = vx - along * nx, ty = vy - along * ny, tz = vz - along * nz;
                double rx = tx * slide - along * nx * bounce, ry = ty * slide - along * ny * bounce, rz = tz * slide - along * nz * bounce;
                double lift = Math.max(0.1, s.scalar[RADIUS][q] * radiusScale * 0.05);
                s.x[q] = (float) (hit[0] + nx * lift); s.y[q] = (float) (hit[1] + ny * lift); s.z[q] = (float) (hit[2] + nz * lift);
                s.px[q] = (float) (s.x[q] - rx); s.py[q] = (float) (s.y[q] - ry); s.pz[q] = (float) (s.z[q] - rz);
            }
        }
    }

    static final class ConstrainDistance extends Constraint {
        final float min = f("minimum distance", 0), max = f("maximum distance", 100);
        final int controlPoint = cp(i("control point number", 0));
        final float[] offset = v("offset of center", 0, 0, 0);
        final boolean global = bool("global center point", false);
        ConstrainDistance(ParticleTable.Params p) { super(p); }
        @Override void apply(ParticleSystem s) {
            double[] c = s.cpPosition[controlPoint], o = new double[3];
            if (global) { o[0] = offset[0]; o[1] = offset[1]; o[2] = offset[2]; }
            else s.transformAxis(controlPoint, offset[0], offset[1], offset[2], o);
            double cx = c[0] + o[0], cy = c[1] + o[1], cz = c[2] + o[2];
            for (int q = 0; q < s.count; q++) {
                double dx = s.x[q] - cx, dy = s.y[q] - cy, dz = s.z[q] - cz;
                double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (d < 1e-9) continue;
                double clamped = Math.max(min, Math.min(max, d));
                if (clamped == d) continue;
                double k = clamped / d;
                s.x[q] = (float) (cx + dx * k); s.y[q] = (float) (cy + dy * k); s.z[q] = (float) (cz + dz * k);
            }
        }
    }

    /** Keeps particles within a distance of the line between two control points, travelled over a time. */
    static final class ConstrainToPath extends Constraint {
        final float min = f("minimum distance", 0), max = f("maximum distance", 100), middle = f("maximum distance middle", -1), end = f("maximum distance end", -1),
            travel = f("travel time", 10), midPoint = f("mid point position", 0.5);
        final int start = cp(i("start control point number", 0)), finish = cp(i("end control point number", 0));
        ConstrainToPath(ParticleTable.Params p) { super(p); }
        @Override void apply(ParticleSystem s) {
            double[] a = s.cpPosition[start], b = s.cpPosition[finish];
            for (int q = 0; q < s.count; q++) {
                float t = travel > 0 ? clamp01(s.age(q) / travel) : 1;
                double cx = a[0] + (b[0] - a[0]) * t, cy = a[1] + (b[1] - a[1]) * t, cz = a[2] + (b[2] - a[2]) * t;
                float limit = max;
                if (middle >= 0 || end >= 0) {
                    float mid = middle >= 0 ? middle : max, last = end >= 0 ? end : max;
                    limit = t < midPoint ? lerp(max, mid, midPoint > 0 ? t / midPoint : 1) : lerp(mid, last, midPoint < 1 ? (t - midPoint) / (1 - midPoint) : 1);
                }
                double dx = s.x[q] - cx, dy = s.y[q] - cy, dz = s.z[q] - cz;
                double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (d < 1e-9) continue;
                double clamped = Math.max(min, Math.min(limit, d));
                if (clamped == d) continue;
                double k = clamped / d;
                s.x[q] = (float) (cx + dx * k); s.y[q] = (float) (cy + dy * k); s.z[q] = (float) (cz + dz * k);
            }
        }
    }
}
