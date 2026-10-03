package dev.theredja.src2mc.logic;

import dev.theredja.src2mc.bundle.LogicTable;
import dev.theredja.src2mc.world.MoverRegistry;
import net.minecraft.nbt.CompoundTag;
import org.joml.Quaterniond;

/**
 * Track trains, their path and rotators, after the SDK's {@code trains.cpp},
 * {@code pathtrack.cpp} and {@code bmodels.cpp}. A train runs along its path at its speed: each
 * logic tick it moves on by what its speed covers, firing the path's {@code OnPass} as Source's
 * {@code CFuncTrackTrain::Next} does, and between ticks its pose is where that same move puts it
 * at the moment asked, so Sable's substeps see it glide along the path. A rotator turns about one
 * axis at a speed that only changes in its own steps.
 *
 * <p>Positions are map-local blocks; speeds and lengths are Source units, as the map gives them.
 */
final class Trains {
    private Trains() {}

    private static final double UNITS = 32.0;

    // ---- path_track.

    /** {@code CPathTrack}: one node of a path, linked to the next by its {@code target}. */
    static final class PathTrack extends LogicEntity {
        static final int DISABLED = 1, ALTREVERSE = 4, DISABLE_TRAIN = 8, TELEPORT = 16, ALTERNATE = 0x8000;

        private PathTrack next, previous, alternate;
        /** The node's {@code speed}: a train arriving under the path's control takes it, unless it is 0. */
        double speed;

        PathTrack(MapLogic map, int index, LogicTable.Entity entity) { super(map, index, entity); }

        @Override void spawn() { speed = number("speed", 0); }

        /** {@code CPathTrack::Link}: its next node, and the next's way back to it. */
        @Override void activate() {
            String target = key("target");
            if (target != null && !target.isEmpty()) {
                LogicEntity found = map.findFirst(target, this, null, null);
                if (found instanceof PathTrack track && found != this) {
                    next = track;
                    track.setPrevious(this);
                }
            }
            String alt = key("altpath");
            if (alt != null && !alt.isEmpty() && map.findFirst(alt, this, null, null) instanceof PathTrack track) {
                alternate = track;
                track.setPrevious(this);
            }
        }

        /** Only set from a node that is not this one's own alternate. */
        private void setPrevious(PathTrack track) {
            String alt = key("altpath");
            if (alt == null || !alt.equalsIgnoreCase(track.name())) previous = track;
        }

        PathTrack next() {
            return alternate != null && hasSpawnFlags(ALTERNATE) && !hasSpawnFlags(ALTREVERSE) ? alternate : next;
        }

        PathTrack previous() {
            return alternate != null && hasSpawnFlags(ALTERNATE) && hasSpawnFlags(ALTREVERSE) ? alternate : previous;
        }

        PathTrack nextInDirection(boolean forward) { return forward ? next() : previous(); }

        double[] at() {
            double[] position = position();
            return position == null ? new double[3] : position;
        }

        /** {@code ValidPath}: a disabled node ends the path for a train actually moving. */
        static PathTrack valid(PathTrack track, boolean move) {
            return track == null || (move && track.hasSpawnFlags(DISABLED)) ? null : track;
        }

        /**
         * {@code CPathTrack::LookAhead}: from {@code origin} on the segment leaving this node, goes
         * {@code distance} blocks along the path (backwards when negative), moving {@code origin}
         * there. Returns the node the point is past, or null when the path ends first; then a
         * moving train's origin stops at the last node, and a look ahead runs on past it along the
         * last segment. {@code nextNext}, if given, receives the node after the one returned.
         */
        PathTrack lookAhead(double[] origin, double distance, boolean move, PathTrack[] nextNext) {
            PathTrack current = this;
            double original = distance;
            double[] position = origin.clone();
            boolean forward = true;
            if (distance < 0) { distance = -distance; forward = false; }
            while (distance > 0) {
                PathTrack ahead = valid(current.nextInDirection(forward), move);
                if (ahead == null) {
                    if (!move) project(current.nextInDirection(!forward), current, origin, distance);
                    return null;
                }
                double[] to = ahead.at();
                double dx = to[0] - position[0], dy = to[1] - position[1], dz = to[2] - position[2];
                double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (length == 0 && valid(ahead.nextInDirection(forward), move) == null) {
                    if (nextNext != null) nextNext[0] = null;
                    return distance == original ? null : ahead;
                }
                if (length > distance) {
                    double f = distance / length;
                    origin[0] = position[0] + dx * f;
                    origin[1] = position[1] + dy * f;
                    origin[2] = position[2] + dz * f;
                    if (nextNext != null) nextNext[0] = ahead;
                    return current;
                }
                distance -= length;
                position = to;
                current = ahead;
                System.arraycopy(position, 0, origin, 0, 3);
            }
            if (nextNext != null) nextNext[0] = current.nextInDirection(forward);
            return current;
        }

