package dev.theredja.src2mc.logic;

import dev.theredja.src2mc.bundle.LogicTable;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Volumes players and movers touch, after the SDK's {@code triggers.cpp}. A solid mover touches
 * them as Source's pushers do once they moved ({@code FinishPushers}), by its box; Minecraft has
 * none of Source's NPCs or physics objects to send through a trigger.
 */
final class Triggers {
    private Triggers() {}

    /** An entity checked against the players inside the map and its movers every tick, before anything thinks. */
    interface Touchable {
        void touch(List<ServerPlayer> players, List<Toucher> movers);
    }

    /** A solid mover and its map-local box where it is now. */
    record Toucher(LogicEntity entity, AABB box) {}

    private static final int ALLOW_CLIENTS = 0x01, ALLOW_ALL = 0x40;

    /**
     * {@code CBaseTrigger}'s touch bookkeeping. Source calls StartTouch when contact begins, Touch
     * every frame of contact and EndTouch when it ends; {@code touching} holds only those that
     * passed the trigger's filters at StartTouch, as Source's list does.
     */
    abstract static class Volume extends LogicEntity implements Touchable {
        /** Who is inside: a player's UUID, or a mover entity. */
        private final Set<Object> contacts = new LinkedHashSet<>();
        final Set<Object> touching = new LinkedHashSet<>();
        boolean disabled;

        Volume(MapLogic map, int index, LogicTable.Entity entity) { super(map, index, entity); }

        @Override void spawn() { disabled = flag("startdisabled"); }

        @Override public void touch(List<ServerPlayer> players, List<Toucher> movers) {
            LogicTable.Volume volume = map.volume(this);
            if (volume == null) return;
            Set<Object> now = new LinkedHashSet<>();
            if (!disabled && touchable()) {
                for (ServerPlayer player : players) {
                    // Source's noclip players touch triggers too; a spectator is Minecraft's noclip.
                    if (!player.isAlive() || (player.isSpectator() && !touchedBySpectators())) continue;
                    AABB box = compiled(map.local(player));
                    if (volume.intersects(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)) now.add(player.getUUID());
                }
                if (touchedByMovers()) {
                    for (Toucher mover : movers) {
                        if (mover.entity() == this) continue;
                        AABB box = compiled(mover.box());
                        if (volume.intersects(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)) now.add(mover.entity());
                    }
                }
            }
            for (Object contact : List.copyOf(contacts)) {
                if (!now.contains(contact)) { contacts.remove(contact); endTouch(actor(contact)); }
            }
            for (Object contact : now) {
                Actor actor = actor(contact);
                if (contacts.add(contact)) startTouch(actor);
                if (removed) return;
                touched(actor);
            }
        }

        private static Actor actor(Object contact) { return contact instanceof UUID id ? new PlayerActor(id) : (LogicEntity) contact; }

        private static Object contact(Actor actor) { return actor instanceof PlayerActor player ? player.id() : actor; }

        /** Whether movers touch it at all: only a trigger that lets everything through can pass one. */
        boolean touchedByMovers() { return hasSpawnFlags(ALLOW_ALL); }

        /** Whether a spectator, Minecraft's noclip, touches it. */
        boolean touchedBySpectators() { return true; }

        /** Whether the volume is in the world at all; a trigger that has fired its once is not. */
        boolean touchable() { return true; }

        /** {@code PassesTriggerFilters}: a player through the clients or everything flag, a mover only through everything. */
        boolean passes(Actor actor) {
            if (!hasSpawnFlags(actor instanceof PlayerActor ? ALLOW_CLIENTS | ALLOW_ALL : ALLOW_ALL)) return false;
            String filterName = key("filtername", "");
            if (filterName.isEmpty()) return true;
            LogicEntity filter = map.findFirst(filterName, this, actor, this);
            return !(filter instanceof LogicEntities.Filter f) || f.passes(this, actor);
        }

        void startTouch(Actor actor) {
            if (!passes(actor)) return;
            boolean added = touching.add(contact(actor));
            fire("onstarttouch", actor, null);
            if (added && touching.size() == 1) fire("onstarttouchall", actor, null);
        }

        void endTouch(Actor actor) {
            if (!touching.remove(contact(actor))) return;
            fire("onendtouch", actor, null);
            if (touching.isEmpty()) fire("onendtouchall", actor, null);
        }

