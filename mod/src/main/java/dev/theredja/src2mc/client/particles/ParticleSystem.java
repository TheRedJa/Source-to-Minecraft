package dev.theredja.src2mc.client.particles;

import dev.theredja.src2mc.bundle.ParticleTable;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * One running particle system: a {@code CParticleCollection}. Its particles live in arrays, one per
 * attribute, indexed as Source's {@code PARTICLE_ATTRIBUTE_*} numbers them (public
 * {@code particles.h}), so the remap functions can name a field by number. Everything is in Source
 * units and axes; the map's placement turns it into the world only when drawn.
 *
 * <p>A step is noclip.website's order: emitters create particles and the initializers set them up,
 * then every operator runs in the file's order. Movement Basic applies the forces and constraints,
 * as Source's {@code C_OP_BasicMovement} does.
 */
final class ParticleSystem {
    // Attribute numbers, particles.h.
    static final int XYZ = 0, LIFE = 1, PREV_XYZ = 2, RADIUS = 3, ROTATION = 4, ROTATION_SPEED = 5, TINT = 6, ALPHA = 7,
        CREATION = 8, SEQUENCE = 9, TRAIL = 10, ID = 11, YAW = 12, SEQUENCE1 = 13, ALPHA2 = 16;
    static final int CONTROL_POINTS = 64;

    final Effect effect;
    final ParticleTable.System definition;
    final ParticleTable table;
    final ParticleTable.Material material;
    final ParticleSystem parent;
    final List<ParticleSystem> children = new ArrayList<>();
    final List<Functions.Emitter> emitters = new ArrayList<>();
    final List<Functions.Initializer> initializers = new ArrayList<>();
    final List<Functions.Operator> operators = new ArrayList<>();
    final List<Functions.Force> forces = new ArrayList<>();
    final List<Functions.Constraint> constraints = new ArrayList<>();
    final List<Renderers.Renderer> renderers = new ArrayList<>();

    final int max;
    int count;
    final float[] x, y, z, px, py, pz, r, g, b, ir, ig, ib;
    /** Per-particle values of the SDK's code effects (end size, end alpha), which have no attribute. */
    final float[] aux0, aux1;
    /** By attribute number; null for the vector ones. */
    final float[][] scalar = new float[17][];
    /** Each scalar as the initializers left it, for the operators that scale it over a life. */
    final float[][] initial = new float[17][];
    int nextId;

    /** Seconds since the system started; negative while a child waits for its delay. */
    float time;
    float dt, previousDt;
    boolean emitting = true;
    final RandomGenerator random;

    // Control points: position, orientation (forward, left, up columns) and last position for velocity.
    final double[][] cpPosition = new double[CONTROL_POINTS][3];
    final double[][] cpPrevious = new double[CONTROL_POINTS][3];
    final double[][] cpForward = new double[CONTROL_POINTS][];
    final double[][] cpLeft = new double[CONTROL_POINTS][];
    final double[][] cpUp = new double[CONTROL_POINTS][];
    int highestControlPoint;

    // Defaults of the definition, for streams nothing initializes.
    final float defaultRadius, defaultAlpha, defaultRotation, defaultRotationSpeed;
    final float defaultR, defaultG, defaultB;
    final int defaultSequence, defaultSequence1;
    final float maxTimeStep;

    private final boolean[] dead;
    private int deadCount;