        /** {@code CPathTrack::Project}: past {@code end}, along the way from {@code start} to it. */
        private static void project(PathTrack start, PathTrack end, double[] origin, double distance) {
            if (start == null || end == null) return;
            double[] a = start.at(), b = end.at();
            double dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
            double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (length == 0) return;
            origin[0] = b[0] + dx / length * distance;
            origin[1] = b[1] + dy / length * distance;
            origin[2] = b[2] + dz / length * distance;
        }

        /** {@code GetOrientation}: the node's own angles, or the way the path leaves it. */
        double[] orientation(boolean forward) {
            if (Variant.integer(key("orientationtype", "1")) == 2) return MoverPose.parseAngles(key("angles"));
            PathTrack from = this, to = nextInDirection(forward);
            if (to == null) { from = nextInDirection(!forward); to = this; }
            if (from == null) return new double[3];
            double[] a = from.at(), b = to.at();
            return MoverPose.vectorAngles(b[0] - a[0], b[1] - a[1], b[2] - a[2]);
        }

        @Override boolean accept(String input, String value, Actor activator, LogicEntity caller) {
            switch (input) {
                case "inpass" -> fire("onpass", activator, null);
                case "inteleport" -> fire("onteleport", activator, null);
                case "enablepath" -> spawnflags &= ~DISABLED;
                case "disablepath" -> spawnflags |= DISABLED;
                case "togglepath" -> spawnflags ^= DISABLED;
                case "enablealternatepath" -> spawnflags |= ALTERNATE;
                case "disablealternatepath" -> spawnflags &= ~ALTERNATE;
                case "togglealternatepath" -> spawnflags ^= ALTERNATE;
                default -> { return false; }
            }
            return true;
        }
    }

    // ---- func_tracktrain.

    /** {@code CFuncTrackTrain}. */
    static final class TrackTrain extends LogicEntity {
        private static final int NOPITCH = 1, NOCONTROL = 2, FIXED_ORIENTATION = 16;
        private static final int VELOCITY_INSTANT = 0, VELOCITY_EASE = 2;
        private static final int ORIENT_FIXED = 0, ORIENT_AT_PATH = 1, ORIENT_EASE = 3;

        private double maxSpeed, length, height;
        private int velocityType, orientationType;
        private double accelSpeed, decelSpeed;

        /** {@code m_ppath}: the node the train last passed in its direction of travel. */
        private PathTrack path;
        /** The train's origin less its height, where it stands at {@link #since}. */
        private double[] point;
        private double[] angles = new double[3];
        /** The {@code angles} the map gives the train: its compiled geometry already faces that way. */
        private Quaterniond spawnInverse = new Quaterniond();
        private double since;
        private double speed, oldSpeed, dir = 1;
        private boolean found, accelerating;
        private double desiredSpeed, forwardModifier = 1, unmodifiedSpeed;

        TrackTrain(MapLogic map, int index, LogicTable.Entity entity) { super(map, index, entity); }

        @Override void spawn() {
            maxSpeed = number("startspeed", 0);
            speed = number("speed", 0);
            if (maxSpeed == 0) maxSpeed = speed == 0 ? 100 : speed;
            length = number("wheels", 0);
            height = number("height", 0);
            velocityType = Variant.integer(key("velocitytype"));
            orientationType = Variant.integer(key("orientationtype", "1"));
            accelSpeed = number("manualaccelspeed", 0);
            decelSpeed = number("manualdecelspeed", 0);
            angles = MoverPose.parseAngles(key("angles"));
            spawnInverse = MoverPose.angles(angles[0], angles[1], angles[2]).invert();
            dir = 1;
            // Spawn: find the path on the next frame, once every node has linked.
            nextThink = map.time();
        }

        private boolean forward() { return dir == 1; }

        private double[] origin(double[] at) { return new double[]{at[0], at[1] + height / UNITS, at[2]}; }

