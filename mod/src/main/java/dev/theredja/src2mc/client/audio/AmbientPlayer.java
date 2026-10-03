package dev.theredja.src2mc.client.audio;

import dev.theredja.src2mc.bundle.AudioTable;
import dev.theredja.src2mc.client.logic.ClientLogic;
import dev.theredja.src2mc.logic.LogicNetwork;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * The map's {@code ambient_generic} sounds: the hum of a machine, a fan, a fire, an alarm.
 *
 * <p>Without the map's logic running, those Source starts with the map play, and nothing else.
 * With it running, each plays as the server's copy of the entity says: on or off, at the level
 * and pitch its inputs set, starting over whenever it is started again, and a one-shot plays
 * once each time it is triggered.
 *
 * <p>Source plays them for the whole map. Here one runs only while it is close enough to be
 * heard at all, so a world with many maps placed does not run every map's channels at once; one
 * that comes back into range starts again from the top. A sound whose file does not loop plays
 * once per start, the first time it is in range.
 */
final class AmbientPlayer {
    private final SoundLibrary library;
    private final RandomGenerator random = RandomGenerator.of("L64X128MixRandom");
    private final Map<Key, State> states = new HashMap<>();

    AmbientPlayer(SoundLibrary library) { this.library = library; }

    /** An ambient sound of one placed map. */
    record Ambient(MapSound map, int index, Vec3 position) {
        AudioTable.Ambient entry() { return map.audio().ambients().get(index); }
        Key key() { return new Key(map.placement(), index); }
    }

    private record Key(dev.theredja.src2mc.world.MapPlacement placement, int index) {}

    private static final class State {
        SourceSound voice;
        boolean played;
        int serial;
        float level, pitch;
    }

    String status() {
        long playing = states.values().stream().filter(state -> state.voice != null).count();
        return "ambient sounds " + playing + " playing of " + states.size() + " heard so far";
    }

    void tick(ResourceLocation dimension, Vec3 listener, List<Ambient> ambients) {
        for (Ambient ambient : ambients) {
            AudioTable.Ambient entry = ambient.entry();
            long anchor = ambient.map.placement().anchorWorld().asLong();
            boolean running = ClientLogic.running(dimension, anchor);
            LogicNetwork.SoundState logic = running ? ClientLogic.state(dimension, anchor, entry.entity()) : null;
            boolean on = running ? logic != null && logic.on() : entry.startsWithMap();
            int serial = running ? (logic == null ? 0 : logic.serial()) : (on ? 1 : 0);
            // The loudest the sound could be; its level is drawn once it starts.
            double gain = SourceFalloff.gain(entry.soundLevel().low(), listener.distanceTo(ambient.position));
            State state = states.computeIfAbsent(ambient.key(), ignored -> new State());
            if (serial != state.serial) {
                // Started again: a looping one starts over, a one-shot plays once more.
                state.serial = serial;
                state.played = false;
                if (state.voice != null && entry.looping()) { Voices.stop(state.voice); state.voice = null; }
                if (!entry.looping() && serial > 0 && gain > 0 && Voices.audible(SoundSource.AMBIENT)) start(ambient, entry, state, logic, false);
            }
            if (!entry.looping()) { adjust(state, logic); continue; }
            boolean wanted = on && gain > 0;
            if (state.voice != null && (!wanted || (!Voices.active(state.voice) && state.voice.isLooping()))) {
                Voices.stop(state.voice);
                state.voice = null;
            }
            if (state.voice == null && wanted && Voices.audible(SoundSource.AMBIENT)) start(ambient, entry, state, logic, true);
            adjust(state, logic);
        }
    }

    private void start(Ambient ambient, AudioTable.Ambient entry, State state, LogicNetwork.SoundState logic, boolean loop) {
        AudioTable.Sound sound = ambient.map.sound(entry.sound(random.nextInt(entry.soundCount())));
        if (!sound.loops() && state.played) return;
        state.played = true;
        state.level = (float) entry.volume().sample(random);
        state.pitch = (int) entry.pitch().sample(random) / 100.0F;
        SourceSound voice = new SourceSound(library, ambient.map.bundle(), sound, SoundSource.AMBIENT, ambient.position,
            (int) entry.soundLevel().sample(random), level(state, logic), pitch(state, logic), loop);
        if (Voices.play(voice)) state.voice = voice;
    }

    /** A Volume or Pitch input replaces the sound's own level or pitch, as {@code SND_CHANGE_VOL} does. */
    private static void adjust(State state, LogicNetwork.SoundState logic) {
        if (state.voice == null || logic == null) return;
        state.voice.setLevel(level(state, logic));
        state.voice.setPitch(pitch(state, logic));
    }

    private static float level(State state, LogicNetwork.SoundState logic) { return logic != null && logic.volume() >= 0 ? logic.volume() : state.level; }

    private static float pitch(State state, LogicNetwork.SoundState logic) { return logic != null && logic.pitch() >= 0 ? Math.max(0.01F, logic.pitch()) : state.pitch; }

    void stop() {
        for (State state : states.values()) if (state.voice != null) Voices.stop(state.voice);
        states.clear();
    }
}