        /** Touch, every tick of contact. */
        void touched(Actor actor) {}

        /** A player arriving through a level change already stands here: the contact is not a new touch. */
        void arrived(ServerPlayer player) {
            LogicTable.Volume volume = map.volume(this);
            if (volume == null) return;
            AABB box = compiled(map.local(player));
            if (volume.intersects(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)) contacts.add(player.getUUID());
        }

        /** Losing the trigger flag breaks every contact, and Source then calls EndTouch for each. */
        void disable() {
            disabled = true;
            for (Object contact : List.copyOf(contacts)) endTouch(actor(contact));
            contacts.clear();
        }

        @Override boolean accept(String input, String value, Actor activator, LogicEntity caller) {
            switch (input) {
                case "enable" -> disabled = false;
                case "disable" -> disable();
                case "toggle" -> { if (disabled) disabled = false; else disable(); }
                case "disableandendtouch" -> disable();
                case "touchtest" -> { if (!disabled) fire(touching.isEmpty() ? "onnottouching" : "ontouching", this, null); }
                default -> { return false; }
            }
            return true;
        }

        @Override void save(CompoundTag tag) {
            super.save(tag);
            tag.putBoolean("disabled", disabled);
            save(tag, "touching", touching);
            save(tag, "contacts", contacts);
        }

        @Override void load(CompoundTag tag) {
            super.load(tag);
            disabled = tag.getBoolean("disabled");
            load(tag, "touching", touching);
            load(tag, "contacts", contacts);
        }

        /** Players by UUID; movers by slot, in a list of their own. */
        private static void save(CompoundTag tag, String name, Set<Object> set) {
            ListTag players = new ListTag();
            List<Integer> entities = new java.util.ArrayList<>();
            for (Object contact : set) {
                if (contact instanceof UUID id) players.add(NbtUtils.createUUID(id));
                else if (contact instanceof LogicEntity entity && !entity.removed) entities.add(entity.index);
            }
            tag.put(name, players);
            tag.putIntArray(name + "_entities", entities.stream().mapToInt(Integer::intValue).toArray());
        }

        private void load(CompoundTag tag, String name, Set<Object> set) {
            set.clear();
            for (Tag value : tag.getList(name, Tag.TAG_INT_ARRAY)) set.add(NbtUtils.loadUUID(value));
            for (int slot : tag.getIntArray(name + "_entities")) {
                LogicEntity entity = map.entity(slot);
                if (entity != null && !entity.removed) set.add(entity);
            }
        }

        @Override String state() { return (disabled ? "disabled" : "enabled") + (touching.isEmpty() ? "" : ", touched by " + touching.size()); }
    }

    /**
     * {@code trigger_multiple} and {@code trigger_once}: OnTrigger fires on touch, then not again
     * until {@code wait} seconds have passed; a once trigger removes itself 0.1 s after firing.
     */
    static final class Trigger extends Volume {
        private double wait, readyAt;
        private boolean spent;

        Trigger(MapLogic map, int index, LogicTable.Entity entity) { super(map, index, entity); }

        @Override void spawn() {
            super.spawn();
            if (classname.equals("trigger_once")) {
                wait = -1;
            } else {
                wait = number("wait", 0);
                if (wait == 0) wait = 0.2;
            }
        }

        @Override boolean touchable() { return !spent; }

        @Override void touched(Actor actor) {
            // CTriggerMultiple::MultiTouch and ActivateMultiTrigger.
            if (spent || !passes(actor) || map.time() < readyAt) return;
            fire("ontrigger", actor, null);
            if (wait > 0) {
                readyAt = map.time() + wait;
            } else {
                spent = true;
                nextThink = map.time() + 0.1;
            }
        }

        @Override void think() { if (spent) remove(); }

        @Override void save(CompoundTag tag) {
            super.save(tag);
            tag.putDouble("ready_at", readyAt);
            tag.putBoolean("spent", spent);
        }

        @Override void load(CompoundTag tag) {
            super.load(tag);
            readyAt = tag.getDouble("ready_at");
            spent = tag.getBoolean("spent");
        }
    }

