package dev.theredja.src2mc.client.render;

/** Per-frame draw counters for the surface renderer's main pass, with a 120-frame average. */
final class DrawStats {
    private static final int WINDOW_FRAMES = 120;
    long draws, triangles, frustumRejected, stateSwitches, cpuNanos, shadowTriangles;
    private final Frame[] window = new Frame[WINDOW_FRAMES];
    private int windowAt, frames;

    record Frame(long draws, long triangles, long frustumRejected, long stateSwitches, long pvsRejectedRegions,
                 double cpuMs, long shadowTriangles, double averageCpuMs, double averageDraws) {
        static final Frame EMPTY = new Frame(0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    /** Closes the running frame into a snapshot and starts the next one. */
    Frame latch(long pvsRejectedRegions) {
        double cpuMs = cpuNanos / 1.0e6;
        window[windowAt] = new Frame(draws, triangles, frustumRejected, stateSwitches, pvsRejectedRegions, cpuMs, shadowTriangles, 0, 0);
        windowAt = (windowAt + 1) % WINDOW_FRAMES;
        if (frames < WINDOW_FRAMES) frames++;
        double sumMs = 0, sumDraws = 0;
        for (int i = 0; i < frames; i++) { sumMs += window[i].cpuMs(); sumDraws += window[i].draws(); }
        Frame result = new Frame(draws, triangles, frustumRejected, stateSwitches, pvsRejectedRegions, cpuMs, shadowTriangles,
            sumMs / frames, sumDraws / frames);
        draws = triangles = frustumRejected = stateSwitches = cpuNanos = shadowTriangles = 0;
        return result;
    }
}