        /** {@code Find}: onto the first node, facing along the path, which it then counts as arrived at. */
        private void find() {
            found = true;
            LogicEntity target = map.findFirst(key("target"), this, null, null);
            if (!(target instanceof PathTrack track)) return;
            path = track;
            point = track.at();
            since = map.time();
            if (!hasSpawnFlags(FIXED_ORIENTATION)) {
                double[] look = point.clone();
                track.lookAhead(look, length / UNITS, false, null);
                angles = MoverPose.vectorAngles(look[0] - point[0], look[1] - point[1], look[2] - point[2]);
                if (hasSpawnFlags(NOPITCH)) angles[0] = 0;
            }
            arrive(track);
        }

        /** {@code ArriveAtNode}. */
        private void arrive(PathTrack node) {
            node.input("inpass", null, this, this);
            if (node.hasSpawnFlags(PathTrack.DISABLE_TRAIN)) spawnflags |= NOCONTROL;
            if (hasSpawnFlags(NOCONTROL) && node.speed != 0) setSpeed(node.speed, false);
        }

        @Override void think() {
            if (!found) {
                find();
                if (speed != 0) schedule();
                return;
            }
            if (speed == 0 && !accelerating) return;
            advance();
            if (speed != 0 || accelerating) schedule();
        }

        private void schedule() { nextThink = map.time() + MapLogic.TICK_SECONDS; }

        /**
         * {@code Next}, one tick of it: the train moves on from where it stood by what its speed
         * covers since, arrives at a node it passes, and at the path's end stops there.
         */
        private void advance() {
            if (path == null || point == null) { speed = 0; return; }
            double now = map.time();
            double elapsed = now - since;
            PathTrack[] nextNext = new PathTrack[1];
            double[] moved = point.clone();
            PathTrack reached = path.lookAhead(moved, speed / UNITS * elapsed, true, nextNext);
            if (reached == null && accelerating && (speed < 0) != (desiredSpeed < 0)) reached = path;
            point = moved;
            since = now;
            if (reached == null) {
                oldSpeed = speed;
                speed = 0;
                accelerating = false;
                deadEnd();
                return;
            }
            updateSpeed(reached, nextNext[0]);
            angles = orientation(reached, nextNext[0], point, angles);
            if (reached != path) {
                path = reached;
                arrive(reached);
                PathTrack teleport = reached.next();
                if (teleport != null && teleport.hasSpawnFlags(PathTrack.TELEPORT)) teleportTo(teleport);
            }
            fire("onnextpoint", reached, null);
        }

        /** {@code UpdateTrainVelocity}: the blend between node speeds, or the approach to a set speed. */
        private void updateSpeed(PathTrack previous, PathTrack next) {
            if (velocityType == VELOCITY_INSTANT) return;
            if (accelerating) {
                double rate = Math.abs(desiredSpeed) > Math.abs(speed) ? accelSpeed : decelSpeed;
                double step = rate * MapLogic.TICK_SECONDS;
                speed = speed < desiredSpeed ? Math.min(desiredSpeed, speed + step) : Math.max(desiredSpeed, speed - step);
                if (speed == desiredSpeed) accelerating = false;
                return;
            }
            if (previous == null || next == null) return;
            double from = previous.speed != 0 ? previous.speed : Math.abs(speed);
            double to = next.speed != 0 ? next.speed : from;
            if (from == to) { speed = dir * from; return; }
            double[] a = previous.at(), b = next.at();
            double segment = distance(a, b);
            if (segment == 0) return;
            double p = distance(a, point) / segment;
            if (velocityType == VELOCITY_EASE) p = p * p * (3 - 2 * p);
            speed = dir * (from * (1 - p) + to * p);
        }

