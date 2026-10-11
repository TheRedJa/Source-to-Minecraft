package dev.theredja.src2mc.client.audio;

import dev.theredja.src2mc.bundle.AudioTable;
import dev.theredja.src2mc.bundle.BundleManifest;
import java.util.Locale;
import net.minecraft.sounds.SoundSource;

/**
 * Plays the Oggs of a screens table ({@code maps/<map>/screens/sounds/}, format section 26) through the same
 * bundle sound library as the map's own sounds: the menu's click sounds and its looped music.
 */
public final class MenuSounds {
    private MenuSounds() {}

    /** A sound that is playing; hand it to {@link #stop}. */
    public static final class Handle {
        private final SourceSound sound;
        private Handle(SourceSound sound) { this.sound = sound; }
    }

    /**
     * Plays {@code entry} of {@code bundle}, heard everywhere at {@code volume}. {@code name} must be unique to the
     * file's content within the generation (it names the decoded buffer); {@code loopStartSeconds} is -1 for a
     * sound that plays once. Null when bundle sounds are unavailable.
     */
    public static Handle play(BundleManifest bundle, String name, String entry, double loopStartSeconds, float volume, SoundSource category) {
        // The table does not record the Ogg's sample rate; loops of the menu start at 0, which needs none.
        long loopStart = loopStartSeconds < 0 ? -1 : Math.round(loopStartSeconds * 44100);
        String id = "screens/" + name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9/._-]", "_");
        AudioTable.Sound asset = new AudioTable.Sound(id, entry, 2, 44100, 0, loopStart, entry);
        SourceSound sound = new SourceSound(SourceAudio.library(), bundle, asset, category, null, 0, volume, 1.0F, loopStart >= 0);
        return Voices.play(sound) ? new Handle(sound) : null;
    }

    public static boolean active(Handle handle) { return handle != null && Voices.active(handle.sound); }

    /** The loudness of a playing sound, 0..1 before the category's volume; the engine picks it up on the next tick. */
    public static void setLevel(Handle handle, float level) { if (handle != null) handle.sound.setLevel(level); }

    public static void stop(Handle handle) { if (handle != null) Voices.stop(handle.sound); }
}