    ParticleSystem(Effect effect, ParticleTable table, ParticleTable.System definition, ParticleSystem parent, float delay,
                   RandomGenerator random, int depth) {
        this.effect = effect;
        this.table = table;
        this.definition = definition;
        this.parent = parent;
        this.random = random;
        this.material = definition.material() >= 0 ? table.materials().get(definition.material()) : null;
        ParticleTable.Params a = definition.attributes();
        max = Math.max(0, Math.min(5000, a.integer("max_particles", 1000)));
        x = new float[max]; y = new float[max]; z = new float[max];
        px = new float[max]; py = new float[max]; pz = new float[max];
        r = new float[max]; g = new float[max]; b = new float[max];
        ir = new float[max]; ig = new float[max]; ib = new float[max];
        aux0 = new float[max]; aux1 = new float[max];
        for (int field : new int[]{LIFE, RADIUS, ROTATION, ROTATION_SPEED, ALPHA, CREATION, SEQUENCE, TRAIL, ID, YAW, SEQUENCE1, ALPHA2}) {
            scalar[field] = new float[max];
            initial[field] = new float[max];
        }
        dead = new boolean[max];
        double[] color = a.vector("color", 255, 255, 255, 255);
        defaultR = (float) (color[0] / 255); defaultG = (float) (color[1] / 255); defaultB = (float) (color[2] / 255);
        defaultAlpha = (float) (color[3] / 255);
        defaultRadius = (float) a.number("radius", 5);
        defaultRotation = (float) Math.toRadians(a.number("rotation", 0));
        defaultRotationSpeed = (float) Math.toRadians(a.number("rotation_speed", 0));
        defaultSequence = a.integer("sequence_number", 0);
        defaultSequence1 = a.integer("sequence_number 1", 0);
        double step = a.number("maximum time step", 0.1);
        maxTimeStep = (float) (step > 0 ? step : 0.1);
        time = -delay;
        for (int i = 0; i < CONTROL_POINTS; i++) {
            cpForward[i] = new double[]{1, 0, 0};
            cpLeft[i] = new double[]{0, 1, 0};
            cpUp[i] = new double[]{0, 0, 1};
        }
        if (parent != null) copyControlPoints(parent);
        for (ParticleTable.Function f : definition.emitters()) { var fn = Functions.emitter(f); if (fn != null) emitters.add(fn); }
        for (ParticleTable.Function f : definition.initializers()) { var fn = Functions.initializer(f); if (fn != null) initializers.add(fn); }
        for (ParticleTable.Function f : definition.operators()) { var fn = Functions.operator(f); if (fn != null) operators.add(fn); }
        for (ParticleTable.Function f : definition.forces()) { var fn = Functions.force(f); if (fn != null) forces.add(fn); }
        for (ParticleTable.Function f : definition.constraints()) { var fn = Functions.constraint(f); if (fn != null) constraints.add(fn); }
        for (ParticleTable.Function f : definition.renderers()) { var fn = Renderers.create(f); if (fn != null) renderers.add(fn); }
        if (depth < 8) {
            for (ParticleTable.Child child : definition.children()) {
                children.add(new ParticleSystem(effect, table, table.systems().get(child.system()), this, child.delay(), random, depth + 1));
            }
        }
        int initial = Math.max(0, Math.min(max, a.integer("initial_particles", 0)));
        pendingInitial = initial;
    }

    private int pendingInitial;

    private void copyControlPoints(ParticleSystem from) {
        for (int i = 0; i < CONTROL_POINTS; i++) {
            System.arraycopy(from.cpPosition[i], 0, cpPosition[i], 0, 3);
            System.arraycopy(from.cpPrevious[i], 0, cpPrevious[i], 0, 3);
            cpForward[i] = from.cpForward[i].clone();
            cpLeft[i] = from.cpLeft[i].clone();
            cpUp[i] = from.cpUp[i].clone();
        }
        highestControlPoint = from.highestControlPoint;
    }

    /** Sets a control point here and in every child, as {@code CNewParticleEffect::SetControlPoint} does. */
    void setControlPoint(int index, double cx, double cy, double cz) {
        if (index < 0 || index >= CONTROL_POINTS) return;
        cpPosition[index][0] = cx; cpPosition[index][1] = cy; cpPosition[index][2] = cz;
        highestControlPoint = Math.max(highestControlPoint, index);
        for (ParticleSystem child : children) child.setControlPoint(index, cx, cy, cz);
    }

    /** Sets a control point's forward, left and up axes here and in every child. */
    void setControlPointOrientation(int index, double[] forward, double[] left, double[] up) {
        if (index < 0 || index >= CONTROL_POINTS) return;
        cpForward[index] = forward.clone(); cpLeft[index] = left.clone(); cpUp[index] = up.clone();
        for (ParticleSystem child : children) child.setControlPointOrientation(index, forward, left, up);
    }

