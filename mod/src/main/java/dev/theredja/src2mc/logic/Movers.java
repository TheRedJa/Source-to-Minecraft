package dev.theredja.src2mc.logic;

import dev.theredja.src2mc.bundle.LogicTable;
import net.minecraft.nbt.CompoundTag;

/**
 * Buttons and doors as the map's logic sees them, after the SDK's {@code buttons.cpp},
 * {@code doors.cpp} and {@code props.cpp}: their states, the outputs each move fires and the time
 * a move takes. Each also has a {@link MoverPose} at any moment, which is where Sable carries its
 * sub-level (D21): a move goes from where the entity is to its destination at the entity's speed,
 * as Source's {@code LinearMove} and {@code AngularMove} do. INFRA's {@code infra_button} is, by
 * its FGD, a {@code func_door} that also fires {@code OnPressed} when used.
 */
final class Movers {
    private Movers() {}

    /** An entity the player's use key works on. */
    interface Usable {
        boolean usable();

        /**
         * Used by a player; {@code pressed} is true on the press itself and false while the key is
         * held on, which only entities used continuously care about.
         */
        void use(PlayerActor player, boolean pressed);
    }

    private enum Toggle { AT_BOTTOM, GOING_UP, AT_TOP, GOING_DOWN }

    /** Size of a brush entity along a direction, in Source units: Source's {@code DotProductAbs(movedir, OBBSize - 2)}. */
    private static double extent(MapLogic map, LogicEntity entity, double[] direction) {
        LogicTable.Volume volume = map.volume(entity);
        if (volume == null) return 0;
        double[] b = volume.bounds();
        // Map-local blocks to Source axes: Source x = block x, Source y = -block z, Source z = block y.
        double sx = (b[3] - b[0]) * 32 - 2, sy = (b[5] - b[2]) * 32 - 2, sz = (b[4] - b[1]) * 32 - 2;
        return Math.abs(direction[0] * sx) + Math.abs(direction[1] * sy) + Math.abs(direction[2] * sz);
    }

    /** {@code AngleVectors} forward of a {@code movedir} keyvalue: pitch, yaw, roll in degrees. */
    private static double[] moveDirection(LogicEntity entity) {
        String[] parts = entity.key("movedir", "0 0 0").trim().split("\\s+");
        double pitch = parts.length > 0 ? Math.toRadians(Variant.number(parts[0])) : 0;
        double yaw = parts.length > 1 ? Math.toRadians(Variant.number(parts[1])) : 0;
        return new double[]{Math.cos(pitch) * Math.cos(yaw), Math.cos(pitch) * Math.sin(yaw), -Math.sin(pitch)};
    }

    private static final int ROTATE_BACKWARDS = 2, ROTATE_ROLL = 64, ROTATE_PITCH = 128;

    /** {@code CBaseToggle::AxisDir} with {@code SF_DOOR_ROTATE_BACKWARDS}: the angle a rotating entity turns about, per degree. */
    private static double[] moveAngles(LogicEntity entity) {
        double[] axis = entity.hasSpawnFlags(ROTATE_ROLL) ? new double[]{0, 0, 1}
            : entity.hasSpawnFlags(ROTATE_PITCH) ? new double[]{1, 0, 0} : new double[]{0, 1, 0};
        if (entity.hasSpawnFlags(ROTATE_BACKWARDS)) for (int i = 0; i < 3; i++) axis[i] = -axis[i];
        return axis;
    }

    /** The pose {@code fraction} of the way along a straight move of {@code travel} blocks. */
    private static MoverPose linear(double[] travel, double fraction) {
        return MoverPose.of(new double[]{travel[0] * fraction, travel[1] * fraction, travel[2] * fraction}, new org.joml.Quaterniond());
    }

    /** The pose {@code fraction} of the way through a turn of {@code degrees} about {@code axis} from {@code start}. */
    private static MoverPose angular(double[] start, double[] axis, double degrees, double fraction) {
        double turn = degrees * fraction;
        return MoverPose.of(new double[3], MoverPose.angles(start[0] + axis[0] * turn, start[1] + axis[1] * turn, start[2] + axis[2] * turn));
    }

