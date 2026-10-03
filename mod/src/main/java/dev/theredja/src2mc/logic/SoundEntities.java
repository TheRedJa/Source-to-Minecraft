package dev.theredja.src2mc.logic;

import dev.theredja.src2mc.bundle.AudioTable;
import dev.theredja.src2mc.bundle.LogicTable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;

/**
 * The entities that make sound, as the server keeps them: whether each plays and how loud. The
 * clients play the sound itself; {@link Synced} entities send their state whenever it changes,
 * so a player who comes later still hears what is playing.
 */
final class SoundEntities {
    private SoundEntities() {}

    /** An entity whose sound state the clients keep a copy of. */
    interface Synced {
        LogicNetwork.SoundState soundState();
    }

    /**
     * {@code CAmbientGeneric}, after the SDK's {@code sound.cpp}. A looping one is "active" while
     * switched on; a one-shot is never active and plays once per PlaySound. A Volume input sets an
     * absolute level, as {@code SND_CHANGE_VOL} does even for a soundscript; PlaySound returns it
     * to the entity's own ({@code InitModulationParms}). Fades step at Source's 5 Hz.
     */
    static final class Ambient extends LogicEntity implements Synced {
        private static final int START_SILENT = 16, NOT_LOOPING = 32;
        private static final double RAMP_INTERVAL = 0.2;
        private boolean looping, active, playing;
        private int serial;
        /** 0..100, or -1 for the sound's own level. */
        private double volume = -1;
        private int pitch = -1;
        /** Per ramp step, as Source's {@code fadein}/{@code fadeout}; 0 for none. */
        private double fadeIn, fadeOut, fadeInSaved, fadeOutSaved;

        Ambient(MapLogic map, int index, LogicTable.Entity entity) { super(map, index, entity); }

        private int volumeRun() { return (int) Math.max(0, Math.min(100, number("health", 10) * 10)); }

        @Override void spawn() {
            looping = !hasSpawnFlags(NOT_LOOPING);
            double fadeInSeconds = Math.min(100, Math.max(0, Variant.integer(key("fadeinsecs")))),
                fadeOutSeconds = Math.min(100, Math.max(0, Variant.integer(key("fadeoutsecs"))));
            fadeInSaved = fadeInSeconds > 0 ? 100 / (fadeInSeconds / RAMP_INTERVAL) : 0;
            fadeOutSaved = fadeOutSeconds > 0 ? 100 / (fadeOutSeconds / RAMP_INTERVAL) : 0;
            // Precache: an ambient that is not silent at start and loops is active from spawn.
            if (!hasSpawnFlags(START_SILENT) && looping) {
                active = true;
                playing = true;
                serial = 1;
            }
        }

        @Override public LogicNetwork.SoundState soundState() {
            return new LogicNetwork.SoundState(index, playing, serial, volume < 0 ? -1 : (float) (volume / 100), pitch < 0 ? -1 : pitch / 100F, -1);
        }

        private void changed() { map.soundChanged(this); }

        @Override boolean accept(String input, String value, Actor activator, LogicEntity caller) {
            switch (input) {
                case "playsound" -> { if (!active) toggle(); }
                case "stopsound" -> { if (active) toggle(); }
                case "togglesound" -> toggle();
                case "volume" -> { volume = Math.max(0, Math.min(100, Math.round(Variant.number(value) * 10))); changed(); }
                case "pitch" -> { pitch = (int) Math.max(0, Math.min(255, Variant.number(value))); changed(); }
                case "fadein" -> {
                    fadeOut = 0;
                    double seconds = Math.max(0, Math.min(100, Variant.number(value)));
                    fadeIn = seconds > 0 ? 100 / (seconds / RAMP_INTERVAL) : 0;
                    nextThink = map.time() + 0.1;
                }
                case "fadeout" -> {
                    fadeIn = 0;
                    double seconds = Math.max(0, Math.min(100, Variant.number(value)));
                    fadeOut = seconds > 0 ? 100 / (seconds / RAMP_INTERVAL) : 0;
                    nextThink = map.time() + 0.1;
                }
                default -> { return false; }
            }
            return true;
        }