        /**
         * {@code UpdateTrainOrientation} for a train at {@code at}, past {@code previous}: fixed,
         * facing the point its wheels' length further along the path, or turning between the two
         * nodes' orientations. {@code current} where none of them says otherwise.
         */
        private double[] orientation(PathTrack previous, PathTrack next, double[] at, double[] current) {
            if (hasSpawnFlags(FIXED_ORIENTATION) || orientationType == ORIENT_FIXED) return current;
            if (orientationType == ORIENT_AT_PATH) {
                double[] front = at.clone();
                double look = length > 0 ? length : 100;
                previous.lookAhead(front, (forward() ? look : -look) / UNITS, false, null);
                double fx = front[0] - at[0], fy = front[1] - at[1], fz = front[2] - at[2];
                if (!forward()) { fx = -fx; fy = -fy; fz = -fz; }
                if (fx == 0 && fz == 0) return current;
                double[] facing = MoverPose.vectorAngles(fx, fy, fz);
                if (hasSpawnFlags(NOPITCH)) facing[0] = current[0];
                facing[2] = current[2];
                return facing;
            }
            double[] from = previous.orientation(forward());
            double[] to = next == null ? from : next.orientation(forward());
            if (hasSpawnFlags(NOPITCH)) to[0] = from[0];
            double p = 0;
            if (next != null) {
                double segment = distance(previous.at(), next.at());
                if (segment > 0) p = distance(previous.at(), at) / segment;
            }
            if (orientationType == ORIENT_EASE) p = p * p * (3 - 2 * p);
            Quaterniond turned = MoverPose.angles(from[0], from[1], from[2]).slerp(MoverPose.angles(to[0], to[1], to[2]), p);
            double[] result = quaternionAngles(turned);
            if (hasSpawnFlags(NOPITCH)) result[0] = from[0];
            return result;
        }

        /** {@code DeadEnd}: the last node of the path in the way it went counts as passed. */
        private void deadEnd() {
            PathTrack track = path;
            if (track == null) return;
            for (int guard = 0; guard < 10_000; guard++) {
                PathTrack further = PathTrack.valid(oldSpeed < 0 ? track.previous() : track.next(), true);
                if (further == null) break;
                track = further;
            }
            track.input("inpass", null, this, this);
        }

        /** {@code TeleportToPathTrack}. */
        private void teleportTo(PathTrack track) {
            double[] at = track.at(), look = at.clone();
            track.lookAhead(look, length / UNITS, false, null);
            if (!hasSpawnFlags(FIXED_ORIENTATION) && distance(look, at) > 0) {
                double[] facing = track.orientation(forward());
                if (hasSpawnFlags(NOPITCH)) facing[0] = angles[0];
                angles = facing;
            }
            point = at;
            since = map.time();
            path = track;
            track.input("inteleport", null, this, this);
        }

        /** Brings {@link #point} up to now before the speed or direction changes. */
        private void settle() {
            if (found && path != null && point != null && speed != 0 && map.time() > since) advance();
            since = map.time();
        }

        /** {@code SetDirForward}: reversing, the node passed becomes the one ahead. */
        private void setForward(boolean forwards) {
            if (forwards && dir != 1) {
                if (path != null && path.previous() != null) path = path.previous();
                dir = 1;
            } else if (!forwards && dir != -1) {
                if (path != null && path.next() != null) path = path.next();
                dir = -1;
            }
        }

        /** {@code SetSpeed}: starting, going on or stopping as the new speed says. */
        private void setSpeed(double value, boolean accel) {
            unmodifiedSpeed = value;
            double old = speed;
            if (forwardModifier < 1 && dir > 0) value *= forwardModifier;
            if (accel) {
                desiredSpeed = Math.abs(value) * dir;
                accelerating = velocityType != VELOCITY_INSTANT;
                if (speed == 0 && Math.abs(desiredSpeed) > 0) speed = 0.1 * dir;
                start();
                return;
            }
            accelerating = false;
            speed = Math.abs(value) * dir;
            if (speed != old) {
                if (speed != 0) { if (old == 0) start(); else schedule(); }
                else stop();
            }
        }

        private void start() {
            fire("onstart", this, null);
            if (found) schedule();
        }

        private void stop() {
            oldSpeed = speed;
            speed = 0;
            accelerating = false;
        }

        @Override boolean accept(String input, String value, Actor activator, LogicEntity caller) {
            if (!input.equals("teleporttopathtrack")) settle();
            switch (input) {
                case "stop" -> stop();
                case "resume" -> { speed = oldSpeed; start(); }
                case "reverse" -> { setForward(!forward()); setSpeed(speed, false); }
                case "startforward" -> { setForward(true); setSpeed(maxSpeed, false); }
                case "startbackward" -> { setForward(false); setSpeed(maxSpeed, false); }
                case "toggle" -> setSpeed(speed == 0 ? maxSpeed : 0, false);
                case "setspeedreal" -> setSpeed(Math.max(0, Math.min(maxSpeed, Variant.number(value))), false);
                case "setspeed" -> setSpeed(maxSpeed * Math.max(0, Math.min(1, Variant.number(value))), false);
                case "setspeeddir", "setspeeddiraccel" -> {
                    double v = Variant.number(value);
                    setForward(v >= 0);
                    setSpeed(maxSpeed * Math.min(1, Math.abs(v)), input.equals("setspeeddiraccel"));
                }
                case "setspeedforwardmodifier" -> {
                    forwardModifier = Math.max(0, Math.min(1, Math.abs(Variant.number(value))));
                    setSpeed(unmodifiedSpeed, true);
                }
                case "teleporttopathtrack" -> {
                    if (map.findFirst(value, this, activator, caller) instanceof PathTrack track) teleportTo(track);
                }
                default -> { return false; }
            }
            return true;
        }