    /** The compiled-to-open offset of a sliding entity, Source's {@code m_vecPosition2 - m_vecPosition1}, in blocks. */
    private static double[] slide(MapLogic map, LogicEntity entity, double lip) {
        double[] direction = moveDirection(entity);
        double length = Math.max(0, extent(map, entity, direction) - lip);
        return MoverPose.sourceToBlocks(new double[]{direction[0] * length, direction[1] * length, direction[2] * length});
    }

    /** {@code PlayLockSounds}: the locked or unlocked sound, at the entity. */
    private static void lockSound(LogicEntity entity, boolean locked) {
        entity.map.emitSound(entity.key(locked ? "locked_sound" : "unlocked_sound"), entity.position(), null, null, 0, -1);
    }

    /**
     * {@code func_button}, {@code func_rot_button} and {@code momentary_rot_button}. A momentary
     * button turns while the use key is held on it, as Source's continuous use does.
     */
    static final class Button extends LogicEntity implements Usable {
        private static final int DONT_MOVE = 1, TOGGLE = 32, USE_ACTIVATES = 1024, LOCKED = 2048;
        private Toggle state = Toggle.AT_BOTTOM;
        private boolean locked, stayPushed, momentary;
        private double wait, travel, speed, distance, lastLocked = -1;
        private Actor activator;
        // Momentary: position 0..1, direction, and when the use key was last held on it.
        private double position;
        private int direction = 1;
        private double lastUsed = -1;
        private boolean beingUsed;
        // The move in progress: it began at moveStart and ends at nextThink.
        private double moveStart;
        private double[] slide = new double[3], startAngles = new double[3], axis = new double[3];
        private boolean rotating;

        Button(MapLogic map, int index, LogicTable.Entity entity) { super(map, index, entity); }

        @Override void spawn() {
            momentary = classname.equals("momentary_rot_button");
            rotating = !classname.equals("func_button");
            startAngles = MoverPose.parseAngles(key("angles"));
            axis = moveAngles(this);
            speed = number("speed", 0);
            if (speed == 0) speed = momentary ? 100 : 40;
            wait = number("wait", 0);
            if (wait == 0) wait = 1;
            stayPushed = wait == -1;
            locked = hasSpawnFlags(LOCKED);
            if (rotating) {
                distance = Math.abs(number("distance", 0));
                travel = distance / speed;
            } else {
                double lip = number("lip", 0);
                if (lip == 0) lip = 4;
                double length = extent(map, this, moveDirection(this)) - lip;
                travel = length < 1 || hasSpawnFlags(DONT_MOVE) ? 0 : length / speed;
                if (travel > 0) slide = slide(map, this, lip);
            }
            if (momentary) {
                position = Math.max(0, Math.min(1, number("startposition", 0)));
                direction = Variant.integer(key("startdirection", "1")) == -1 ? -1 : 1;
                if (position == 0) direction = -1;
                else if (position == 1) direction = 1;
            }
        }

        private String noise() {
            int sounds = Variant.integer(key("sounds"));
            return sounds == 0 ? null : "buttons.snd" + sounds;
        }

        @Override public boolean usable() { return !removed && hasSpawnFlags(USE_ACTIVATES); }

        @Override public void use(PlayerActor player, boolean pressed) {
            if (momentary) { useMomentary(player, pressed); return; }
            if (!pressed) return;
            // CBaseButton::ButtonUse.
            if (state == Toggle.GOING_UP || state == Toggle.GOING_DOWN) return;
            if (locked) { useLocked(player); return; }
            activator = player;
            if (state == Toggle.AT_TOP) {
                if (hasSpawnFlags(TOGGLE)) {
                    map.emitSound(noise(), position(), null, null, 0, -1);
                    fire("onpressed", activator, null);
                    goReturn();
                }
            } else {
                fire("onpressed", activator, null);
                activateButton();
            }
        }

        /** {@code OnUseLocked}: at most every half second. */
        private void useLocked(Actor player) {
            lockSound(this, true);
            if (map.time() > lastLocked) {
                fire("onuselocked", player, null);
                lastLocked = map.time() + 0.5;
            }
        }

