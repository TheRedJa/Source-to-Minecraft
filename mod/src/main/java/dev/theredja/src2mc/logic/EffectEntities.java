package dev.theredja.src2mc.logic;

import dev.theredja.src2mc.bundle.LogicTable;
import net.minecraft.nbt.CompoundTag;

/**
 * Entities that start effects the clients draw. Their state goes to the clients with the sound
 * states ({@link SoundEntities.Synced}): whether each is on and a serial that changes when it starts
 * over, so a player who comes later sees what runs.
 */
final class EffectEntities {
    private EffectEntities() {}

    /**
     * {@code info_particle_system}, after the SDK's {@code particle_system.cpp}: Start turns it on
     * when it is off (a running one is left alone), Stop ends its emission and lets its particles
     * live out. DestroyImmediately, newer than the SDK, also takes the particles away: the serial
     * moves on while it stays off, which the clients read as that. {@code start_active} starts it
     * as the map's logic spawns.
     */
    static final class ParticleSystem extends LogicEntity implements SoundEntities.Synced {
        private boolean active;
        private int serial;

        ParticleSystem(MapLogic map, int index, LogicTable.Entity entity) { super(map, index, entity); }

        @Override void spawn() {
            if (flag("start_active")) start();
        }

        private void start() {
            if (active) return;
            active = true;
            serial++;
            map.soundChanged(this);
        }

        @Override public LogicNetwork.SoundState soundState() { return new LogicNetwork.SoundState(index, active, serial, -1, -1, -1); }

        @Override boolean accept(String input, String value, Actor activator, LogicEntity caller) {
            switch (input) {
                case "start" -> start();
                case "stop", "stopplayendcap" -> { if (active) { active = false; map.soundChanged(this); } }
                case "destroyimmediately" -> { active = false; serial++; map.soundChanged(this); }
                default -> { return false; }
            }
            return true;
        }

        /** Removing the entity takes its particles with it. */
        @Override void remove() {
            if (!removed) { active = false; serial++; map.soundChanged(this); }
            super.remove();
        }

        @Override void save(CompoundTag tag) { super.save(tag); tag.putBoolean("active", active); tag.putInt("serial", serial); }
        @Override void load(CompoundTag tag) { super.load(tag); active = tag.getBoolean("active"); serial = tag.getInt("serial"); }
        @Override String state() { return active ? "on" : "off"; }
    }

    /**
     * {@code env_spark}, after the SDK's {@code envspark.cpp}: while sparking it sparks every 0.1 s
     * plus up to {@code MaxDelay} more, and once for SparkOnce, which also stops it. Each spark fires
     * OnSpark, plays {@code DoSpark} unless silent (256) and moves the serial on, which the clients
     * draw as {@code FX_ElectricSpark} at the entity, along its angles when directional (512).
     * Start on (64) sparks first 0.1 to 1.6 s after spawn.
     */
    static final class Spark extends LogicEntity implements SoundEntities.Synced {
        static final int START_ON = 64, SILENT = 256;
        private double maxDelay;
        private boolean sparking;
        private int serial;

        Spark(MapLogic map, int index, LogicTable.Entity entity) { super(map, index, entity); }

        @Override void spawn() {
            maxDelay = Math.max(0, number("maxdelay", 0));
            if (hasSpawnFlags(START_ON)) {
                sparking = true;
                nextThink = map.time() + 0.1 + LogicEntities.RANDOM.nextDouble() * 1.5;
            }
        }

        @Override void think() {
            if (!sparking) return;
            nextThink = map.time() + 0.1 + LogicEntities.RANDOM.nextDouble() * maxDelay;
            spark();
        }

        private void spark() {
            serial++;
            map.soundChanged(this);
            if (!hasSpawnFlags(SILENT)) map.emitSound("DoSpark", position(), null, null, 0, -1);
            fire("onspark", null, "");
        }

        @Override public LogicNetwork.SoundState soundState() { return new LogicNetwork.SoundState(index, sparking, serial, -1, -1, -1); }

        @Override boolean accept(String input, String value, Actor activator, LogicEntity caller) {
            switch (input) {
                case "startspark" -> start();
                case "stopspark" -> stop();
                case "togglespark" -> { if (sparking) stop(); else start(); }
                case "sparkonce" -> { spark(); stop(); }
                default -> { return false; }
            }
            return true;
        }

        private void start() {
            sparking = true;
            nextThink = map.time();
            map.soundChanged(this);
        }

        private void stop() {
            sparking = false;
            nextThink = NEVER;
            map.soundChanged(this);
        }

        @Override void save(CompoundTag tag) { super.save(tag); tag.putBoolean("sparking", sparking); tag.putInt("serial", serial); }
        @Override void load(CompoundTag tag) {
            super.load(tag);
            maxDelay = Math.max(0, number("maxdelay", 0));
            sparking = tag.getBoolean("sparking");
            serial = tag.getInt("serial");
        }
        @Override String state() { return sparking ? "sparking" : "off"; }
    }
}
