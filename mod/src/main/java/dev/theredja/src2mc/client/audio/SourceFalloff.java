package dev.theredja.src2mc.client.audio;

/**
 * How loud a Source sound is at a distance, from its sound level in decibels.
 *
 * <p>Source states a sound level as the loudness at a 36-unit reference distance against a
 * 60 dB reference, and loudness then falls as the inverse of distance -- 6 dB per doubling -- up
 * to full volume close by. Once that falls to 1% it fades linearly to silence over the same
 * distance again, so a sound ends instead of lingering at a whisper forever. Sound level 0
 * ({@code SNDLVL_NONE}) does not fall off at all.
 *
 * <p>The SDK's {@code soundflags.h} and {@code sound.cpp} state the reference values; the shape of
 * the curve past them is the engine's, which is not in the public SDK.
 */
final class SourceFalloff {
    static final double UNITS_PER_BLOCK = 32.0;
    private static final double REFERENCE_DB = 60.0;
    private static final double REFERENCE_DISTANCE = 36.0;
    private static final double MIN_GAIN = 0.01;

    private SourceFalloff() {}

    /** Gain 0..1 at {@code blocks} from the sound. */
    static double gain(double soundLevel, double blocks) {
        if (soundLevel <= 0) return 1.0;
        double distanceMultiplier = Math.pow(10.0, (REFERENCE_DB - soundLevel) / 20.0) / REFERENCE_DISTANCE;
        double relative = blocks * UNITS_PER_BLOCK * distanceMultiplier;
        double gain = relative > 0.1 ? 1.0 / relative : 1.0;
        if (gain >= MIN_GAIN) return Math.min(gain, 1.0);
        return Math.max(0.0, MIN_GAIN * (2.0 - relative * MIN_GAIN));
    }

    /** Distance in blocks past which {@link #gain} is 0. */
    static double silentBeyond(double soundLevel) {
        if (soundLevel <= 0) return Double.POSITIVE_INFINITY;
        double distanceMultiplier = Math.pow(10.0, (REFERENCE_DB - soundLevel) / 20.0) / REFERENCE_DISTANCE;
        return 2.0 / MIN_GAIN / distanceMultiplier / UNITS_PER_BLOCK;
    }
}
