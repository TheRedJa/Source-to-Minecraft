package dev.theredja.src2mc.logic;

import dev.theredja.src2mc.Src2mcIds;
import dev.theredja.src2mc.bundle.LogicTable;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/**
 * Entities that act on the player, and the helpers they and the player triggers share.
 *
 * <p>Coordinates: a map-local position is in blocks, and a Source vector {@code (x, y, z)} is
 * {@code (x, z, -y) / 32} in it (docs/format.md, the model-space mapping; the logic table's origins
 * are in the same space). So Source's yaw {@code a} is Minecraft's {@code -90 - a}, as
 * {@code MenuEntities.ViewControl.enable} has it, and pitch (down positive) is the same in both.
 * Players are moved by their feet, which is where Source's player origin is (hull mins z is 0).
 */
final class PlayerEntities {
    private PlayerEntities() {}

    static final double UNITS_PER_BLOCK = 32.0;

    /** {@code pitch yaw roll} of an angles keyvalue (or any three numbers); missing parts are 0. */
    static double[] angles(String value) { return MenuEntities.angles(value); }

    /** A Source vector keyvalue {@code "x y z"} as a map-local offset in blocks. */
    static double[] vector(String value) {
        double[] v = MenuEntities.angles(value);
        return new double[]{v[0] / UNITS_PER_BLOCK, v[2] / UNITS_PER_BLOCK, -v[1] / UNITS_PER_BLOCK};
    }

    static float yaw(double sourceYaw) { return (float) Mth.wrapDegrees(-90.0 - sourceYaw); }

    /** {@code AngleVectors}' forward vector of Source angles, in Minecraft's axes. */
    static Vec3 direction(double pitch, double yaw) {
        double p = Math.toRadians(pitch), y = Math.toRadians(yaw);
        return new Vec3(Math.cos(p) * Math.cos(y), -Math.sin(p), -Math.cos(p) * Math.sin(y));
    }

    /**
     * {@code CBaseEntity::Teleport} for a player: origin at {@code world} (their feet), the view
     * snapped to Source {@code angles} ({@code SnapEyeAngles}) when given, velocity kept unless
     * {@code motion} says otherwise. A position packet makes the client forget its velocity, so
     * the velocity the client last reported is sent again after it.
     */
    static void teleport(ServerPlayer player, Vec3 world, double[] angles, Vec3 motion) {
        Vec3 keep = motion != null ? motion : player.getKnownMovement();
        float yaw = angles == null ? player.getYRot() : yaw(angles[1]);
        float pitch = angles == null ? player.getXRot() : (float) Mth.clamp(angles[0], -90.0, 90.0);
        if (player.isPassenger()) player.stopRiding();
        player.connection.teleport(world.x, world.y, world.z, yaw, pitch);
        player.setDeltaMovement(keep);
        player.hurtMarked = true;
        // Landing inside a mover (the intro's train): ride it from the first tick.
        MoverSystem.freezeOnto(player);
    }

    /**
     * {@code trigger_push}'s push on a player, {@code velocity} in blocks a second. Source's
     * {@code ApplyAbsVelocityImpulse} (push once) is an impulse; its base velocity (every other
     * touch) is added to the player's movement for as long as they touch, without piling up, which
     * the client's movement cannot do. It is approximated by an impulse that, against the drag
     * Minecraft applies to horizontal movement each tick (block friction times 0.91 on the ground,
     * 0.91 in the air), settles at {@code velocity}; vertically it lifts or lowers the velocity to
     * at least that of the push. The player's velocity is the client's last reported one.
     */
    static void push(ServerPlayer player, Vec3 velocity, boolean impulse) {
        Vec3 known = player.getKnownMovement();
        Vec3 perTick = velocity.scale(1.0 / 20.0);
        Vec3 next;
        if (impulse) {
            next = known.add(perTick);
        } else {
            double drag = 0.91;
            if (player.onGround()) {
                BlockPos below = player.getBlockPosBelowThatAffectsMyMovement();
                drag *= player.level().getBlockState(below).getBlock().getFriction();
            }
            double y = perTick.y > 0 ? Math.max(known.y, perTick.y) : perTick.y < 0 ? Math.min(known.y, perTick.y) : known.y;
            next = new Vec3(known.x + perTick.x * (1 - drag), y, known.z + perTick.z * (1 - drag));
        }
        player.setDeltaMovement(next);
        player.hurtMarked = true;
    }

