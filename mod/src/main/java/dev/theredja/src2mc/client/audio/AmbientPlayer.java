package dev.theredja.src2mc.client.audio;

import dev.theredja.src2mc.bundle.AudioTable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * The {@code ambient_generic} sounds that play from the start: the hum of a machine, a fan, a fire.
 *
 * <p>Source plays them for the whole map. Here one runs only while it is close enough to be
 * heard at all, so a world with many maps placed does not run every map's channels at once; one
 * that comes back into range starts again from the top. A sound whose file does not loop plays
 * once, the first time it is in range, as it would once at map start in Source.
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
    }

    String status() {
        long playing = states.values().stream().filter(state -> state.voice != null).count();
        return "ambient sounds " + playing + " playing of " + states.size() + " heard so far";
    }

    void tick(Vec3 listener, List<Ambient> ambients) {
        for (Ambient ambient : ambients) {
            AudioTable.Ambient entry = ambient.entry();
            // The loudest the sound could be; its level is drawn once it starts.
            double gain = SourceFalloff.gain(entry.soundLevel().low(), listener.distanceTo(ambient.position));
            State state = states.computeIfAbsent(ambient.key(), ignored -> new State());
            if (state.voice != null && (gain <= 0 || (!Voices.active(state.voice) && state.voice.isLooping()))) {
                Voices.stop(state.voice);
                state.voice = null;
            }
            if (state.voice == null && gain > 0 && Voices.audible(SoundSource.AMBIENT)) start(ambient, entry, state);
        }
    }

    private void start(Ambient ambient, AudioTable.Ambient entry, State state) {
        AudioTable.Sound sound = ambient.map.sound(entry.sound(random.nextInt(entry.soundCount())));
        if (!sound.loops() && state.played) return;
        state.played = true;
        SourceSound voice = new SourceSound(library, ambient.map.bundle(), sound, SoundSource.AMBIENT, ambient.position,
            (int) entry.soundLevel().sample(random), (float) entry.volume().sample(random), (int) entry.pitch().sample(random) / 100.0F, true);
        if (Voices.play(voice)) state.voice = voice;
    }

    void stop() {
        for (State state : states.values()) if (state.voice != null) Voices.stop(state.voice);
        states.clear();
    }
}