    /**
     * {@code trigger_changelevel}: a player touching it, or its ChangeLevel input, moves the player
     * to the next map if it is placed, keeping their place relative to the shared landmark; the
     * runtime does the moving ({@link LogicSystem#changeLevel}).
     */
    static final class ChangeLevel extends Volume {
        private static final int NO_TOUCH = 2;

        ChangeLevel(MapLogic map, int index, LogicTable.Entity entity) { super(map, index, entity); }

        String nextMap() { return key("map", ""); }

        String landmark() { return key("landmark", ""); }

        @Override boolean touchable() { return !hasSpawnFlags(NO_TOUCH); }



        @Override boolean passes(Actor actor) { return actor instanceof PlayerActor; }

        @Override boolean touchedByMovers() { return false; }

        /** Players who arrived standing in it, and do not take it until they have left it once. */
        private final Set<UUID> arrivedInside = new LinkedHashSet<>();

        /** {@code TouchChangeLevel} is the touch function, run every frame of contact; it does nothing for a noclipping player. */
        @Override void touched(Actor actor) {
            if (!(actor instanceof PlayerActor player) || arrivedInside.contains(player.id())) return;
            ServerPlayer target = map.serverPlayer(player);
            if (target != null && !target.isSpectator()) change(player);
        }

        @Override void arrived(ServerPlayer player) {
            super.arrived(player);
            LogicTable.Volume volume = map.volume(this);
            AABB box = compiled(map.local(player));
            if (volume != null && volume.intersects(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)) arrivedInside.add(player.getUUID());
        }

        @Override void endTouch(Actor actor) {
            if (actor instanceof PlayerActor player) arrivedInside.remove(player.id());
            super.endTouch(actor);
        }

        private void change(PlayerActor player) {
            ServerPlayer target = map.serverPlayer(player);
            if (target == null) return;
            fire("onchangelevel", player, null);
            LogicSystem.changeLevel(map, this, target);
        }

        @Override boolean accept(String input, String value, Actor activator, LogicEntity caller) {
            if (input.equals("changelevel")) {
                PlayerActor player = map.player(activator);
                if (player != null) change(player);
                return true;
            }
            return super.accept(input, value, activator, caller);
        }
    }

    /**
     * {@code trigger_teleport} and {@code trigger_teleport_relative} ({@code CTriggerTeleport::Touch},
     * {@code CTriggerTeleportRelative::Touch}): every tick a player touches it, they are moved to the
     * entity named by {@code target} (an info_teleport_destination is any entity of that name) plus,
     * with a {@code landmark}, their offset from the landmark. Without a landmark the destination's
     * angles snap the player's view too, unless flag 0x20 keeps them; velocity is never touched, as
     * in the SDK. The relative trigger moves them to its own centre plus {@code teleportoffset} and
     * zeroes their velocity. Only players are moved: Minecraft has no NPCs or physics objects here.
     */
    static final class Teleport extends Volume {
        private static final int PRESERVE_ANGLES = 0x20;
        private final boolean relative;

        Teleport(MapLogic map, int index, LogicTable.Entity entity) {
            super(map, index, entity);
            relative = classname.equals("trigger_teleport_relative");
        }

        @Override void touched(Actor actor) {
            if (!(actor instanceof PlayerActor who) || !passes(actor)) return;
            ServerPlayer player = map.serverPlayer(who);
            if (player == null || !player.isAlive()) return;
            if (relative) {
                LogicTable.Volume volume = map.volume(this);
                if (volume == null) return;
                double[] b = volume.bounds();
                double[] offset = PlayerEntities.vector(key("teleportoffset"));
                double[] to = {(b[0] + b[3]) / 2 + offset[0], (b[1] + b[4]) / 2 + offset[1], (b[2] + b[5]) / 2 + offset[2]};
                PlayerEntities.teleport(player, map.world(to), null, Vec3.ZERO);
                return;
            }
            LogicEntity target = map.findFirst(key("target", ""), null, actor, null);
            double[] at = target == null ? null : target.position();
            if (at == null) return;
            LogicEntity landmark = key("landmark", "").isEmpty() ? null : map.findFirst(key("landmark", ""), null, actor, null);
            double[] landmarkAt = landmark == null ? null : landmark.position();
            double[] to = at.clone();
            if (landmarkAt != null) {
                // The player's origin is at their feet, as Source's is (hull mins z is 0), so no feet adjustment applies.
                Vec3 feet = player.position().subtract(map.world(landmarkAt));
                to = new double[]{at[0] + feet.x, at[1] + feet.y, at[2] + feet.z};
            }
            boolean angles = landmarkAt == null && !hasSpawnFlags(PRESERVE_ANGLES);
            PlayerEntities.teleport(player, map.world(to), angles ? PlayerEntities.angles(target.key("angles")) : null, null);
        }
    }