        /** {@code ToggleSound}. */
        private void toggle() {
            if (active) {
                active = false;
                if (fadeOutSaved > 0) {
                    fadeOut = fadeOutSaved;
                    fadeIn = 0;
                    nextThink = map.time() + 0.1;
                } else {
                    playing = false;
                    changed();
                }
            } else {
                if (looping) active = true;
                // InitModulationParms: back to the entity's own level, or fading in from silence.
                fadeIn = fadeInSaved;
                fadeOut = 0;
                volume = fadeIn > 0 ? 0 : -1;
                pitch = -1;
                playing = looping;
                serial++;
                changed();
                if (fadeIn > 0) nextThink = map.time() + 0.1;
            }
        }

        /** {@code RampThink}, amplitude only. */
        @Override void think() {
            if (fadeIn == 0 && fadeOut == 0) return;
            double current = volume < 0 ? volumeRun() : volume;
            if (fadeIn > 0) {
                current += fadeIn;
                if (current >= volumeRun()) { current = volumeRun(); fadeIn = 0; }
            } else {
                current -= fadeOut;
                if (current < 0) {
                    // Faded out: the sound stops, the entity stays active, as in Source.
                    volume = 0;
                    fadeOut = 0;
                    playing = false;
                    changed();
                    return;
                }
            }
            volume = Math.max(1, Math.min(100, Math.floor(current)));
            changed();
            if (fadeIn > 0 || fadeOut > 0) nextThink = map.time() + RAMP_INTERVAL;
        }

        @Override void save(CompoundTag tag) {
            super.save(tag);
            tag.putBoolean("active", active); tag.putBoolean("playing", playing); tag.putInt("serial", serial);
            tag.putDouble("volume", volume); tag.putInt("pitch", pitch);
            tag.putDouble("fade_in", fadeIn); tag.putDouble("fade_out", fadeOut);
        }

        @Override void load(CompoundTag tag) {
            super.load(tag);
            active = tag.getBoolean("active"); playing = tag.getBoolean("playing"); serial = tag.getInt("serial");
            volume = tag.getDouble("volume"); pitch = tag.getInt("pitch");
            fadeIn = tag.getDouble("fade_in"); fadeOut = tag.getDouble("fade_out");
        }

        @Override String state() {
            return (playing ? "playing" : active ? "active, silent" : "off") + (volume >= 0 ? ", volume " + (int) volume : "")
                + (pitch >= 0 ? ", pitch " + pitch : "");
        }
    }

    /** {@code env_soundscape}: whether it may be chosen; the clients choose. */
    static final class Soundscape extends LogicEntity implements Synced {
        private boolean enabled;

        Soundscape(MapLogic map, int index, LogicTable.Entity entity) { super(map, index, entity); }

        @Override void spawn() { enabled = !flag("startdisabled"); }

        @Override public LogicNetwork.SoundState soundState() { return new LogicNetwork.SoundState(index, enabled, 0, -1, -1, -1); }

        @Override boolean accept(String input, String value, Actor activator, LogicEntity caller) {
            switch (input) {
                case "enable" -> enabled = true;
                case "disable" -> enabled = false;
                case "toggleenabled" -> enabled = !enabled;
                default -> { return false; }
            }
            map.soundChanged(this);
            return true;
        }

        @Override void save(CompoundTag tag) { super.save(tag); tag.putBoolean("enabled", enabled); }
        @Override void load(CompoundTag tag) { super.load(tag); enabled = tag.getBoolean("enabled"); }
        @Override String state() { return enabled ? "enabled" : "disabled"; }
    }

    /**
     * INFRA's {@code infra_music}, by its FGD: a soundscript heard everywhere, started, stopped and
     * faded by inputs, firing OnFinishedPlaying at its end; with {@code loop} it starts over instead.
     * The server picks which of the script's files plays, so it knows when that file ends.
     */
    static final class Music extends LogicEntity implements Synced {
        private static final double FADE_STEP = MapLogic.TICK_SECONDS;
        private boolean playing;
        private int serial, sound = -1;
        private double level = 1, fadeRate, endsAt = NEVER;

        Music(MapLogic map, int index, LogicTable.Entity entity) { super(map, index, entity); }