        @Override MoverPose pose(double t) {
            double[] compiled = position();
            if (compiled == null || !found || point == null) return null;
            double[] at = point.clone();
            double[] facing = angles;
            if (speed != 0 && path != null && t > since) {
                PathTrack[] nextNext = new PathTrack[1];
                PathTrack reached = path.lookAhead(at, speed / UNITS * (t - since), true, nextNext);
                if (reached != null) facing = orientation(reached, nextNext[0], at, angles);
            }
            double[] origin = origin(at);
            // Turned from the angles it was compiled at. Source turns a brush model by its absolute
            // angles, but every train with spawn angles here was built along its track already.
            return MoverPose.of(new double[]{origin[0] - compiled[0], origin[1] - compiled[1], origin[2] - compiled[2]},
                MoverPose.angles(facing[0], facing[1], facing[2]).mul(spawnInverse));
        }

        @Override boolean moving(double t) { return speed != 0; }

        /**
         * Solid even when {@code passable}: Source's passable trains are walked on through the
         * physics models of the props riding them, which a mover's props do not have here; its
         * brushes are the nearest collision there is.
         */
        @Override int moverState() { return super.moverState(); }

        @Override void save(CompoundTag tag) {
            super.save(tag);
            tag.putBoolean("found", found);
            if (path != null) tag.putInt("path", path.index);
            if (point != null) for (int i = 0; i < 3; i++) tag.putDouble("point" + i, point[i]);
            for (int i = 0; i < 3; i++) tag.putDouble("angles" + i, angles[i]);
            tag.putDouble("since", since);
            tag.putDouble("speed", speed);
            tag.putDouble("old_speed", oldSpeed);
            tag.putDouble("dir", dir);
            tag.putBoolean("accelerating", accelerating);
            tag.putDouble("desired_speed", desiredSpeed);
            tag.putDouble("forward_modifier", forwardModifier);
            tag.putDouble("unmodified_speed", unmodifiedSpeed);
        }

        @Override void load(CompoundTag tag) {
            super.load(tag);
            found = tag.getBoolean("found");
            // Saved before trains ran (DEV-0.24.0): find the path as a fresh spawn would.
            if (!found) nextThink = map.time();
            path = tag.contains("path") && map.entity(tag.getInt("path")) instanceof PathTrack track ? track : null;
            point = tag.contains("point0") ? new double[]{tag.getDouble("point0"), tag.getDouble("point1"), tag.getDouble("point2")} : null;
            if (tag.contains("angles0")) angles = new double[]{tag.getDouble("angles0"), tag.getDouble("angles1"), tag.getDouble("angles2")};
            since = tag.getDouble("since");
            speed = tag.getDouble("speed");
            oldSpeed = tag.getDouble("old_speed");
            dir = tag.contains("dir") ? tag.getDouble("dir") : 1;
            accelerating = tag.getBoolean("accelerating");
            desiredSpeed = tag.getDouble("desired_speed");
            forwardModifier = tag.contains("forward_modifier") ? tag.getDouble("forward_modifier") : 1;
            unmodifiedSpeed = tag.getDouble("unmodified_speed");
        }

        @Override String state() {
            return (speed == 0 ? "stopped" : String.format(java.util.Locale.ROOT, "speed %.1f", speed))
                + (path != null ? " past " + path.describe() : "");
        }
    }

    // ---- func_rotating.

    /** {@code CFuncRotating}. */
    static final class Rotating extends LogicEntity {
        private static final int START_ON = 1, BACKWARDS = 2, ROLL_AXIS = 4, PITCH_AXIS = 8, ACCDCC = 16, NOT_SOLID = 64;
        /** How often a rotator steps its speed while it speeds up or slows down, in seconds. */
        private static final double STEP = 0.1;