        private void activateButton() {
            map.emitSound(noise(), position(), null, null, 0, -1);
            if (locked) { lockSound(this, true); return; }
            lockSound(this, false);
            state = Toggle.GOING_UP;
            moveStart = map.time();
            nextThink = map.time() + travel;
        }

        private void goReturn() {
            state = Toggle.GOING_DOWN;
            moveStart = map.time();
            nextThink = map.time() + travel;
        }

        /** How far pressed in the button is at time {@code t}: 0 out, 1 in. */
        private double fraction(double t) {
            if (momentary) return position;
            double done = travel <= 0 ? 1 : Math.max(0, Math.min(1, (t - moveStart) / travel));
            return switch (state) {
                case AT_BOTTOM -> 0;
                case AT_TOP -> 1;
                case GOING_UP -> done;
                case GOING_DOWN -> 1 - done;
            };
        }

        @Override MoverPose pose(double t) {
            double fraction = fraction(t);
            return rotating ? angular(startAngles, axis, distance, fraction) : linear(slide, fraction);
        }

        @Override boolean moving(double t) {
            return momentary ? beingUsed : state == Toggle.GOING_UP || state == Toggle.GOING_DOWN;
        }

        @Override void think() {
            if (momentary) { thinkMomentary(); return; }
            switch (state) {
                case GOING_UP -> {
                    // TriggerAndWait.
                    if (locked) return;
                    state = Toggle.AT_TOP;
                    if (!stayPushed && !hasSpawnFlags(TOGGLE)) nextThink = map.time() + wait;
                    fire("onin", activator, null);
                }
                case AT_TOP -> goReturn();
                case GOING_DOWN -> {
                    // ButtonBackHome.
                    state = Toggle.AT_BOTTOM;
                    fire("onout", activator, null);
                }
                default -> {}
            }
        }

        @Override boolean accept(String input, String value, Actor activator, LogicEntity caller) {
            switch (input) {
                case "lock" -> locked = true;
                case "unlock" -> locked = false;
                case "press" -> press(activator, Toggle.GOING_UP, true);
                case "pressin" -> press(activator, Toggle.AT_TOP, false);
                case "pressout" -> press(activator, Toggle.AT_BOTTOM, false);
                case "setposition", "setpositionimmediately" -> {
                    if (!momentary) return false;
                    position = Math.max(0, Math.min(1, Variant.number(value)));
                    if (input.equals("setposition")) {
                        if (position == 1) fire("onfullyclosed", this, null);
                        else if (position == 0) fire("onfullyopen", this, null);
                        fire("onreachedposition", this, null);
                    }
                }
                case "enable", "disable", "_disableupdatetarget", "_enableupdatetarget" -> { if (!momentary) return false; }
                default -> { return false; }
            }
            return true;
        }

        /** {@code CBaseButton::Press} for the Press, PressIn and PressOut inputs. */
        private void press(Actor by, Toggle code, boolean anyPress) {
            if (anyPress && (state == Toggle.GOING_UP || state == Toggle.GOING_DOWN)) return;
            if (!anyPress && code == Toggle.AT_TOP && (state == Toggle.GOING_UP || state == Toggle.AT_TOP)) return;
            if (!anyPress && code == Toggle.AT_BOTTOM && (state == Toggle.GOING_DOWN || state == Toggle.AT_BOTTOM)) return;
            if (locked) { lockSound(this, true); return; }
            activator = by;
            boolean returning = (anyPress && state == Toggle.AT_TOP) || (code == Toggle.AT_BOTTOM && (state == Toggle.AT_TOP || state == Toggle.GOING_UP));
            if (returning) {
                map.emitSound(noise(), position(), null, null, 0, -1);
                fire("onpressed", by, null);
                goReturn();
            } else if (anyPress || state == Toggle.AT_BOTTOM || state == Toggle.GOING_DOWN) {
                fire("onpressed", by, null);
                activateButton();
            }
        }

        // ---- momentary_rot_button: held use turns it, CMomentaryRotButton::Use and UpdateSelf.