    private static void setSpeedModifier(ServerPlayer player, ResourceLocation id, double scale) {
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) return;
        if (Math.abs(scale - 1.0) < 1e-6) speed.removeModifier(id);
        else speed.addOrUpdateTransientModifier(new AttributeModifier(id, scale - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }

    /**
     * {@code point_teleport} ({@code CPointTeleport}): Teleport moves the entity named by
     * {@code target} to this entity's origin and angles; velocity is kept. With flag 1 the
     * destination is the target's own origin and angles when the map starts, and the entity removes
     * itself when the target is missing. Only the player can be moved (target {@code !player} or
     * {@code !activator}); another target counts as an unhandled input.
     */
    static final class PointTeleport extends LogicEntity {
        private static final int TO_SPAWN_POS = 1;
        private double[] origin, angles;

        PointTeleport(MapLogic map, int index, LogicTable.Entity entity) { super(map, index, entity); }

        /** Targets that name the player, who is not in the map yet when it spawns. */
        private boolean targetsPlayer() {
            return switch (key("target", "").trim().toLowerCase(java.util.Locale.ROOT)) {
                case "!player", "!pvsplayer", "!picker", "!speechtarget" -> true;
                default -> false;
            };
        }

        @Override void activate() {
            if (!hasSpawnFlags(TO_SPAWN_POS) || origin != null) return;
            // pointteleport.cpp:73-110: "!player" is the player in Source, who is there by the time it
            // looks. Here nobody is inside as the map spawns, so the pose is saved on the first tick
            // that has a player; the entity does not remove itself for want of one.
            if (targetsPlayer()) {
                if (!savePlayerPose()) nextThink = map.time();
                return;
            }
            LogicEntity target = map.findFirst(key("target", ""), this, null, null);
            if (target == null) {
                remove();
            } else if (target.position() != null) {
                origin = target.position();
                angles = angles(target.key("angles"));
            }
        }

        @Override void think() {
            if (origin == null && hasSpawnFlags(TO_SPAWN_POS) && targetsPlayer() && !savePlayerPose()) nextThink = map.time();
        }

        /** Saves the pose of the player the map is about as the spawn pose; false when there is none yet. */
        private boolean savePlayerPose() {
            PlayerActor who = map.player(null);
            ServerPlayer player = who == null ? null : map.serverPlayer(who);
            double[] at = who == null ? null : map.playerPosition(who);
            if (player == null || at == null) return false;
            origin = at;
            // Source angles: pitch, then yaw, which is Minecraft's turned a quarter and mirrored.
            angles = new double[]{player.getXRot(), Mth.wrapDegrees(-90.0 - player.getYRot()), 0};
            return true;
        }

        @Override boolean accept(String input, String value, Actor activator, LogicEntity caller) {
            if (!input.equals("teleport")) return false;
            String name = key("target", "").trim();
            PlayerActor who = switch (name.toLowerCase(java.util.Locale.ROOT)) {
                case "!player" -> map.player(activator);
                case "!activator" -> activator instanceof PlayerActor actor ? actor : null;
                default -> null;
            };
            ServerPlayer player = who == null ? null : map.serverPlayer(who);
            if (player != null) {
                double[] at = origin != null ? origin : position();
                if (at != null) teleport(player, map.world(at), origin != null ? angles : angles(key("angles")), null);
            } else {
                LogicEntity found = map.findFirst(name, this, activator, caller);
                if (found != null) map.unhandled(this, "teleport " + found.classname);
            }
            return true;
        }

        @Override void save(CompoundTag tag) {
            super.save(tag);
            if (origin == null) return;
            ListTag list = new ListTag();
            for (double v : new double[]{origin[0], origin[1], origin[2], angles[0], angles[1], angles[2]}) list.add(net.minecraft.nbt.DoubleTag.valueOf(v));
            tag.put("saved_pose", list);
        }

        @Override void load(CompoundTag tag) {
            super.load(tag);
            ListTag list = tag.getList("saved_pose", Tag.TAG_DOUBLE);
            if (list.size() != 6) return;
            origin = new double[]{list.getDouble(0), list.getDouble(1), list.getDouble(2)};
            angles = new double[]{list.getDouble(3), list.getDouble(4), list.getDouble(5)};
        }
    }

    /**
     * {@code player_speedmod} ({@code CMovementSpeedMod}): ModifySpeed sets the player's movement
     * speed to that multiple ({@code SetLaggedMovementValue}), as an attribute modifier with the id
     * {@code src2mc:speedmod}, removed at 1. The activator, else the first player of the map. The
     * flags that suppress weapons, the HUD and buttons, and the flashlight, are not done. Minecraft's
     * field of view follows movement speed, so a slowed player also sees it narrow. A transient
     * modifier does not survive a relog, so the value is kept here and put back once a second;
     * the values are shared between all the map's speedmods, as Source's one value is.
     */
    static final class SpeedMod extends LogicEntity {
        private static final ResourceLocation ID = Src2mcIds.id("speedmod");
        private static final double REASSERT_SECONDS = 1.0;
        /** What each player's speed is set to, by everyone who has set it. */
        static final Map<UUID, Double> CURRENT = new HashMap<>();
        private final Set<UUID> players = new LinkedHashSet<>();

        SpeedMod(MapLogic map, int index, LogicTable.Entity entity) { super(map, index, entity); }

        /** Forgets every player's speed, when the server stops. */
        static void clear() { CURRENT.clear(); }

        /** Gives the players this entity slowed or sped back their speed. */
        private void release() {
            for (UUID id : List.copyOf(players)) {
                CURRENT.remove(id);
                ServerPlayer player = map.serverPlayer(new PlayerActor(id));
                if (player != null) setSpeedModifier(player, ID, 1.0);
            }
            players.clear();
        }

        @Override void remove() {
            release();
            super.remove();
        }

        @Override void stopped() { release(); }

        @Override boolean accept(String input, String value, Actor activator, LogicEntity caller) {
            if (!input.equals("modifyspeed")) return false;
            PlayerActor who = map.player(activator);
            ServerPlayer player = who == null ? null : map.serverPlayer(who);
            if (player == null) return true;
            double scale = Variant.number(value);
            if (scale == 1.0) {
                CURRENT.remove(who.id());
                players.remove(who.id());
            } else {
                CURRENT.put(who.id(), scale);
                players.add(who.id());
                if (nextThink == NEVER) nextThink = map.time() + REASSERT_SECONDS;
            }
            setSpeedModifier(player, ID, scale);
            return true;
        }

        @Override void think() {
            for (UUID id : List.copyOf(players)) {
                Double scale = CURRENT.get(id);
                ServerPlayer player = map.serverPlayer(new PlayerActor(id));
                if (scale == null) players.remove(id);
                else if (player != null) setSpeedModifier(player, ID, scale);
            }
            if (!players.isEmpty()) nextThink = map.time() + REASSERT_SECONDS;
        }

        @Override void save(CompoundTag tag) {
            super.save(tag);
            ListTag ids = new ListTag();
            ListTag scales = new ListTag();
            for (UUID id : players) {
                if (!CURRENT.containsKey(id)) continue;
                ids.add(NbtUtils.createUUID(id));
                scales.add(net.minecraft.nbt.DoubleTag.valueOf(CURRENT.get(id)));
            }
            tag.put("players", ids);
            tag.put("scales", scales);
        }

        @Override void load(CompoundTag tag) {
            super.load(tag);
            players.clear();
            ListTag ids = tag.getList("players", Tag.TAG_INT_ARRAY);
            ListTag scales = tag.getList("scales", Tag.TAG_DOUBLE);
            for (int i = 0; i < ids.size() && i < scales.size(); i++) {
                UUID id = NbtUtils.loadUUID(ids.get(i));
                players.add(id);
                CURRENT.put(id, scales.getDouble(i));
            }
            if (!players.isEmpty() && nextThink == NEVER) nextThink = map.time();
        }

        @Override String state() { return players.isEmpty() ? "" : "speed set for " + players.size() + " player(s)"; }
    }

    /**
     * {@code point_playermoveconstraint} ({@code CPointPlayerMoveConstraint}, with the player's side
     * from gamemovement.cpp:943-976). TurnOn constrains every player in the map around this entity:
     * inside the ring from {@code radius - width} to {@code radius}, a player moving away from the
     * centre is slowed, linearly from full speed to {@code speedfactor}; past {@code radius}, checked
     * every 0.1 s, the constraint is dropped and OnConstraintBroken fires. TurnOff drops it without
     * firing. The slowdown is a movement speed modifier, {@code src2mc:moveconstraint}, set every tick;
     * "moving away" is the client's last reported movement, where Source uses the wished direction.
     * The SDK's inputs are TurnOn and TurnOff; the activator of OnConstraintBroken is this entity,
     * and its caller, the player in Source, is not a logic entity here.
     */
    static final class MoveConstraint extends LogicEntity {
        private static final ResourceLocation ID = Src2mcIds.id("moveconstraint");
        private static final double CHECK_SECONDS = 0.1, EPSILON = 1e-6;
        private final Set<UUID> constrained = new LinkedHashSet<>();
        private double radius, width, factor = 1, checkedAt;

        MoveConstraint(MapLogic map, int index, LogicTable.Entity entity) { super(map, index, entity); }

        @Override void spawn() {
            radius = number("radius", 0) / UNITS_PER_BLOCK;
            width = number("width", 0) / UNITS_PER_BLOCK;
            factor = number("speedfactor", 1);
        }

        @Override boolean accept(String input, String value, Actor activator, LogicEntity caller) {
            switch (input) {
                case "turnon" -> {
                    for (ServerPlayer player : map.inside()) constrained.add(player.getUUID());
                    checkedAt = map.time();
                    if (!constrained.isEmpty()) nextThink = map.time();
                }
                case "turnoff" -> { for (UUID id : List.copyOf(constrained)) release(id); }
                default -> { return false; }
            }
            return true;
        }

        private void release(UUID id) {
            constrained.remove(id);
            ServerPlayer player = map.serverPlayer(new PlayerActor(id));
            if (player != null) setSpeedModifier(player, ID, 1.0);
        }

        @Override void think() {
            double[] at = position();
            if (at == null || constrained.isEmpty()) return;
            Vec3 center = map.world(at);
            boolean check = map.time() - checkedAt >= CHECK_SECONDS - EPSILON;
            if (check) checkedAt = map.time();
            for (UUID id : List.copyOf(constrained)) {
                ServerPlayer player = null;
                for (ServerPlayer inside : map.inside()) if (inside.getUUID().equals(id)) player = inside;
                if (player == null) { release(id); continue; }
                Vec3 offset = player.position().subtract(center);
                double distance = offset.length();
                if (check && distance > radius) {
                    release(id);
                    fire("onconstraintbroken", this, null, null, 0);
                    continue;
                }
                double scale = 1.0;
                double inner = radius - width;
                if (distance > inner && distance < radius) {
                    Vec3 moving = player.getKnownMovement();
                    if (moving.x * offset.x + moving.z * offset.z >= 0) scale = 1.0 + (factor - 1.0) * ((distance - inner) / width);
                }
                setSpeedModifier(player, ID, scale);
            }
            if (!constrained.isEmpty()) nextThink = map.time();
        }

        @Override void remove() {
            for (UUID id : List.copyOf(constrained)) release(id);
            super.remove();
        }

        @Override void stopped() {
            for (UUID id : List.copyOf(constrained)) release(id);
        }

        @Override void save(CompoundTag tag) {
            super.save(tag);
            ListTag ids = new ListTag();
            for (UUID id : constrained) ids.add(NbtUtils.createUUID(id));
            tag.put("constrained", ids);
        }

        @Override void load(CompoundTag tag) {
            super.load(tag);
            constrained.clear();
            for (Tag value : tag.getList("constrained", Tag.TAG_INT_ARRAY)) constrained.add(NbtUtils.loadUUID(value));
            if (!constrained.isEmpty()) nextThink = map.time();
        }

        @Override String state() { return constrained.isEmpty() ? "off" : "constraining " + constrained.size() + " player(s)"; }
    }
}