        private enum Ramp { NONE, UP, DOWN, REVERSE, START }

        private double maxSpeed, friction;
        private double[] startAngles, axis;
        /** Degrees turned about the axis at {@link #since}, and the signed speed since then. */
        private double turned, since, speed, targetSpeed;
        private boolean reversed, stopAtStart;
        private Ramp ramp = Ramp.NONE;

        Rotating(MapLogic map, int index, LogicTable.Entity entity) { super(map, index, entity); }

        @Override void spawn() {
            friction = number("fanfriction", 0) / 100;
            if (friction == 0) friction = 1;
            axis = hasSpawnFlags(ROLL_AXIS) ? new double[]{0, 0, 1} : hasSpawnFlags(PITCH_AXIS) ? new double[]{1, 0, 0} : new double[]{0, 1, 0};
            if (hasSpawnFlags(BACKWARDS)) for (int i = 0; i < 3; i++) axis[i] = -axis[i];
            maxSpeed = Math.abs(number("maxspeed", 0));
            if (maxSpeed == 0) maxSpeed = 100;
            startAngles = MoverPose.parseAngles(key("angles"));
            if (hasSpawnFlags(START_ON)) {
                ramp = Ramp.START;
                nextThink = map.time() + 0.2;
            }
        }

        /** Brings {@link #turned} up to now. */
        private void settle() {
            double now = map.time();
            turned = (turned + speed * (now - since)) % 360.0;
            since = now;
        }

        /** {@code UpdateSpeed}, with its slowing toward the start position. */
        private void updateSpeed(double value) {
            settle();
            double old = speed;
            speed = Math.max(-maxSpeed, Math.min(maxSpeed, value));
            if (stopAtStart && value < 100) {
                double delta = startDelta();
                if (value <= 25 && Math.abs(delta) < 1) {
                    targetSpeed = 0;
                    stopAtStart = false;
                    speed = 0;
                    turned = 0;
                } else if (Math.abs(delta) > 90) {
                    speed = old;
                } else {
                    double min = Math.max(20, Math.abs(delta));
                    speed = old > 0 ? min : -min;
                }
            }
        }

        /** How far the rotator is from its start position about its axis, in -180 to 180 degrees. */
        private double startDelta() {
            double delta = ((turned % 360) + 360) % 360;
            return delta > 180 ? delta - 360 : delta;
        }

        /** {@code SetTargetSpeed}. */
        private void setTarget(double value) {
            value = Math.abs(value);
            if (reversed) value = -value;
            targetSpeed = value;
            if (!hasSpawnFlags(ACCDCC)) {
                updateSpeed(targetSpeed);
                ramp = Ramp.NONE;
            } else if ((speed > 0 && targetSpeed < 0) || (speed < 0 && targetSpeed > 0)) {
                ramp = Ramp.REVERSE;
            } else if (Math.abs(speed) < Math.abs(targetSpeed)) {
                ramp = Ramp.UP;
            } else if (Math.abs(speed) > Math.abs(targetSpeed)) {
                ramp = Ramp.DOWN;
            } else {
                ramp = Ramp.NONE;
            }
            nextThink = map.time() + interval();
        }

        private double interval() { return stopAtStart ? MapLogic.TICK_SECONDS : STEP; }

        /** {@code SpinDown}: true once at the target speed. */
        private boolean spinDown(double target) {
            double value = Math.max(0, Math.abs(speed) - 0.1 * maxSpeed * friction);
            boolean done = false;
            if (value <= Math.abs(target)) { value = target; done = !stopAtStart; }
            else if (speed < 0) value = -value;
            updateSpeed(value);
            return done;
        }

        @Override void think() {
            switch (ramp) {
                case START -> { ramp = Ramp.NONE; use(); return; }
                case UP -> {
                    double value = Math.abs(speed) + 0.2 * maxSpeed * friction;
                    boolean done = false;
                    if (value >= Math.abs(targetSpeed)) { value = targetSpeed; done = !stopAtStart; }
                    else if (targetSpeed < 0) value = -value;
                    updateSpeed(value);
                    if (done) ramp = Ramp.NONE;
                }
                case DOWN -> { if (spinDown(targetSpeed)) ramp = Ramp.NONE; }
                case REVERSE -> { if (spinDown(0)) { setTarget(targetSpeed); return; } }
                case NONE -> { }
            }
            if (stopAtStart) {
                settle();
                if (Math.abs(startDelta()) < Math.abs(speed) * MapLogic.TICK_SECONDS) {
                    setTarget(0);
                    speed = 0;
                    turned = 0;
                    stopAtStart = false;
                    ramp = Ramp.NONE;
                    return;
                }
            }
            if (ramp != Ramp.NONE || stopAtStart) nextThink = map.time() + interval();
        }