        private void useMomentary(PlayerActor player, boolean pressed) {
            if (locked) { if (pressed) useLocked(player); return; }
            activator = player;
            if (!beingUsed) {
                beingUsed = true;
                direction = -direction;
                fire("onpressed", player, null);
                map.emitSound(noise(), position(), null, null, 0, -1);
            }
            lastUsed = map.time();
            nextThink = map.time();
        }

        private void thinkMomentary() {
            if (!beingUsed) return;
            if (map.time() - lastUsed > 0.1) {
                // UseMoveDone: the key was let go.
                beingUsed = false;
                fire("onunpressed", activator, null);
                return;
            }
            double step = distance <= 0 ? 1 : speed * MapLogic.TICK_SECONDS / distance;
            position = Math.max(0, Math.min(1, position + direction * step));
            if (direction > 0 && position >= 1) { fire("onfullyclosed", this, null); beingUsed = false; return; }
            if (direction < 0 && position <= 0) { fire("onfullyopen", this, null); beingUsed = false; return; }
            nextThink = map.time() + MapLogic.TICK_SECONDS;
        }

        @Override void save(CompoundTag tag) {
            super.save(tag);
            tag.putString("state", state.name());
            tag.putBoolean("locked", locked);
            tag.putDouble("position", position);
            tag.putInt("direction", direction);
            tag.putDouble("move_start", moveStart);
        }

        @Override void load(CompoundTag tag) {
            super.load(tag);
            if (!tag.getString("state").isEmpty()) state = Toggle.valueOf(tag.getString("state"));
            locked = tag.getBoolean("locked");
            position = tag.getDouble("position");
            direction = tag.getInt("direction") == -1 ? -1 : 1;
            moveStart = tag.getDouble("move_start");
        }

        @Override String state() {
            return (momentary ? String.format(java.util.Locale.ROOT, "position %.2f", position) : state.name().toLowerCase(java.util.Locale.ROOT))
                + (locked ? ", locked" : "");
        }
    }

    /**
     * {@code func_door}, {@code func_door_rotating}, {@code func_movelinear}, {@code infra_button}
     * and {@code prop_door_rotating}: closed, opening, open, closing, with the time each move
     * takes from the entity's speed and distance.
     */
    static final class Door extends LogicEntity implements Usable {
        private static final int START_OPEN = 1, NO_AUTO_RETURN = 32, USE_OPENS = 256, LOCKED = 2048, SILENT = 4096,
            USE_CLOSES = 8192, CAN_BE_HELD = 16384, NEW_USE_RULES = 65536;
        private enum Kind { BRUSH, PROP, MOVELINEAR }
        private Kind kind;
        private Toggle state = Toggle.AT_BOTTOM;
        private boolean locked, held;
        private double travel, wait, lastHeld = -1, moveDone = NEVER, returnAt = NEVER;
        private Actor activator;
        private long movingSound = -1;
        // The move in progress: from this fraction of the way open, starting at moveStart.
        private double moveFrom, moveStart;
        private double distance, speed;
        // prop_door_rotating moves by angles, as CPropDoorRotating does: from angFrom to angTo.
        private double[] closedAngles = new double[3], forwardAngles, backAngles, angFrom = new double[3], angTo = new double[3];
        private PlayerActor openAwayFrom;
        private double[] slide = new double[3], startAngles = new double[3], axis = new double[3];

        Door(MapLogic map, int index, LogicTable.Entity entity) { super(map, index, entity); }

        private boolean infra() { return classname.equals("infra_button"); }