    /**
     * {@code trigger_push} ({@code CTriggerPush::Touch}): a touching player is pushed along
     * {@code pushdir}, which is angles, at {@code speed} Source units a second (0 means 100, as
     * Source's Spawn has it). Source sets the player's base velocity, added to their movement while
     * they touch; Minecraft's client owns the movement, so {@link PlayerEntities#push} approximates
     * it. Flag 0x80 pushes once, as an impulse, and removes the trigger.
     */
    static final class Push extends Volume {
        private static final int ONCE = 0x80;
        private Vec3 velocity = Vec3.ZERO;

        Push(MapLogic map, int index, LogicTable.Entity entity) { super(map, index, entity); }

        @Override void spawn() {
            super.spawn();
            double[] angles = PlayerEntities.angles(key("pushdir"));
            double speed = number("speed", 0);
            if (speed == 0) speed = 100;
            velocity = PlayerEntities.direction(angles[0], angles[1]).scale(speed / PlayerEntities.UNITS_PER_BLOCK);
        }

        @Override void touched(Actor actor) {
            if (!(actor instanceof PlayerActor who) || !passes(actor)) return;
            ServerPlayer player = map.serverPlayer(who);
            if (player == null || !player.isAlive()) return;
            boolean once = hasSpawnFlags(ONCE);
            PlayerEntities.push(player, velocity, once);
            if (once) remove();
        }
    }

    /**
     * {@code trigger_wind}: in Source it only pushes physics objects ({@code StartTouch} skips players),
     * and Minecraft has none, so it takes part in no touch. It keeps {@code SetSpeed} and the
     * enable and disable inputs, so a chain through it neither breaks nor counts as unhandled.
     */
    static final class Wind extends Volume {
        private int speed;

        Wind(MapLogic map, int index, LogicTable.Entity entity) { super(map, index, entity); }

        @Override void spawn() {
            super.spawn();
            speed = (int) number("speed", 0);
        }

        @Override boolean touchable() { return false; }

        @Override boolean accept(String input, String value, Actor activator, LogicEntity caller) {
            if (!input.equals("setspeed")) return super.accept(input, value, activator, caller);
            speed = Variant.integer(value);
            return true;
        }

        @Override void save(CompoundTag tag) {
            super.save(tag);
            tag.putInt("speed", speed);
        }

        @Override void load(CompoundTag tag) {
            super.load(tag);
            speed = tag.getInt("speed");
        }

        @Override String state() { return super.state() + ", speed " + speed + " (physics objects only; no effect on players)"; }
    }

    /**
     * {@code trigger_hurt} ({@code CTriggerHurt}). The first touch starts a think that hurts every
     * toucher {@code damage * 0.5} each half second while anyone was hurt; a toucher who leaves
     * before ever being hurt takes half of that on leaving. Damage over time with
     * {@code DMG_RADIATION} thinks every quarter second for as long as the map runs instead, and
     * hurts everyone every time half a second has passed. Double forgiveness (damagemodel 1) doubles
     * the damage up to {@code damagecap} on each hurt and resets it after 3 s without one.
     * Negative damage heals. Source health is 100 and Minecraft's 20, so damage is scaled by
     * {@link #HEALTH_SCALE}.
     */
    static final class Hurt extends Volume {
        /** Minecraft's 20 health per Source's 100. */
        static final double HEALTH_SCALE = 0.2;
        private static final int RADIATION = 1 << 18, FORGIVENESS = 1, FORGIVE_SECONDS = 3;
        private static final double EPSILON = 1e-6;
        private double damage, original, cap, resetAt, lastAt;
        private int bits, model;
        /** Who was hurt in the latest window, for EndTouch. */
        private final Set<UUID> hurt = new LinkedHashSet<>();

        Hurt(MapLogic map, int index, LogicTable.Entity entity) { super(map, index, entity); }

