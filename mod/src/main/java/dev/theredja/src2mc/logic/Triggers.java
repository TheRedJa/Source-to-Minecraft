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
import net.minecraft.world.phys.AABB;

/**
 * Volumes players touch, after the SDK's {@code triggers.cpp}. Only players touch them here:
 * Minecraft has none of Source's NPCs or physics objects to send through a trigger.
 */
final class Triggers {
    private Triggers() {}

    /** An entity checked against the players inside the map every tick, before anything thinks. */
    interface Touchable {
        void touch(List<ServerPlayer> players);
    }

    private static final int ALLOW_CLIENTS = 0x01, ALLOW_ALL = 0x40;

    /**
     * {@code CBaseTrigger}'s touch bookkeeping. Source calls StartTouch when contact begins, Touch
     * every frame of contact and EndTouch when it ends; {@code touching} holds only those that
     * passed the trigger's filters at StartTouch, as Source's list does.
     */
    abstract static class Volume extends LogicEntity implements Touchable {
        private final Set<UUID> contacts = new LinkedHashSet<>();
        final Set<UUID> touching = new LinkedHashSet<>();
        boolean disabled;

        Volume(MapLogic map, int index, LogicTable.Entity entity) { super(map, index, entity); }

        @Override void spawn() { disabled = flag("startdisabled"); }

        @Override public void touch(List<ServerPlayer> players) {
            LogicTable.Volume volume = map.volume(this);
            if (volume == null) return;
            Set<UUID> now = new LinkedHashSet<>();
            if (!disabled && touchable()) {
                for (ServerPlayer player : players) {
                    // Source's noclip players touch triggers too; a spectator is Minecraft's noclip.
                    if (!player.isAlive() || (player.isSpectator() && !touchedBySpectators())) continue;
                    AABB box = map.local(player);
                    if (volume.intersects(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)) now.add(player.getUUID());
                }
            }
            for (UUID id : List.copyOf(contacts)) {
                if (!now.contains(id)) { contacts.remove(id); endTouch(new PlayerActor(id)); }
            }
            for (UUID id : now) {
                PlayerActor player = new PlayerActor(id);
                if (contacts.add(id)) startTouch(player);
                if (removed) return;
                touched(player);
            }
        }

        /** Whether a spectator, Minecraft's noclip, touches it. */
        boolean touchedBySpectators() { return true; }

        /** Whether the volume is in the world at all; a trigger that has fired its once is not. */
        boolean touchable() { return true; }

        /** {@code PassesTriggerFilters} for a player. */
        boolean passes(PlayerActor player) {
            if (!hasSpawnFlags(ALLOW_CLIENTS | ALLOW_ALL)) return false;
            String filterName = key("filtername", "");
            if (filterName.isEmpty()) return true;
            LogicEntity filter = map.findFirst(filterName, this, player, this);
            return !(filter instanceof LogicEntities.Filter f) || f.passes(this, player);
        }

        void startTouch(PlayerActor player) {
            if (!passes(player)) return;
            boolean added = touching.add(player.id());
            fire("onstarttouch", player, null);
            if (added && touching.size() == 1) fire("onstarttouchall", player, null);
        }

        void endTouch(PlayerActor player) {
            if (!touching.remove(player.id())) return;
            fire("onendtouch", player, null);
            if (touching.isEmpty()) fire("onendtouchall", player, null);
        }

        /** Touch, every tick of contact. */
        void touched(PlayerActor player) {}

        /** A player arriving through a level change already stands here: the contact is not a new touch. */
        void arrived(ServerPlayer player) {
            LogicTable.Volume volume = map.volume(this);
            if (volume == null) return;
            AABB box = map.local(player);
            if (volume.intersects(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)) contacts.add(player.getUUID());
        }

        /** Losing the trigger flag breaks every contact, and Source then calls EndTouch for each. */
        void disable() {
            disabled = true;
            for (UUID id : List.copyOf(contacts)) endTouch(new PlayerActor(id));
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
            ListTag list = new ListTag();
            for (UUID id : touching) list.add(NbtUtils.createUUID(id));
            tag.put("touching", list);
            ListTag contactList = new ListTag();
            for (UUID id : contacts) contactList.add(NbtUtils.createUUID(id));
            tag.put("contacts", contactList);
        }

        @Override void load(CompoundTag tag) {
            super.load(tag);
            disabled = tag.getBoolean("disabled");
            touching.clear();
            for (Tag value : tag.getList("touching", Tag.TAG_INT_ARRAY)) touching.add(NbtUtils.loadUUID(value));
            contacts.clear();
            for (Tag value : tag.getList("contacts", Tag.TAG_INT_ARRAY)) contacts.add(NbtUtils.loadUUID(value));
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

        @Override void touched(PlayerActor player) {
            // CTriggerMultiple::MultiTouch and ActivateMultiTrigger.
            if (spent || !passes(player) || map.time() < readyAt) return;
            fire("ontrigger", player, null);
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



        @Override boolean passes(PlayerActor player) { return true; }

        /** Players who arrived standing in it, and do not take it until they have left it once. */
        private final Set<UUID> arrivedInside = new LinkedHashSet<>();

        /** {@code TouchChangeLevel} is the touch function, run every frame of contact; it does nothing for a noclipping player. */
        @Override void touched(PlayerActor player) {
            if (arrivedInside.contains(player.id())) return;
            ServerPlayer target = map.serverPlayer(player);
            if (target != null && !target.isSpectator()) change(player);
        }

        @Override void arrived(ServerPlayer player) {
            super.arrived(player);
            LogicTable.Volume volume = map.volume(this);
            AABB box = map.local(player);
            if (volume != null && volume.intersects(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)) arrivedInside.add(player.getUUID());
        }

        @Override void endTouch(PlayerActor player) {
            arrivedInside.remove(player.id());
            super.endTouch(player);
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
}