        @Override void spawn() {
            kind = switch (classname) {
                case "prop_door_rotating" -> Kind.PROP;
                case "func_movelinear" -> Kind.MOVELINEAR;
                default -> Kind.BRUSH;
            };
            speed = number("speed", 0);
            if (speed == 0) speed = 100;
            distance = switch (classname) {
                case "func_door_rotating", "prop_door_rotating" -> Math.abs(number("distance", 90));
                case "func_movelinear" -> Math.abs(number("movedistance", 100));
                default -> extent(map, this, moveDirection(this)) - number("lip", 0);
            };
            travel = Math.max(0, distance) / speed;
            startAngles = MoverPose.parseAngles(key("angles"));
            axis = classname.equals("prop_door_rotating")
                // CPropDoorRotating turns about its up axis; opendir 2 opens backwards.
                ? new double[]{0, Variant.integer(key("opendir")) == 2 ? -1 : 1, 0} : moveAngles(this);
            if (kind == Kind.PROP) spawnPropDoor();
            if (kind == Kind.MOVELINEAR) {
                double[] direction = moveDirection(this);
                slide = MoverPose.sourceToBlocks(new double[]{direction[0] * distance, direction[1] * distance, direction[2] * distance});
            } else if (!rotating()) {
                slide = slide(map, this, number("lip", 0));
            }
            wait = kind == Kind.PROP ? number("returndelay", -1) : number("wait", 4);
            locked = hasSpawnFlags(LOCKED);
            boolean open = kind == Kind.PROP ? Variant.integer(key("spawnpos")) != 0 || hasSpawnFlags(START_OPEN)
                : kind == Kind.MOVELINEAR ? number("startposition", 0) >= 1
                : Variant.integer(key("spawnpos")) == 1 || hasSpawnFlags(START_OPEN);
            if (open) state = Toggle.AT_TOP;
        }

        @Override public boolean usable() {
            if (removed) return false;
            return switch (kind) {
                case PROP -> true;
                case MOVELINEAR -> false;
                case BRUSH -> hasSpawnFlags(USE_OPENS);
            };
        }

        @Override public void use(PlayerActor player, boolean pressed) {
            if (infra() && hasSpawnFlags(CAN_BE_HELD)) {
                if (!held) { held = true; fire("onstartpressing", player, null); }
                lastHeld = map.time();
                schedule();
            }
            if (!pressed) return;
            activator = player;
            if (kind == Kind.PROP) { usePropDoor(player); return; }
            // CBaseDoor::Use.
            boolean allow = hasSpawnFlags(NEW_USE_RULES)
                ? state == Toggle.AT_BOTTOM || state == Toggle.GOING_DOWN || (hasSpawnFlags(NO_AUTO_RETURN) && (state == Toggle.AT_TOP || state == Toggle.GOING_UP))
                : state == Toggle.AT_BOTTOM || (hasSpawnFlags(NO_AUTO_RETURN) && state == Toggle.AT_TOP);
            if (!allow) return;
            if (locked) {
                fire("onlockeduse", player, null);
                lockSound(this, true);
                return;
            }
            if (infra()) fire("onpressed", player, null);
            activateDoor();
        }

        /** {@code CBasePropDoor::OnUse}. */
        private void usePropDoor(PlayerActor player) {
            openAwayFrom = player;
            if (state == Toggle.AT_BOTTOM || (state == Toggle.AT_TOP && hasSpawnFlags(USE_CLOSES))) {
                if (locked) {
                    lockSound(this, true);
                    fire("onlockeduse", player, null);
                } else {
                    lockSound(this, false);
                    if (state == Toggle.AT_TOP) goDown(); else goUp();
                }
            } else if (state == Toggle.GOING_UP && hasSpawnFlags(USE_CLOSES)) {
                goDown();
            } else if (state == Toggle.GOING_DOWN) {
                goUp();
            }
        }

        /** {@code DoorActivate}. */
        private void activateDoor() {
            if (hasSpawnFlags(NO_AUTO_RETURN) && state == Toggle.AT_TOP) {
                goDown();
            } else {
                lockSound(this, false);
                if (state != Toggle.AT_TOP && state != Toggle.GOING_UP) goUp();
            }
        }

        private boolean silent() { return hasSpawnFlags(SILENT); }

        private boolean rotating() { return classname.equals("func_door_rotating") || kind == Kind.PROP; }

