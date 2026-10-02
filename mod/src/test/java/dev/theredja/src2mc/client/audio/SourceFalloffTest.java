package dev.theredja.src2mc.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class SourceFalloffTest {
    @Test void soundLevelZeroIsHeardEverywhereAtFullVolume() {
        assertEquals(1.0, SourceFalloff.gain(0, 10_000), 0);
        assertEquals(Double.POSITIVE_INFINITY, SourceFalloff.silentBeyond(0));
    }

    @Test void sixtyDecibelsIsFullVolumeAtThirtySixUnitsAndHalvesPerDoubling() {
        double reference = 36.0 / SourceFalloff.UNITS_PER_BLOCK;
        assertEquals(1.0, SourceFalloff.gain(60, reference), 1e-12);
        assertEquals(1.0, SourceFalloff.gain(60, reference / 4), 1e-12);
        assertEquals(0.5, SourceFalloff.gain(60, reference * 2), 1e-12);
        // 20 dB louder reaches ten times as far.
        assertEquals(0.5, SourceFalloff.gain(80, reference * 20), 1e-12);
    }

    @Test void belowOnePercentItFadesToSilenceOverTheSameDistanceAgain() {
        double onePercent = 100 * 36.0 / SourceFalloff.UNITS_PER_BLOCK;
        assertEquals(0.01, SourceFalloff.gain(60, onePercent), 1e-12);
        assertEquals(0.005, SourceFalloff.gain(60, onePercent * 1.5), 1e-12);
        assertEquals(0.0, SourceFalloff.gain(60, SourceFalloff.silentBeyond(60)), 1e-12);
        assertEquals(0.0, SourceFalloff.gain(60, onePercent * 3), 0);
    }
}
