package dev.theredja.src2mc.client.audio;

import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;

/** Starting and stopping bundle sounds on Minecraft's sound manager. */
final class Voices {
    private Voices() {}

    /** False when the sound could not be handed to the engine; it then plays nothing. */
    static boolean play(SourceSound sound) {
        if (!sound.prepare()) return false;
        Minecraft.getInstance().getSoundManager().play(sound);
        return true;
    }

    static boolean active(SourceSound sound) { return Minecraft.getInstance().getSoundManager().isActive(sound); }

    static void stop(SourceSound sound) {
        sound.stopPlaying();
        Minecraft.getInstance().getSoundManager().stop(sound);
    }

    /** Whether a sound in this category would be heard at all; the engine drops it otherwise. */
    static boolean audible(SoundSource category) {
        var options = Minecraft.getInstance().options;
        return options.getSoundSourceVolume(SoundSource.MASTER) > 0 && options.getSoundSourceVolume(category) > 0;
    }
}