        /**
         * {@code CPropDoorRotating::Spawn} and {@code CalcOpenAngles}: the closed angles are the
         * entity's, forward opens to the yaw minus the distance and back to the yaw plus it, the
         * two swapped for a door hinged on its left; the spawn position picks where it starts.
         */
        private void spawnPropDoor() {
            closedAngles = MoverPose.parseAngles(key("angles"));
            forwardAngles = new double[]{closedAngles[0], closedAngles[1] - distance, closedAngles[2]};
            backAngles = new double[]{closedAngles[0], closedAngles[1] + distance, closedAngles[2]};
            if (hingeOnLeft()) { double[] swap = forwardAngles; forwardAngles = backAngles; backAngles = swap; }
            angFrom = closedAngles.clone();
            int spawn = Variant.integer(key("spawnpos"));
            angTo = hasSpawnFlags(START_OPEN) || spawn == 1 ? forwardAngles
                : spawn == 2 ? backAngles
                : spawn == 3 ? MoverPose.parseAngles(key("ajarangles")) : closedAngles.clone();
        }

        /**
         * {@code IsHingeOnLeft}: the corner of the model's box farthest from its origin, across the
         * floor, lies to the door's right. Asked in the model's own frame, where Source's right
         * is Minecraft's +z; the same as Source's world-box test for a door square to the axes.
         */
        private boolean hingeOnLeft() {
            if (map.map == null || map.map.movers() == null) return false;
            int mover = map.map.movers().indexOfEntity(index);
            if (mover < 0) return false;
            for (var prop : map.map.movers().movers().get(mover).props()) {
                if (prop.entity() != index) continue;
                float[] b = map.map.models().get(prop.model()).bounds();
                if (b == null) return false;
                double minLength = b[0] * b[0] + b[2] * b[2], maxLength = b[3] * b[3] + b[5] * b[5];
                return (minLength > maxLength ? b[2] : b[5]) > 0;
            }
            return false;
        }

        /** Where a prop door turns to when it opens: its one direction, else away from whoever opens it. */
        private double[] openAngles() {
            int direction = Variant.integer(key("opendir"));
            if (direction == 1) return forwardAngles;
            if (direction == 2) return backAngles;
            double[] player = openAwayFrom == null ? null : map.playerPosition(openAwayFrom);
            double[] origin = position();
            if (player == null || origin == null) return forwardAngles;
            // CPropDoorRotating::BeginOpening: open back when the player stands in front.
            org.joml.Vector3d forward = MoverPose.angles(closedAngles[0], closedAngles[1], closedAngles[2]).transform(new org.joml.Vector3d(1, 0, 0));
            double ahead = forward.x * (player[0] - origin[0]) + forward.y * (player[1] - origin[1]) + forward.z * (player[2] - origin[2]);
            return ahead > 0 ? backAngles : forwardAngles;
        }

        /** The prop door's angles at time {@code t}: {@code AngularMove} turns each angle at a constant rate. */
        private double[] propAngles(double t) {
            return switch (state) {
                case AT_BOTTOM -> closedAngles;
                case AT_TOP -> angTo;
                case GOING_UP, GOING_DOWN -> {
                    double length = moveDone - moveStart;
                    double done = !(length > 0) || moveDone == NEVER ? 1 : Math.max(0, Math.min(1, (t - moveStart) / length));
                    yield new double[]{angFrom[0] + (angTo[0] - angFrom[0]) * done, angFrom[1] + (angTo[1] - angFrom[1]) * done,
                        angFrom[2] + (angTo[2] - angFrom[2]) * done};
                }
            };
        }

        /** How far open the door is at time {@code t}: 0 at its first position, 1 at its second. */
        private double fraction(double t) {
            double target = state == Toggle.GOING_UP ? 1 : 0;
            return switch (state) {
                case AT_BOTTOM -> 0;
                case AT_TOP -> 1;
                case GOING_UP, GOING_DOWN -> {
                    double length = moveDone - moveStart;
                    double done = !(length > 0) || moveDone == NEVER ? 1 : Math.max(0, Math.min(1, (t - moveStart) / length));
                    yield moveFrom + (target - moveFrom) * done;
                }
            };
        }

