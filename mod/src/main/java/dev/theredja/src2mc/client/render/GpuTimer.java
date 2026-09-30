package dev.theredja.src2mc.client.render;

import java.util.EnumMap;
import java.util.Map;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL33C;

/**
 * GPU time spent in each of the mod's own draw phases, from GL_TIME_ELAPSED queries. CPU
 * timers only see how long submitting took; whether a steady 20 ms frame is spent on the GPU
 * drawing our geometry or somewhere else is what decides which optimisation is worth doing.
 *
 * <p>Results are read a few frames late from a small ring per phase and never waited for.
 * TIME_ELAPSED queries cannot overlap, so a phase is skipped while another is open.
 */
final class GpuTimer {
    enum Phase { SURFACES_OPAQUE, PROPS_OPAQUE, SURFACES_TRANSLUCENT, PROPS_TRANSLUCENT, SURFACES_SHADOW, PROPS_SHADOW }

    private static final int RING = 6;
    private static final int WINDOW_FRAMES = 120;
    private static final Map<Phase, Ring> RINGS = new EnumMap<>(Phase.class);
    private static boolean checked, supported;
    private static Phase open;

    private GpuTimer() {}

    static void begin(Phase phase) {
        if (!supported()) return;
        if (open != null) return;
        // Someone else's elapsed-time query is running; beginning ours would fail, and ending it
        // later would end theirs.
        if (GL15C.glGetQueryi(GL33C.GL_TIME_ELAPSED, GL15C.GL_CURRENT_QUERY) != 0) return;
        Ring ring = RINGS.computeIfAbsent(phase, ignored -> new Ring());
        ring.poll();
        int slot = ring.next;
        if (ring.pending[slot]) return;
        if (ring.queries[slot] == 0) ring.queries[slot] = GL15C.glGenQueries();
        GL15C.glBeginQuery(GL33C.GL_TIME_ELAPSED, ring.queries[slot]);
        open = phase;
    }

    static void end(Phase phase) {
        if (open != phase) return;
        GL15C.glEndQuery(GL33C.GL_TIME_ELAPSED);
        Ring ring = RINGS.get(phase);
        ring.pending[ring.next] = true;
        ring.next = (ring.next + 1) % RING;
        open = null;
    }

    /** Called once per main frame: closes the frame's sums into the averaging window. */
    static void endFrame() {
        for (Ring ring : RINGS.values()) ring.endFrame();
    }

    /** Average GPU milliseconds per frame over the window, or -1 when unmeasured. */
    static double averageMs(Phase phase) {
        Ring ring = RINGS.get(phase);
        return ring == null || ring.frames == 0 ? -1 : ring.windowNanos / 1.0e6 / ring.frames;
    }

    static boolean supported() {
        if (!checked) {
            checked = true;
            supported = GL.getCapabilities().OpenGL33 || GL.getCapabilities().GL_ARB_timer_query;
        }
        return supported;
    }

    static void close() {
        for (Ring ring : RINGS.values()) {
            for (int query : ring.queries) if (query != 0) GL15C.glDeleteQueries(query);
        }
        RINGS.clear();
        open = null;
    }

    private static final class Ring {
        final int[] queries = new int[RING];
        final boolean[] pending = new boolean[RING];
        int next;
        long frameNanos;
        final long[] window = new long[WINDOW_FRAMES];
        int windowAt, frames;
        long windowNanos;

        void poll() {
            for (int i = 0; i < RING; i++) {
                if (!pending[i] || GL15C.glGetQueryObjecti(queries[i], GL15C.GL_QUERY_RESULT_AVAILABLE) == 0) continue;
                frameNanos += GL33C.glGetQueryObjecti64(queries[i], GL15C.GL_QUERY_RESULT);
                pending[i] = false;
            }
        }

        void endFrame() {
            poll();
            windowNanos += frameNanos - window[windowAt];
            window[windowAt] = frameNanos;
            windowAt = (windowAt + 1) % WINDOW_FRAMES;
            if (frames < WINDOW_FRAMES) frames++;
            frameNanos = 0;
        }
    }
}