        @Override public LogicNetwork.SoundState soundState() {
            return new LogicNetwork.SoundState(index, playing, serial, (float) level, -1, sound);
        }

        private AudioTable.Script script() {
            return map.audio == null ? null : map.audio.script(MapLogic.normalizeSound(key("sound", "")));
        }

        private void start(double fromLevel) {
            AudioTable.Script script = script();
            if (script == null) { map.unhandled(this, "start (no sound)"); return; }
            sound = script.sound(LogicEntities.RANDOM.nextInt(script.soundCount()));
            AudioTable.Sound asset = map.audio.sounds().get(sound);
            playing = true;
            serial++;
            level = fromLevel;
            endsAt = map.time() + asset.frames() / (double) asset.sampleRate();
            map.soundChanged(this);
            schedule();
        }

        private void schedule() { nextThink = fadeRate != 0 ? map.time() + FADE_STEP : endsAt; }

        @Override void think() {
            if (!playing) return;
            if (fadeRate != 0) {
                level = Math.max(0, Math.min(1, level + fadeRate * FADE_STEP));
                if (level <= 0) { stop(); return; }
                if (level >= 1) fadeRate = 0;
                map.soundChanged(this);
            }
            if (map.time() >= endsAt) {
                if (flag("loop")) { start(level); return; }
                stop();
                fire("onfinishedplaying", this, null);
                return;
            }
            schedule();
        }

        private void stop() {
            playing = false;
            fadeRate = 0;
            endsAt = NEVER;
            nextThink = NEVER;
            map.soundChanged(this);
        }

        @Override boolean accept(String input, String value, Actor activator, LogicEntity caller) {
            switch (input) {
                case "start" -> { fadeRate = 0; start(1); }
                case "stop", "pause" -> stop();
                case "resume" -> { if (!playing) { fadeRate = 0; start(1); } }
                case "fadein" -> {
                    double seconds = Variant.number(value);
                    fadeRate = seconds > 0 ? 1 / seconds : 0;
                    start(seconds > 0 ? 0 : 1);
                }
                case "fadeout" -> {
                    double seconds = Variant.number(value);
                    if (!playing) return true;
                    if (seconds <= 0) stop();
                    else { fadeRate = -level / seconds; schedule(); }
                }
                default -> { return false; }
            }
            return true;
        }

        @Override void save(CompoundTag tag) {
            super.save(tag);
            tag.putBoolean("playing", playing); tag.putInt("serial", serial); tag.putInt("sound", sound);
            tag.putDouble("level", level); tag.putDouble("fade_rate", fadeRate);
            if (endsAt != NEVER) tag.putDouble("ends_at", endsAt);
        }

        @Override void load(CompoundTag tag) {
            super.load(tag);
            playing = tag.getBoolean("playing"); serial = tag.getInt("serial"); sound = tag.getInt("sound");
            level = tag.getDouble("level"); fadeRate = tag.getDouble("fade_rate");
            endsAt = tag.contains("ends_at") ? tag.getDouble("ends_at") : NEVER;
        }

        @Override String state() { return playing ? String.format(Locale.ROOT, "playing at %.2f", level) : "stopped"; }
    }

    /**
     * {@code logic_choreographed_scene}: plays its scene's lines and fires its triggers at their
     * times, then OnCompletion. Only {@code speak} and {@code firetrigger} events exist here; the
     * actors' faces and gestures have nothing to drive. A {@code busyactor} of 1 makes the start
     * wait while one of its actors is still speaking another scene's line, as Source does.
     */
    static final class Scene extends LogicEntity {
        private boolean playing, paused, waiting;
        private double elapsed;
        private int next;
        private Actor activator;
        private final List<Long> voices = new ArrayList<>();

        Scene(MapLogic map, int index, LogicTable.Entity entity) { super(map, index, entity); }