        /** Starts a move toward fully open (1) or closed (0) from wherever the door is, at its speed. */
        private void moveTo(double target) {
            double now = map.time();
            if (kind == Kind.PROP) {
                double[] current = propAngles(now);
                double[] destination = target >= 1 ? (state == Toggle.GOING_UP || state == Toggle.AT_TOP ? angTo : openAngles()) : closedAngles;
                double turn = Math.max(Math.abs(destination[0] - current[0]), Math.max(Math.abs(destination[1] - current[1]), Math.abs(destination[2] - current[2])));
                angFrom = current.clone();
                angTo = destination.clone();
                moveStart = now;
                moveDone = now + turn / speed;
                return;
            }
            moveFrom = fraction(now);
            moveStart = now;
            moveDone = now + Math.abs(target - moveFrom) * travel;
        }

        @Override MoverPose pose(double t) {
            double fraction = fraction(t);
            if (kind == Kind.MOVELINEAR) {
                // CFuncMoveLinear spawns startposition of the way along its path.
                double start = Math.max(0, Math.min(1, number("startposition", 0)));
                return linear(slide, fraction - (start >= 1 ? 1 : 0));
            }
            if (kind == Kind.PROP) {
                // The prop is placed at its closed angles already: the pose is the turn from them.
                double[] now = propAngles(t);
                return MoverPose.of(new double[3], MoverPose.angles(now[0], now[1], now[2])
                    .mul(MoverPose.angles(closedAngles[0], closedAngles[1], closedAngles[2]).conjugate()));
            }
            return rotating() ? angular(startAngles, axis, distance, fraction) : linear(slide, fraction);
        }

        @Override boolean moving(double t) { return state == Toggle.GOING_UP || state == Toggle.GOING_DOWN; }

        private void goUp() {
            if (!silent() && state != Toggle.GOING_UP && state != Toggle.GOING_DOWN) startMoving(true);
            moveTo(1);
            state = Toggle.GOING_UP;
            returnAt = NEVER;
            schedule();
            fire("onopen", this, null);
        }

        private void goDown() {
            if (!silent() && state != Toggle.GOING_UP && state != Toggle.GOING_DOWN) startMoving(false);
            moveTo(0);
            state = Toggle.GOING_DOWN;
            returnAt = NEVER;
            schedule();
            fire("onclose", this, null);
        }

        private void startMoving(boolean opening) {
            String sound = kind == Kind.PROP ? key("soundmoveoverride") : !opening && key("startclosesound") != null && !key("startclosesound").isEmpty() ? key("startclosesound") : key("noise1");
            map.stopSound(movingSound);
            movingSound = map.emitSound(sound, position(), null, null, 0, -1);
        }

        private void arrive(boolean open) {
            map.stopSound(movingSound);
            movingSound = -1;
            if (silent()) return;
            String sound = kind == Kind.PROP ? key(open ? "soundopenoverride" : "soundcloseoverride")
                : !open && key("closesound") != null && !key("closesound").isEmpty() ? key("closesound") : key("noise2");
            map.emitSound(sound, position(), null, null, 0, -1);
        }

        /** One think slot serves the move's end, the automatic return and the held check: it is set to the earliest. */
        private void schedule() {
            nextThink = Math.min(Math.min(moveDone, returnAt), held ? map.time() + MapLogic.TICK_SECONDS : NEVER);
        }

        @Override void think() {
            double now = map.time();
            if (held && now - lastHeld >= 0.1) {
                held = false;
                fire("onstoppressing", activator, null);
            }
            if (moveDone <= now) {
                moveDone = NEVER;
                if (state == Toggle.GOING_UP) {
                    // DoorHitTop / DoorOpenMoveDone.
                    arrive(true);
                    state = Toggle.AT_TOP;
                    boolean autoReturn = kind == Kind.PROP ? wait != -1
                        : kind != Kind.MOVELINEAR && !hasSpawnFlags(NO_AUTO_RETURN) && wait != -1;
                    if (autoReturn) returnAt = now + (kind == Kind.PROP ? wait + 0.1 : wait);
                    fire(kind != Kind.PROP && hasSpawnFlags(START_OPEN) ? "onfullyclosed" : "onfullyopen", this, null);
                } else if (state == Toggle.GOING_DOWN) {
                    // DoorHitBottom / DoorCloseMoveDone.
                    arrive(false);
                    state = Toggle.AT_BOTTOM;
                    fire(kind != Kind.PROP && hasSpawnFlags(START_OPEN) ? "onfullyopen" : "onfullyclosed", activator, null);
                }
            }
            if (returnAt <= now) {
                returnAt = NEVER;
                if (state == Toggle.AT_TOP) goDown();
            }
            schedule();
        }

