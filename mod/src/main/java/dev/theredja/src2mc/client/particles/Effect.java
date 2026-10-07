package dev.theredja.src2mc.client.particles;

import dev.theredja.src2mc.bundle.ParticleTable;
import java.util.random.RandomGenerator;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * One started particle effect: a {@code CNewParticleEffect}, the system its entity or impact named
 * with its children, in the map's {@link Space}. Control point 0 is where it was started.
 */
final class Effect {
    final Space space;
    final ParticleTable table;
    final ParticleSystem root;
    final long seed;
    final ParticleTable.Material material;
    /** Map-local scale data for draw ordering: the world position of control point 0. */
    double sortX, sortY, sortZ;

    Effect(ParticleTable table, int system, Space space, long seed) {
        this(table, table.systems().get(system), space, seed);
    }

    /** An effect of a definition that is not in the table: the SDK's code effects. */
    Effect(ParticleTable table, ParticleTable.System definition, Space space, long seed) {
        this.space = space;
        this.table = table;
        this.seed = seed;
        RandomGenerator random = new java.util.SplittableRandom(seed);
        this.root = new ParticleSystem(this, table, definition, null, 0, random, 0);
        this.material = root.material;
    }

    void setOrigin(double x, double y, double z, double pitch, double yaw, double roll) {
        root.setControlPoint(0, x, y, z);
        double[] v = Angles.vectors(pitch, yaw, roll);
        root.setControlPointOrientation(0, new double[]{v[0], v[1], v[2]}, new double[]{-v[3], -v[4], -v[5]}, new double[]{v[6], v[7], v[8]});
        // A freshly started effect has not moved: no velocity on its first step.
        for (int i = 0; i < 3; i++) root.cpPrevious[0][i] = root.cpPosition[0][i];
        sortX = space.worldX(x); sortY = space.worldY(z); sortZ = space.worldZ(y);
    }

    /** Sets control point {@code index} to a position, keeping its orientation. */
    void setControlPoint(int index, double x, double y, double z) { root.setControlPoint(index, x, y, z); }

    /** Sets control point 0's frame from a forward direction, as {@code SetControlPointOrientation} with {@code VectorVectors}. */
    void setForward(int index, double fx, double fy, double fz) {
        double length = Math.sqrt(fx * fx + fy * fy + fz * fz);
        if (length < 1e-9) return;
        fx /= length; fy /= length; fz /= length;
        double rx, ry, rz;
        if (Math.abs(fx) < 1e-6 && Math.abs(fy) < 1e-6) { rx = 0; ry = -1; rz = 0; }
        else { rx = fy; ry = -fx; rz = 0; double l = Math.sqrt(rx * rx + ry * ry); rx /= l; ry /= l; }
        double ux = ry * fz - rz * fy, uy = rz * fx - rx * fz, uz = rx * fy - ry * fx;
        root.setControlPointOrientation(index, new double[]{fx, fy, fz}, new double[]{-rx, -ry, -rz}, new double[]{ux, uy, uz});
    }

    /**
     * A line through the world from one Source point to another; on a hit, {@code out} holds the hit
     * point and normal in Source coordinates and the fraction, and the result is true.
     */
    boolean trace(double sx, double sy, double sz, double ex, double ey, double ez, double[] out) {
        return world.trace(this, sx, sy, sz, ex, ey, ez, out);
    }

    /** What the effects see of the world; tests swap it for one without Minecraft. */
    interface World {
        boolean trace(Effect effect, double sx, double sy, double sz, double ex, double ey, double ez, double[] out);
        double[] playerEye(Effect effect);
    }

    static World world = new World() {
        @Override public boolean trace(Effect effect, double sx, double sy, double sz, double ex, double ey, double ez, double[] out) {
            return effect.traceLevel(sx, sy, sz, ex, ey, ez, out);
        }
        @Override public double[] playerEye(Effect effect) { return effect.levelEye(); }
    };

    private boolean traceLevel(double sx, double sy, double sz, double ex, double ey, double ez, double[] out) {
        var level = Minecraft.getInstance().level;
        if (level == null) return false;
        Vec3 from = new Vec3(space.worldX(sx), space.worldY(sz), space.worldZ(sy));
        Vec3 to = new Vec3(space.worldX(ex), space.worldY(ez), space.worldZ(ey));
        if (from.distanceToSqr(to) < 1e-12) return false;
        BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
        if (hit.getType() != HitResult.Type.BLOCK) return false;
        double[] point = new double[3];
        space.toSource(hit.getLocation().x, hit.getLocation().y, hit.getLocation().z, point);
        Direction face = hit.getDirection();
        out[0] = point[0]; out[1] = point[1]; out[2] = point[2];
        out[3] = face.getStepX(); out[4] = -face.getStepZ(); out[5] = face.getStepY();
        out[6] = from.distanceTo(hit.getLocation()) / from.distanceTo(to);
        return true;
    }

    /** The local player's eye in Source coordinates; null without a player. */
    double[] playerEye() { return world.playerEye(this); }

    private double[] levelEye() {
        var player = Minecraft.getInstance().player;
        if (player == null) return null;
        Vec3 eye = player.getEyePosition();
        double[] out = new double[3];
        space.toSource(eye.x, eye.y, eye.z, out);
        return out;
    }

    void simulate(float seconds) { root.simulate(seconds); }

    boolean finished() { return root.finished(); }

    void stop() { root.stopEmission(); }

    int particles() { return root.totalParticles(); }
}