        @Override boolean accept(String input, String value, Actor by, LogicEntity caller) {
            switch (input) {
                case "start" -> {
                    if (playing || waiting) return true;
                    activator = by;
                    if (Variant.integer(key("busyactor", "1")) == 1 && busy()) {
                        waiting = true;
                        nextThink = map.time();
                    } else {
                        begin();
                    }
                }
                case "pause" -> paused = true;
                case "resume" -> paused = false;
                case "cancel" -> { if (playing || waiting) { stopVoices(); playing = waiting = false; nextThink = NEVER; fire("oncanceled", by, null); } }
                default -> { return false; }
            }
            return true;
        }

        @Override void remove() {
            stopVoices();
            super.remove();
        }

        private void begin() {
            LogicTable.Scene scene = map.scene(this);
            waiting = false;
            if (scene == null) {
                map.unhandled(this, "start (scene unavailable)");
                fire("oncompletion", activator, null);
                return;
            }
            playing = true;
            paused = false;
            elapsed = 0;
            next = 0;
            fire("onstart", activator, null);
            run(scene);
        }

        @Override void think() {
            if (waiting) {
                if (busy()) { nextThink = map.time() + MapLogic.TICK_SECONDS; return; }
                begin();
                return;
            }
            if (!playing) return;
            LogicTable.Scene scene = map.scene(this);
            if (scene == null) return;
            if (!paused) elapsed += MapLogic.TICK_SECONDS;
            run(scene);
        }

        private void run(LogicTable.Scene scene) {
            List<LogicTable.SceneEvent> events = scene.events();
            while (!paused && next < events.size() && events.get(next).start() <= elapsed + 1e-9) {
                LogicTable.SceneEvent event = events.get(next++);
                if (event.speaks()) speak(event);
                else fire(String.format(Locale.ROOT, "ontrigger%d", event.trigger()), activator, null);
                if (removed || !playing) return;
            }
            if (next >= events.size() && elapsed + 1e-9 >= scene.length()) {
                playing = false;
                voices.clear();
                fire("oncompletion", activator, null);
                return;
            }
            nextThink = map.time() + MapLogic.TICK_SECONDS;
        }

        private void speak(LogicTable.SceneEvent event) {
            String actor = event.actor();
            UUID speaker = null;
            double[] at = null;
            if (actor.equals("player") || actor.equals("!player")) {
                PlayerActor player = map.player(activator);
                if (player != null) speaker = player.id();
            } else {
                LogicEntity entity = actorEntity(actor);
                if (entity != null) at = entity.position();
            }
            double duration = event.end() > event.start() ? event.end() - event.start() : -1;
            long id = map.emitSound(event.script(), at, speaker, event.caption(), duration, -1);
            if (id > 0) voices.add(id);
            map.speaking(actor, duration > 0 ? duration : 0);
        }

        /** {@code FindNamedActor}: {@code !target1}..{@code !target8} name the scene's own target keys. */
        private LogicEntity actorEntity(String actor) {
            String name = actor;
            if (actor.startsWith("!target")) name = key("target" + actor.substring(7), "");
            return map.findFirst(name, this, activator, this);
        }

        private boolean busy() {
            LogicTable.Scene scene = map.scene(this);
            if (scene == null) return false;
            for (LogicTable.SceneEvent event : scene.events()) if (event.speaks() && map.isSpeaking(event.actor())) return true;
            return false;
        }

        private void stopVoices() {
            for (long id : voices) map.stopSound(id);
            voices.clear();
        }

        @Override void save(CompoundTag tag) {
            super.save(tag);
            tag.putBoolean("playing", playing); tag.putBoolean("paused", paused); tag.putBoolean("waiting", waiting);
            tag.putDouble("elapsed", elapsed); tag.putInt("next", next);
            if (activator instanceof PlayerActor player) tag.putUUID("activator", player.id());
        }

        @Override void load(CompoundTag tag) {
            super.load(tag);
            playing = tag.getBoolean("playing"); paused = tag.getBoolean("paused"); waiting = tag.getBoolean("waiting");
            elapsed = tag.getDouble("elapsed"); next = tag.getInt("next");
            if (tag.hasUUID("activator")) activator = new PlayerActor(tag.getUUID("activator"));
        }

        @Override String state() {
            return waiting ? "waiting for its actors" : playing ? String.format(Locale.ROOT, "playing at %.1fs%s", elapsed, paused ? ", paused" : "") : "idle";
        }
    }
}