        @Override void spawn() {
            super.spawn();
            damage = original = number("damage", 0);
            cap = number("damagecap", 20);
            bits = (int) number("damagetype", 0);
            model = (int) number("damagemodel", 0);
            if ((bits & RADIATION) != 0) nextThink = map.time() + LogicEntities.RANDOM.nextDouble(0.0, 0.5);
        }

        /** Gordon's {@code gordon_invulnerable} global state: Half-Life 2's player takes no damage while it is on (hl2_player.cpp:2317). */
        boolean playerInvulnerable() { return GlobalStates.isOn(map, "gordon_invulnerable"); }

        @Override boolean touchedBySpectators() { return false; }

        @Override void touched(Actor actor) {
            if (nextThink == NEVER && (bits & RADIATION) == 0) nextThink = map.time();
        }

        @Override void endTouch(Actor actor) {
            if (actor instanceof PlayerActor who && touching.contains(who.id()) && !hurt.contains(who.id())) hurtPlayer(who.id(), damage * 0.5);
            super.endTouch(actor);
        }

        @Override void think() {
            if ((bits & RADIATION) != 0) {
                double dt = map.time() - lastAt;
                if (dt >= 0.5 - EPSILON) hurtAll(dt);
                nextThink = map.time() + 0.25 - EPSILON;
            } else if (hurtAll(0.5) > 0) {
                nextThink = map.time() + 0.5 - EPSILON;
            }
        }

        /** {@code HurtAllTouchers}: the number of players hurt. */
        private int hurtAll(double dt) {
            double amount = damage * dt;
            lastAt = map.time();
            hurt.clear();
            int count = 0;
            for (Object contact : List.copyOf(touching)) if (contact instanceof UUID id && hurtPlayer(id, amount)) count++;
            if (model == FORGIVENESS) {
                if (count == 0) {
                    if (map.time() > resetAt) damage = original;
                } else {
                    damage = Math.min(damage * 2.0, cap);
                    resetAt = map.time() + FORGIVE_SECONDS;
                }
            }
            return count;
        }

        /** {@code HurtEntity} for a player: true when they could be hurt, whether or not the damage went through. */
        private boolean hurtPlayer(UUID id, double amount) {
            ServerPlayer player = map.serverPlayer(new PlayerActor(id));
            if (player == null || !player.isAlive() || player.isSpectator()) return false;
            if (amount < 0) {
                player.heal((float) (-amount * HEALTH_SCALE));
            } else if (!playerInvulnerable()) {
                // Source has no invulnerability frames; Minecraft's would swallow every second half-second tick.
                player.invulnerableTime = 0;
                player.hurt(source(player), (float) (amount * HEALTH_SCALE));
            }
            fire("onhurtplayer", new PlayerActor(id), this, null, 0);
            hurt.add(id);
            return true;
        }

        /** The nearest Minecraft damage type to the {@code DMG_*} bits (shareddefs.h:437-). */
        private DamageSource source(ServerPlayer player) {
            var sources = player.damageSources();
            if ((bits & ((1 << 3) | (1 << 21))) != 0) return sources.onFire();
            if ((bits & (1 << 14)) != 0) return sources.drown();
            if ((bits & (1 << 5)) != 0) return sources.fall();
            if ((bits & (1 << 8)) != 0) return sources.lightningBolt();
            if ((bits & ((1 << 15) | (1 << 16) | (1 << 17) | RADIATION | (1 << 20) | (1 << 26))) != 0) return sources.magic();
            return sources.generic();
        }

        @Override boolean accept(String input, String value, Actor activator, LogicEntity caller) {
            if (!input.equals("setdamage")) return super.accept(input, value, activator, caller);
            damage = Variant.number(value);
            return true;
        }

        @Override void save(CompoundTag tag) {
            super.save(tag);
            tag.putDouble("damage", damage);
            tag.putDouble("reset_at", resetAt);
            tag.putDouble("last_at", lastAt);
        }

        @Override void load(CompoundTag tag) {
            super.load(tag);
            if (tag.contains("damage")) damage = tag.getDouble("damage");
            resetAt = tag.getDouble("reset_at");
            lastAt = tag.getDouble("last_at");
        }

        @Override String state() { return super.state() + ", damage " + damage + "/s"; }
    }