    /** A local offset turned into the control point's frame: {@code x forward + y left + z up}. */
    void toWorld(int cp, double lx, double ly, double lz, double[] out) {
        double[] f = cpForward[cp], l = cpLeft[cp], u = cpUp[cp];
        out[0] = f[0] * lx + l[0] * ly + u[0] * lz;
        out[1] = f[1] * lx + l[1] * ly + u[1] * lz;
        out[2] = f[2] * lx + l[2] * ly + u[2] * lz;
    }

    /**
     * A local velocity as Source's initializers turn it from the raw orientation vectors
     * ({@code GetControlPointOrientationAtTime}): {@code x forward + y right + z up}. Right is minus
     * the matrix's left column, so local Y runs the other way from {@link #toWorld}; the compiled
     * {@code C_INIT_CreateWithinSphere} and {@code C_INIT_VelocityRandom} of SDK 2013's
     * {@code particles.a} read it so.
     */
    void toWorldRight(int cp, double lx, double ly, double lz, double[] out) {
        double[] f = cpForward[cp], l = cpLeft[cp], u = cpUp[cp];
        out[0] = f[0] * lx - l[0] * ly + u[0] * lz;
        out[1] = f[1] * lx - l[1] * ly + u[1] * lz;
        out[2] = f[2] * lx - l[2] * ly + u[2] * lz;
    }

    /** {@code CParticleCollection::TransformAxis} in local space: {@code x right + y forward + z up}. */
    void transformAxis(int cp, double lx, double ly, double lz, double[] out) {
        double[] f = cpForward[cp], l = cpLeft[cp], u = cpUp[cp];
        out[0] = -l[0] * lx + f[0] * ly + u[0] * lz;
        out[1] = -l[1] * lx + f[1] * ly + u[1] * lz;
        out[2] = -l[2] * lx + f[2] * ly + u[2] * lz;
    }

    /** The control point's velocity over the last step, units per second. */
    void controlPointVelocity(int cp, double[] out) {
        double step = previousDt > 0 ? previousDt : dt;
        for (int i = 0; i < 3; i++) out[i] = step > 0 ? (cpPosition[cp][i] - cpPrevious[cp][i]) / step : 0;
    }

    float random() { return random.nextFloat(); }

    float random(float min, float max, float exponent) {
        if (min == max) return min;
        float v = random.nextFloat();
        if (exponent != 1) v = (float) Math.pow(v, exponent);
        return min + (max - min) * v;
    }

    /** A per-particle value that stays the same every step: Source's {@code RandomFloat(particle ID + operator)}. */
    float stableRandom(int particle, int salt) {
        long h = (long) scalar[ID][particle] * 0x9E3779B97F4A7C15L + salt * 0xC2B2AE3D27D4EB4FL + effect.seed;
        h ^= h >>> 33; h *= 0xFF51AFD7ED558CCDL; h ^= h >>> 33;
        return (h >>> 40) / (float) (1 << 24);
    }

    float stableRandom(int particle, int salt, float min, float max, float exponent) {
        float v = stableRandom(particle, salt);
        if (exponent != 1) v = (float) Math.pow(v, exponent);
        return min + (max - min) * v;
    }

    float age(int p) { return time - scalar[CREATION][p]; }

    /** Age over lifetime, 0..1 and beyond. */
    float lifeFraction(int p) {
        float life = scalar[LIFE][p];
        return life > 0 ? age(p) / life : 1;
    }

    void kill(int p) {
        if (p < 0 || p >= count || dead[p]) return;
        dead[p] = true;
        deadCount++;
    }

    boolean isDead(int p) { return dead[p]; }

    private void removeDead() {
        if (deadCount == 0) return;
        int write = 0;
        for (int read = 0; read < count; read++) {
            if (dead[read]) { dead[read] = false; continue; }
            if (write != read) copy(read, write);
            write++;
        }
        count = write;
        deadCount = 0;
    }