        /** {@code RotatingUse}: stops a turning rotator, starts a still one. */
        private void use() { setTarget(speed != 0 ? 0 : maxSpeed); }

        @Override boolean accept(String input, String value, Actor activator, LogicEntity caller) {
            switch (input) {
                case "reverse" -> { stopAtStart = false; reversed = !reversed; setTarget(speed); }
                case "setspeed" -> {
                    stopAtStart = false;
                    double v = Variant.number(value);
                    reversed = v < 0;
                    setTarget(Math.min(1, Math.abs(v)) * maxSpeed);
                }
                case "start" -> { stopAtStart = false; setTarget(maxSpeed); }
                case "startforward" -> { reversed = false; setTarget(maxSpeed); }
                case "startbackward" -> { stopAtStart = false; reversed = true; setTarget(maxSpeed); }
                case "stop" -> { stopAtStart = false; setTarget(0); }
                case "stopatstartpos" -> { stopAtStart = true; setTarget(0); }
                case "toggle" -> setTarget(speed > 0 ? 0 : maxSpeed);
                case "getspeed" -> fire("ongetspeed", activator, caller, Variant.of(speed), 0);
                default -> { return false; }
            }
            return true;
        }

        @Override MoverPose pose(double t) {
            double now = turned + speed * Math.max(0, t - since);
            return MoverPose.of(new double[3], MoverPose.angles(startAngles[0] + axis[0] * now, startAngles[1] + axis[1] * now,
                startAngles[2] + axis[2] * now));
        }

        @Override boolean moving(double t) { return speed != 0; }

        @Override int moverState() {
            return removed || !hasSpawnFlags(NOT_SOLID) ? super.moverState() : MoverRegistry.NOT_SOLID;
        }

        @Override void save(CompoundTag tag) {
            super.save(tag);
            tag.putDouble("turned", turned);
            tag.putDouble("since", since);
            tag.putDouble("speed", speed);
            tag.putDouble("target_speed", targetSpeed);
            tag.putBoolean("reversed", reversed);
            tag.putBoolean("stop_at_start", stopAtStart);
            tag.putString("ramp", ramp.name());
        }

        @Override void load(CompoundTag tag) {
            super.load(tag);
            turned = tag.getDouble("turned");
            since = tag.getDouble("since");
            speed = tag.getDouble("speed");
            targetSpeed = tag.getDouble("target_speed");
            reversed = tag.getBoolean("reversed");
            stopAtStart = tag.getBoolean("stop_at_start");
            if (!tag.getString("ramp").isEmpty()) ramp = Ramp.valueOf(tag.getString("ramp"));
            else if (hasSpawnFlags(START_ON)) { ramp = Ramp.START; nextThink = map.time() + 0.2; }
        }

        @Override String state() {
            return speed == 0 ? "stopped" : String.format(java.util.Locale.ROOT, "%.0f deg/s", speed);
        }
    }

    private static double distance(double[] a, double[] b) {
        double dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** {@code QuaternionAngles}: Source pitch, yaw and roll of a rotation built as {@link MoverPose#angles}. */
    static double[] quaternionAngles(Quaterniond q) {
        // Forward (Source x) and left (Source y) after the turn, in map-local axes.
        org.joml.Vector3d forward = q.transform(new org.joml.Vector3d(1, 0, 0));
        org.joml.Vector3d left = q.transform(new org.joml.Vector3d(0, 0, -1));
        org.joml.Vector3d up = q.transform(new org.joml.Vector3d(0, 1, 0));
        double fx = forward.x, fy = -forward.z, fz = forward.y;
        double xy = Math.sqrt(fx * fx + fy * fy);
        double pitch = Math.toDegrees(Math.atan2(-fz, xy)), yaw, roll;
        if (xy > 0.001) {
            yaw = Math.toDegrees(Math.atan2(fy, fx));
            roll = Math.toDegrees(Math.atan2(left.y, up.y));
        } else {
            yaw = Math.toDegrees(Math.atan2(-left.x, -left.z));
            roll = 0;
        }
        return new double[]{pitch, yaw, roll};
    }
}