    /**
     * {@code trigger_look} ({@code CTriggerLook}, a trigger_once that is not one): while a player is
     * inside, the dot product of their view direction and the direction to {@code target} must stay
     * above {@code FieldOfView} (a cosine) for {@code LookTime} seconds, which resets whenever they look
     * away, and then OnTrigger fires. {@code Timeout} seconds after they entered, OnTimeout fires and
     * OnTrigger is off until they leave. Flag 128 removes it after either; 256 uses the player's
     * velocity as the look direction.
     */
    static final class Look extends Volume {
        private static final int FIRE_ONCE = 128, USE_VELOCITY = 256;
        private static final double EPSILON = 1e-6;
        private double fov = 0.9, lookTime = 0.5, timeout, total = -1, last;
        private boolean timeoutFired, removing;
        private UUID activator;
        private LogicEntity lookTarget;

        Look(MapLogic map, int index, LogicTable.Entity entity) { super(map, index, entity); }

        @Override void spawn() {
            super.spawn();
            // The FGD defaults, which the SDK tree does not have, are only used for a key the map leaves out.
            fov = number("fieldofview", 0.9);
            lookTime = number("looktime", 0.5);
            timeout = number("timeout", 0);
            total = -1;
        }

        @Override boolean touchedByMovers() { return false; }

        @Override void startTouch(Actor actor) {
            super.startTouch(actor);
            if (actor instanceof PlayerActor who && timeout != 0) {
                timeoutFired = false;
                activator = who.id();
                nextThink = map.time() + timeout - EPSILON;
            }
        }

        @Override void endTouch(Actor actor) {
            super.endTouch(actor);
            if (actor instanceof PlayerActor) {
                nextThink = NEVER;
                total = -1;
                timeoutFired = false;
            }
        }

        @Override void think() {
            if (removing) remove();
            else if (activator != null) trigger(new PlayerActor(activator), true);
        }

        @Override void touched(Actor actor) {
            if (timeoutFired || !(actor instanceof PlayerActor who)) return;
            if (lookTarget == null || lookTarget.removed) lookTarget = map.findFirst(key("target", ""), this, actor, this);
            double[] at = lookTarget == null ? null : lookTarget.position();
            ServerPlayer player = map.serverPlayer(who);
            if (at == null || player == null) return;
            Vec3 look = hasSpawnFlags(USE_VELOCITY) ? player.getKnownMovement().normalize() : player.getLookAngle();
            double dot = look.dot(map.world(at).subtract(player.getEyePosition()).normalize());
            if (dot > fov) {
                double now = map.time();
                if (total == -1) total = 0;
                else total += now - last;
                last = now;
                if (total >= lookTime) trigger(actor, false);
            } else {
                total = -1;
            }
        }

        /** {@code CTriggerLook::Trigger}. */
        private void trigger(Actor who, boolean timedOut) {
            if (timedOut) {
                fire("ontimeout", who, null);
                timeoutFired = true;
            } else {
                fire("ontrigger", who, null);
                total = -1;
                nextThink = NEVER;
            }
            if (hasSpawnFlags(FIRE_ONCE)) {
                removing = true;
                nextThink = map.time();
            }
        }

        @Override boolean accept(String input, String value, Actor activator, LogicEntity caller) {
            switch (input) {
                case "fieldofview" -> fov = Variant.number(value);
                case "looktime" -> lookTime = Variant.number(value);
                default -> { return super.accept(input, value, activator, caller); }
            }
            return true;
        }

        @Override void save(CompoundTag tag) {
            super.save(tag);
            tag.putDouble("fov", fov);
            tag.putDouble("look_time", lookTime);
            tag.putDouble("total", total);
            tag.putDouble("last", last);
            tag.putBoolean("timeout_fired", timeoutFired);
            tag.putBoolean("removing", removing);
            if (activator != null) tag.putUUID("activator", activator);
        }

        @Override void load(CompoundTag tag) {
            super.load(tag);
            fov = tag.getDouble("fov");
            lookTime = tag.getDouble("look_time");
            total = tag.getDouble("total");
            last = tag.getDouble("last");
            timeoutFired = tag.getBoolean("timeout_fired");
            removing = tag.getBoolean("removing");
            activator = tag.hasUUID("activator") ? tag.getUUID("activator") : null;
        }
    }
}