    private void copy(int from, int to) {
        x[to] = x[from]; y[to] = y[from]; z[to] = z[from];
        px[to] = px[from]; py[to] = py[from]; pz[to] = pz[from];
        r[to] = r[from]; g[to] = g[from]; b[to] = b[from];
        ir[to] = ir[from]; ig[to] = ig[from]; ib[to] = ib[from];
        aux0[to] = aux0[from]; aux1[to] = aux1[from];
        for (int f = 0; f < scalar.length; f++) {
            if (scalar[f] == null) continue;
            scalar[f][to] = scalar[f][from];
            initial[f][to] = initial[f][from];
        }
    }

    /**
     * Creates up to {@code n} particles born over the last step, each with {@code spawnTimes[i]};
     * returns how many were made.
     */
    int create(int n, float firstTime, float spacing) {
        int made = Math.max(0, Math.min(n, max - count));
        for (int i = 0; i < made; i++) {
            int p = count++;
            float born = firstTime + spacing * i;
            x[p] = (float) cpPosition[0][0]; y[p] = (float) cpPosition[0][1]; z[p] = (float) cpPosition[0][2];
            px[p] = x[p]; py[p] = y[p]; pz[p] = z[p];
            r[p] = defaultR; g[p] = defaultG; b[p] = defaultB;
            scalar[LIFE][p] = 1;
            scalar[RADIUS][p] = defaultRadius;
            scalar[ROTATION][p] = defaultRotation;
            scalar[ROTATION_SPEED][p] = defaultRotationSpeed;
            scalar[ALPHA][p] = defaultAlpha;
            scalar[ALPHA2][p] = 1;
            scalar[CREATION][p] = born;
            scalar[SEQUENCE][p] = defaultSequence;
            scalar[SEQUENCE1][p] = defaultSequence1;
            scalar[TRAIL][p] = 0.1F;
            scalar[ID][p] = nextId++;
            scalar[YAW][p] = 0;
            dead[p] = false;
        }
        int from = count - made;
        if (made > 0) {
            for (Functions.Initializer initializer : initializers) initializer.init(this, from, count);
            for (int p = from; p < count; p++) {
                ir[p] = r[p]; ig[p] = g[p]; ib[p] = b[p];
                for (int f = 0; f < scalar.length; f++) if (scalar[f] != null) initial[f][p] = scalar[f][p];
            }
        }
        return made;
    }

    boolean emitActive() {
        if (!emitting) return false;
        for (Functions.Emitter emitter : emitters) if (emitter.active(this)) return true;
        return false;
    }

    boolean finished() {
        if (count > 0 || pendingInitial > 0) return false;
        if (time < 0) return false;
        if (emitActive()) return false;
        for (ParticleSystem child : children) if (!child.finished()) return false;
        return true;
    }

    void stopEmission() {
        emitting = false;
        for (ParticleSystem child : children) child.stopEmission();
    }

    /** Advances by {@code seconds}, in steps no longer than the definition's maximum time step. */
    void simulate(float seconds) {
        while (seconds > 1e-5F) {
            float step = Math.min(seconds, maxTimeStep);
            step(step);
            seconds -= step;
        }
    }

    private void step(float step) {
        previousDt = dt > 0 ? dt : step;
        dt = step;
        time += step;
        if (time > 0) {
            if (pendingInitial > 0) {
                create(pendingInitial, time - step, 0);
                pendingInitial = 0;
            }
            if (emitting) for (Functions.Emitter emitter : emitters) emitter.emit(this);
            for (Functions.Operator operator : operators) {
                float weight = operator.weight(time);
                if (weight <= 0) continue;
                operator.operate(this, weight);
                removeDead();
            }
        }
        for (ParticleSystem child : children) child.step(step);
        for (int i = 0; i < CONTROL_POINTS; i++) System.arraycopy(cpPosition[i], 0, cpPrevious[i], 0, 3);
    }

    int totalParticles() {
        int total = count;
        for (ParticleSystem child : children) total += child.totalParticles();
        return total;
    }
}