        @Override boolean accept(String input, String value, Actor by, LogicEntity caller) {
            switch (input) {
                case "open" -> {
                    if (kind == Kind.PROP) {
                        if (locked) return true;
                        if (state != Toggle.AT_TOP && state != Toggle.GOING_UP) {
                            lockSound(this, false);
                            fire("onopen", by, null);
                            activator = by;
                            openAwayFrom = null;
                            goUp();
                        }
                    } else if (state != Toggle.AT_TOP && state != Toggle.GOING_UP) {
                        if (locked && kind != Kind.MOVELINEAR) return true;
                        if (kind != Kind.MOVELINEAR) lockSound(this, false);
                        goUp();
                    }
                }
                case "close" -> {
                    if (kind == Kind.PROP) {
                        if (state != Toggle.AT_BOTTOM) { fire("onclose", by, null); goDown(); }
                    } else if (state != Toggle.AT_BOTTOM) {
                        goDown();
                    }
                }
                case "toggle" -> {
                    if (locked && kind != Kind.MOVELINEAR) return true;
                    if (state == Toggle.AT_BOTTOM) goUp();
                    else if (state == Toggle.AT_TOP) goDown();
                }
                case "lock" -> locked = true;
                case "unlock" -> locked = false;
                case "press" -> { if (infra()) { fire("onpressed", by, null); activator = by; activateDoor(); } else return false; }
                case "setspeed" -> {
                    double speed = Variant.number(value);
                    if (speed > 0 && travel > 0) travel = travel * number("speed", 100) / speed;
                }
                case "setposition" -> {
                    if (kind != Kind.MOVELINEAR) return false;
                    double target = Variant.number(value);
                    if (target >= 1 && state != Toggle.AT_TOP) goUp();
                    else if (target <= 0 && state != Toggle.AT_BOTTOM) goDown();
                }
                default -> { return false; }
            }
            return true;
        }

        @Override void save(CompoundTag tag) {
            super.save(tag);
            tag.putString("state", state.name());
            tag.putBoolean("locked", locked);
            tag.putDouble("travel", travel);
            if (moveDone != NEVER) tag.putDouble("move_done", moveDone);
            if (returnAt != NEVER) tag.putDouble("return_at", returnAt);
            tag.putDouble("move_from", moveFrom);
            tag.putDouble("move_start", moveStart);
            if (kind == Kind.PROP) {
                for (int i = 0; i < 3; i++) { tag.putDouble("ang_from" + i, angFrom[i]); tag.putDouble("ang_to" + i, angTo[i]); }
            }
        }

        @Override void load(CompoundTag tag) {
            super.load(tag);
            if (!tag.getString("state").isEmpty()) state = Toggle.valueOf(tag.getString("state"));
            locked = tag.getBoolean("locked");
            if (tag.contains("travel")) travel = tag.getDouble("travel");
            moveDone = tag.contains("move_done") ? tag.getDouble("move_done") : NEVER;
            returnAt = tag.contains("return_at") ? tag.getDouble("return_at") : NEVER;
            moveFrom = tag.getDouble("move_from");
            moveStart = tag.getDouble("move_start");
            if (kind == Kind.PROP && tag.contains("ang_to0")) {
                for (int i = 0; i < 3; i++) { angFrom[i] = tag.getDouble("ang_from" + i); angTo[i] = tag.getDouble("ang_to" + i); }
            }
        }

        @Override String state() {
            return switch (state) { case AT_BOTTOM -> "closed"; case GOING_UP -> "opening"; case AT_TOP -> "open"; case GOING_DOWN -> "closing"; }
                + (locked ? ", locked" : "");
        }
    }
}
