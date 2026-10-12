package dev.theredja.src2mc.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.bundle.BundleMap;
import dev.theredja.src2mc.bundle.BundleProp;
import dev.theredja.src2mc.bundle.LightTable;
import dev.theredja.src2mc.bundle.SunVisibilityTable;
import dev.theredja.src2mc.bundle.WorldLightTable.Light;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

/**
 * What lights a prop besides its baked light, for the baked shader (D32): an ambient cube and up
 * to four world lights per light set (eight in a moving prop's row while it fades between two
 * selections), one row of {@link #ROW_TEXELS} texels in a float texture (sampler 8). Texels 0 to 5
 * are the cube's sides (+X, -X, +Y, -Y, +Z, -Z), the light count in texel 0's alpha; then five per light:
 * position and type + 1 (0 for none); intensity and radius; direction and spot exponent;
 * constant, linear, quadratic attenuation and the spot cone's scale; the cone's bias.
 *
 * Props that stand still -- static, and logic props -- get a row each when they are built, found
 * by origin, and name it in their vertices' surface attribute (z); rows from {@link
 * #DYNAMIC_ROWS} up. Props that move are re-selected on a worker when they have moved a quarter
 * block; the render thread cross-fades the old set into the new one over {@link #FADE_NANOS} by
 * weighting each light's intensity in the row (a light in both sets keeps its slot, one leaving
 * fades out, one entering fades in) and writes the row, one of those below {@link #DYNAMIC_ROWS},
 * per draw; the shader reads which from a uniform. Positions are map-local; {@code MapOffset}
 * turns them camera-relative.
 */
public final class PropLights {
    private PropLights() {}

    /** Lights a row holds: four selected, and up to four more fading out. */
    static final int ROW_LIGHTS = 8;
    static final int ROW_TEXELS = 48;
    /** Rows 1 to 63 are for props in motion; row 0 is none. */
    static final int DYNAMIC_ROWS = 64;
    private static final int MAX_ROWS = 8192;
    private static final double MOVE_THRESHOLD_SQUARED = 0.25 * 0.25;
    /** A moving prop's cross-fade between two selections, and the least time between its selections. */
    private static final long FADE_NANOS = 200_000_000L, MIN_INTERVAL_NANOS = 100_000_000L;
    /** Selections handed to the worker per frame, over all moving props. */
    private static final int SUBMITS_PER_FRAME = 8;

    /** {@code /src2mc_look phong|rimlight|proplights off}: read per draw by the shader. */
    public static volatile boolean phongOn = true, rimOn = true, propLightsOn = true;
    /** {@code /src2mc_look lightatten vertex|pixel}: phong light attenuation per vertex (Source) or per pixel. */
    public static volatile boolean lightAttenVertex = true;
    /** {@code /src2mc_look phongdebug}: 0 off, 1 diffuse, 2 spec + rim, 3 local-light diffuse only. */
    public static volatile int phongDebug = 0;
    /** {@code /src2mc_look sun on|off}: the skylight counts in prop lighting; see {@link #setSunOn}. */
    private static volatile boolean sunOn = true;
    /** {@code /src2mc_look lightvis on|off}: lights behind walls are culled by the sun visibility file's lists; see {@link #setLightVisOn}. */
    private static volatile boolean lightVisOn = true;
    /** Bumped when the sun or lightvis switch flips, so moving props select again. */
    private static volatile int generation;

    private static final Map<BundleMap, MapLights> MAPS = new IdentityHashMap<>();
    private static final Map<LightTable, MapLights> TABLES = new IdentityHashMap<>();
    private static int placeholder;
    private static boolean overflowLogged;

    /** {@code visList} is the prop's list in the visibility table, -1 for none (a different list is a different light set). */
    private record Origin(double x, double y, double z, float sun, int visList) {}

    private static final class MapLights {
        final LightGrid grid;
        final LightTable light;
        final SunVisibilityTable sunTable;
        final Map<Origin, Integer> byOrigin = new HashMap<>();
        /** Static rows, row {@code DYNAMIC_ROWS + i}, and the origin each was selected at. */
        final List<float[]> rows = new ArrayList<>();
        final List<Origin> rowOrigins = new ArrayList<>();
        /** Who holds each of the moving props' rows; render thread only. */
        final Moving[] owners = new Moving[DYNAMIC_ROWS];
        long lights, overflow, culled;
        int texture, capacity, uploaded;

        MapLights(BundleMap map) {
            grid = new LightGrid(LightSelection.eligible(map.worldLights()), LightSelection.eligibleIndices(map.worldLights()));
            light = map.light();
            sunTable = map.sunVisibility();
        }
    }

    /**
     * A prop in motion, or one whose origin changes, and the light it is drawn with. The selection
     * runs on a worker ({@code work*} are the worker's while {@code stage} is WORKING, then the
     * render thread's); the render thread cross-fades the shown set ({@code cur*}) into it.
     */
    static final class Moving {
        private static final int IDLE = 0, WORKING = 1, READY = 2;
        // Render thread.
        private double x = Double.NaN, y, z;
        private long requestedAt;
        private int generation = -1;
        private boolean valid, dirty, fading;
        private int row;
        private long fadeStart;
        private final float[] cube = new float[18], data = new float[ROW_TEXELS * 4];
        private final Light[] curLights = new Light[ROW_LIGHTS];
        private final float[] curWeights = new float[ROW_LIGHTS];
        private int curCount;
        private final Light[] fromLights = new Light[ROW_LIGHTS];
        private final float[] fromWeights = new float[ROW_LIGHTS], fromCube = new float[18];
        private int fromCount;
        private final Light[] toLights = new Light[LightSelection.MAX_LOCAL];
        private final float[] toWeights = new float[LightSelection.MAX_LOCAL], toCube = new float[18];
        private int toCount;
        // The worker's result, and what it works on.
        private volatile int stage;
        private int workGeneration;
        private double workX, workY, workZ;
        private final float[] workCube = new float[18], workRanks = new float[LightSelection.MAX_LOCAL], workWeights = new float[LightSelection.MAX_LOCAL], sunScratch = new float[1];
        private final Light[] workLights = new Light[LightSelection.MAX_LOCAL];
        private final dev.theredja.src2mc.bundle.LightTable.SampleWeights workSamples = new dev.theredja.src2mc.bundle.LightTable.SampleWeights();
        private final LightVisibility workVis = LightVisibility.ofSamples();
        private int workCount;

        /** The prop's cube as shown now (blended while fading), valid after {@link PropLights#update}. */
        float[] cube() { return cube; }
        boolean valid() { return valid; }
    }

    /**
     * The map's light state; null when it has no ambient light, or is not in the active bundle
     * generation (a worker of an older one may still ask, and must not bring the map back).
     */
    private static MapLights state(BundleMap map) {
        if (map.light() == null) return null;
        synchronized (MAPS) {
            MapLights state = MAPS.get(map);
            if (state == null) {
                if (!inActiveGeneration(map)) return null;
                state = new MapLights(map);
                MAPS.put(map, state);
                TABLES.put(map.light(), state);
            }
            return state;
        }
    }

    private static boolean inActiveGeneration(BundleMap map) {
        for (var bundle : Src2mc.bundles().active().bundles()) {
            for (BundleMap candidate : bundle.maps()) if (candidate == map) return true;
        }
        return false;
    }

    /**
     * The row of the light set of a static prop of the map: at its lighting origin (the sun
     * visibility file's, else its translation), with the sun visibility there for the skylight.
     * See {@link #staticRow(BundleMap, double, double, double, float, int)}.
     */
    static int staticRow(BundleMap map, BundleProp prop) {
        double[] origin = lightingOrigin(map, prop);
        SunVisibilityTable table = map.sunVisibility();
        int list = table == null ? -1 : table.propList(BakedLighting.propIndex(map, prop));
        return staticRow(map, origin[0], origin[1], origin[2], sunOf(map, prop), list);
    }

    /** Where a static prop is lit from, map-local: the sun visibility file's origin for it, else its translation. */
    static double[] lightingOrigin(BundleMap map, BundleProp prop) {
        SunVisibilityTable sun = map.sunVisibility();
        if (sun != null) {
            float[] origin = sun.propOrigin(BakedLighting.propIndex(map, prop));
            if (origin != null) return new double[] {origin[0], origin[1], origin[2]};
        }
        return prop.translation();
    }

    /** The sun visibility (0 to 1) at a static prop's lighting origin; 0 when the map has no such data. */
    private static float sunOf(BundleMap map, BundleProp prop) {
        SunVisibilityTable sun = map.sunVisibility();
        return sun == null ? 0f : sun.prop(BakedLighting.propIndex(map, prop));
    }

    /**
     * The row of the light set at a map-local point, shared by props with the same origin; 0 when
     * the map has no ambient light or the texture is full. Any thread; the selection is made
     * outside the lock, so the render thread never waits on it (a rare duplicate is dropped).
     */
    static int staticRow(BundleMap map, double x, double y, double z, float sun, int visList) {
        MapLights state = state(map);
        if (state == null) return 0;
        Origin origin = new Origin(x, y, z, sun, visList);
        synchronized (state) {
            Integer known = state.byOrigin.get(origin);
            if (known != null) return known;
            if (full(state)) return overflow(state);
        }
        float[] data = new float[ROW_TEXELS * 4];
        int[] culled = new int[1];
        int count = selectStatic(state, map, origin, data, culled);
        synchronized (state) {
            Integer known = state.byOrigin.get(origin);
            if (known != null) return known;
            if (full(state)) return overflow(state);
            state.rows.add(data);
            state.rowOrigins.add(origin);
            state.lights += count;
            state.culled += culled[0];
            int row = DYNAMIC_ROWS + state.rows.size() - 1;
            state.byOrigin.put(origin, row);
            return row;
        }
    }

    /** Selects a static origin's lights into {@code data} (a row); returns how many are local. */
    private static int selectStatic(MapLights state, BundleMap map, Origin o, float[] data, int[] culled) {
        float[] cube = new float[18];
        boolean leaf = map.light().ambientAt(o.x(), o.y(), o.z(), cube);
        // Without ambient samples the cube-lit path is white; the lights add to that.
        if (!leaf) java.util.Arrays.fill(cube, 1f);
        LightVisibility vis = lightVisOn ? LightVisibility.ofList(state.sunTable, o.visList()) : null;
        LightSelection.Result lights = LightSelection.select(state.grid, leaf, o.x(), o.y(), o.z(), sunOn ? o.sun() : 0f, vis, cube);
        if (vis != null) culled[0] = vis.culled;
        fill(data, cube, lights.lights(), lights.weights(), lights.count());
        return lights.count();
    }

    /**
     * {@code /src2mc_look sun on|off}: whether the skylight lights props at all. The static light
     * sets are selected again in place (props built with a cube taken from one keep it until they
     * rebuild), and moving props select again.
     */
    public static void setSunOn(boolean on) {
        sunOn = on;
        reselect();
    }

    /**
     * {@code /src2mc_look lightvis on|off}: whether lights the visibility lists hide from a prop
     * (behind a wall) are left out of its light set and cube. Refreshes like {@link #setSunOn}.
     */
    public static void setLightVisOn(boolean on) {
        lightVisOn = on;
        reselect();
    }

    private static void reselect() {
        generation++;
        List<MapLights> states;
        synchronized (MAPS) { states = new ArrayList<>(MAPS.values()); }
        for (MapLights state : states) {
            BundleMap map = null;
            synchronized (MAPS) {
                for (Map.Entry<BundleMap, MapLights> entry : MAPS.entrySet()) if (entry.getValue() == state) map = entry.getKey();
            }
            if (map == null) continue;
            synchronized (state) {
                state.lights = 0;
                state.culled = 0;
                int[] culled = new int[1];
                for (int i = 0; i < state.rows.size(); i++) {
                    culled[0] = 0;
                    state.lights += selectStatic(state, map, state.rowOrigins.get(i), state.rows.get(i), culled);
                    state.culled += culled[0];
                }
                state.uploaded = 0;
            }
        }
    }

    private static boolean full(MapLights state) { return DYNAMIC_ROWS + state.rows.size() >= MAX_ROWS; }

    /** Counts a static prop left without a set (its phong off); caller holds the state. */
    private static int overflow(MapLights state) {
        state.overflow++;
        if (!overflowLogged) {
            overflowLogged = true;
            Src2mc.LOGGER.warn("src2mc: more than {} prop light sets; later props keep their baked light", MAX_ROWS - DYNAMIC_ROWS);
        }
        return 0;
    }

    /** The cube of a static set, folded as {@link #staticRow} made it, or null. */
    static float[] staticCube(BundleMap map, int row) {
        MapLights state = state(map);
        if (state == null) return null;
        synchronized (state) {
            int at = row - DYNAMIC_ROWS;
            if (at < 0 || at >= state.rows.size()) return null;
            float[] cube = new float[18];
            for (int side = 0; side < 6; side++) System.arraycopy(state.rows.get(at), side * 4, cube, side * 3, 3);
            return cube;
        }
    }

    private static final java.util.concurrent.ExecutorService WORKER = java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "src2mc-prop-lights");
        thread.setDaemon(true);
        return thread;
    });
    private static final java.util.concurrent.atomic.AtomicLong workerNanos = new java.util.concurrent.atomic.AtomicLong();
    private static long submitFrame = Long.MIN_VALUE;
    private static int submitsThisFrame;
    /** Moving props' selections made, and the last full second's time spent on them, for status. */
    private static long dynamicSelections, windowStart, windowRenderNanos, windowSelections;
    private static volatile double workerMsPerSecond, renderMsPerSecond;
    private static volatile long selectionsPerSecond;

    /**
     * Keeps a moving prop's light up to date for a prop now at a map-local point; render thread.
     * A selection is handed to a worker when the prop has moved a quarter block (and 0.1 s have
     * passed since the last), or has none; the result is cross-faded in on a later frame. False
     * while the map has no ambient light or the prop has no selection yet (it is lit by its
     * cube alone for that frame or two).
     */
    static boolean update(Moving moving, BundleMap map, double x, double y, double z, long frame) {
        MapLights state = state(map);
        if (state == null) return false;
        long begin = System.nanoTime();
        if (moving.stage == Moving.READY) {
            takeResult(moving, begin);
            moving.stage = Moving.IDLE;
        }
        if (moving.fading) advanceFade(moving, begin);
        if (moving.stage == Moving.IDLE) {
            double dx = x - moving.x, dy = y - moving.y, dz = z - moving.z;
            boolean stale = moving.generation != generation || Double.isNaN(moving.x)
                || (dx * dx + dy * dy + dz * dz >= MOVE_THRESHOLD_SQUARED && begin - moving.requestedAt >= MIN_INTERVAL_NANOS);
            if (stale) {
                if (submitFrame != frame) { submitFrame = frame; submitsThisFrame = 0; }
                if (submitsThisFrame < SUBMITS_PER_FRAME) {
                    submitsThisFrame++;
                    submit(moving, state, map, x, y, z, begin);
                }
            }
        }
        windowRenderNanos += System.nanoTime() - begin;
        if (begin - windowStart >= 1_000_000_000L) {
            double seconds = (begin - windowStart) / 1.0e9;
            workerMsPerSecond = workerNanos.getAndSet(0) / 1.0e6 / seconds;
            renderMsPerSecond = windowRenderNanos / 1.0e6 / seconds;
            selectionsPerSecond = Math.round(windowSelections / seconds);
            windowStart = begin;
            windowRenderNanos = 0;
            windowSelections = 0;
        }
        return moving.valid;
    }

    private static void submit(Moving moving, MapLights state, BundleMap map, double x, double y, double z, long now) {
        moving.x = x; moving.y = y; moving.z = z;
        moving.requestedAt = now;
        moving.generation = generation;
        moving.workGeneration = generation;
        moving.workX = x; moving.workY = y; moving.workZ = z;
        moving.stage = Moving.WORKING;
        dynamicSelections++;
        windowSelections++;
        WORKER.execute(() -> {
            long begin = System.nanoTime();
            try {
                float[] cube = moving.workCube;
                float[] sun = moving.sunScratch;
                boolean leaf = map.light().ambientAt(moving.workX, moving.workY, moving.workZ, cube, state.sunTable, sun, moving.workSamples);
                LightVisibility vis = null;
                if (lightVisOn && leaf && moving.workVis.setSamples(state.sunTable, moving.workSamples)) {
                    vis = moving.workVis;
                    vis.culled = 0;
                }
                // As staticRow: white when the map has no ambient samples.
                if (!leaf) java.util.Arrays.fill(cube, 1f);
                moving.workCount = LightSelection.select(state.grid, leaf, moving.workX, moving.workY, moving.workZ,
                    sunOn && leaf ? sun[0] : 0f, vis, cube, moving.workLights, moving.workRanks, moving.workWeights);
            } catch (RuntimeException e) {
                moving.workCount = 0;
                java.util.Arrays.fill(moving.workCube, 1f);
                Src2mc.LOGGER.warn("src2mc: prop light selection failed", e);
            } finally {
                workerNanos.addAndGet(System.nanoTime() - begin);
                moving.stage = Moving.READY;
            }
        });
    }

    /** Makes the worker's selection the target: shown at once the first time, cross-faded after. */
    private static void takeResult(Moving m, long now) {
        if (!m.valid) {
            System.arraycopy(m.workCube, 0, m.cube, 0, 18);
            for (int i = 0; i < ROW_LIGHTS; i++) {
                boolean set = i < m.workCount;
                m.curLights[i] = set ? m.workLights[i] : null;
                m.curWeights[i] = set ? m.workWeights[i] : 0f;
            }
            m.curCount = m.workCount;
            m.valid = true;
            m.fading = false;
            fill(m.data, m.cube, m.curLights, m.curWeights, m.curCount);
            m.dirty = true;
            return;
        }
        // From what is shown now (itself possibly mid-fade), to the new selection.
        System.arraycopy(m.cube, 0, m.fromCube, 0, 18);
        System.arraycopy(m.curLights, 0, m.fromLights, 0, ROW_LIGHTS);
        System.arraycopy(m.curWeights, 0, m.fromWeights, 0, ROW_LIGHTS);
        m.fromCount = m.curCount;
        System.arraycopy(m.workCube, 0, m.toCube, 0, 18);
        System.arraycopy(m.workLights, 0, m.toLights, 0, LightSelection.MAX_LOCAL);
        System.arraycopy(m.workWeights, 0, m.toWeights, 0, LightSelection.MAX_LOCAL);
        m.toCount = m.workCount;
        m.fading = true;
        m.fadeStart = now;
        advanceFade(m, now);
    }

    private static final Light[] unionLights = new Light[2 * ROW_LIGHTS];
    private static final float[] unionWeights = new float[2 * ROW_LIGHTS];

    /**
     * Sets the shown set to the blend of the one it faded from and the worker's result at time
     * {@code now}: smoothstep weights, a light in both sets between its two weights.
     */
    private static void advanceFade(Moving m, long now) {
        float t = Math.min(1f, (now - m.fadeStart) / (float) FADE_NANOS);
        float k = t * t * (3f - 2f * t);
        int n = 0;
        for (int i = 0; i < m.fromCount; i++) {
            Light light = m.fromLights[i];
            float to = 0f;
            for (int j = 0; j < m.toCount; j++) if (m.toLights[j] == light) { to = m.toWeights[j]; break; }
            unionLights[n] = light; unionWeights[n++] = m.fromWeights[i] + (to - m.fromWeights[i]) * k;
        }
        for (int j = 0; j < m.toCount; j++) {
            Light light = m.toLights[j];
            boolean known = false;
            for (int i = 0; i < m.fromCount; i++) if (m.fromLights[i] == light) { known = true; break; }
            if (!known) { unionLights[n] = light; unionWeights[n++] = m.toWeights[j] * k; }
        }
        // More than a row holds (a fade restarted mid-way): the faintest go, which are nearly gone.
        while (n > ROW_LIGHTS) {
            int weakest = 0;
            for (int i = 1; i < n; i++) if (unionWeights[i] < unionWeights[weakest]) weakest = i;
            unionLights[weakest] = unionLights[n - 1]; unionWeights[weakest] = unionWeights[n - 1]; n--;
        }
        int count = 0;
        for (int i = 0; i < n; i++) {
            if (unionWeights[i] <= 1.0e-3f) continue;
            m.curLights[count] = unionLights[i]; m.curWeights[count++] = unionWeights[i];
        }
        for (int i = count; i < ROW_LIGHTS; i++) { m.curLights[i] = null; m.curWeights[i] = 0f; }
        m.curCount = count;
        for (int c = 0; c < 18; c++) m.cube[c] = m.fromCube[c] + (m.toCube[c] - m.fromCube[c]) * k;
        if (t >= 1f) m.fading = false;
        fill(m.data, m.cube, m.curLights, m.curWeights, m.curCount);
        m.dirty = true;
    }

    private static void fill(float[] row, float[] cube, Light[] lights, float[] weights, int count) {
        java.util.Arrays.fill(row, 0f);
        for (int side = 0; side < 6; side++) {
            for (int c = 0; c < 3; c++) row[side * 4 + c] = cube[side * 3 + c];
        }
        row[3] = count;
        for (int i = 0; i < count; i++) {
            Light light = lights[i];
            float w = weights[i];
            int at = (6 + 5 * i) * 4;
            row[at] = light.origin()[0]; row[at + 1] = light.origin()[1]; row[at + 2] = light.origin()[2]; row[at + 3] = light.type() + 1;
            row[at + 4] = light.intensity()[0] * w; row[at + 5] = light.intensity()[1] * w; row[at + 6] = light.intensity()[2] * w; row[at + 7] = light.radius();
            row[at + 8] = light.direction()[0]; row[at + 9] = light.direction()[1]; row[at + 10] = light.direction()[2]; row[at + 11] = light.exponent();
            float scale = 1f / Math.max(light.stopdot() - light.stopdot2(), 1.0e-4f);
            row[at + 12] = light.constant(); row[at + 13] = light.linear(); row[at + 14] = light.quadratic(); row[at + 15] = scale;
            row[at + 16] = -light.stopdot2() * scale;
        }
    }

    /**
     * Resets the shader to no local lights (row 0, the placeholder set), what a draw that does not
     * {@link #apply} gets; render thread.
     */
    static void reset(net.minecraft.client.renderer.ShaderInstance shader) {
        RenderSystem.setShaderTexture(8, placeholder());
        shader.safeGetUniform("MapOffset").set(0f, 0f, 0f);
        shader.safeGetUniform("LightRow").set(0);
    }

    /**
     * Gives the draw its light: the map's set texture on sampler 8, the camera-relative position
     * of the map's origin, and for a prop in motion its row, written when it was newly given or
     * re-selected. Call after {@code BakedLighting.prepare}, before the draw; render thread.
     */
    static void apply(net.minecraft.client.renderer.ShaderInstance shader, LightTable table, float offsetX, float offsetY, float offsetZ,
                      Moving moving, long frame) {
        MapLights state;
        synchronized (MAPS) { state = table == null ? null : TABLES.get(table); }
        int row = 0;
        if (state == null) {
            RenderSystem.setShaderTexture(8, placeholder());
        } else {
            synchronized (state) { upload(state); }
            if (moving != null && moving.valid) {
                if (moving.row <= 0 || state.owners[moving.row] != moving) {
                    // Rows 1.. in turn; a prop that finds its row taken writes a new one.
                    moving.row = 1 + (int) (rowCounter++ % (DYNAMIC_ROWS - 1));
                    state.owners[moving.row] = moving;
                    moving.dirty = true;
                }
                row = moving.row;
                if (moving.dirty) {
                    moving.dirty = false;
                    writeRow(state, row, moving.data);
                }
            }
            RenderSystem.setShaderTexture(8, state.texture);
        }
        shader.safeGetUniform("MapOffset").set(offsetX, offsetY, offsetZ);
        shader.safeGetUniform("LightRow").set(row);
    }

    private static java.nio.FloatBuffer rowScratch;

    private static void writeRow(MapLights state, int row, float[] values) {
        if (rowScratch == null) rowScratch = MemoryUtil.memAllocFloat(ROW_TEXELS * 4);
        rowScratch.clear();
        rowScratch.put(values).flip();
        GlStateManager._bindTexture(state.texture);
        int unpack = UnpackState.begin(4);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, row, ROW_TEXELS, 1, GL11.GL_RGBA, GL11.GL_FLOAT, rowScratch);
        UnpackState.end(unpack);
        GlStateManager._bindTexture(0);
    }

    private static long rowCounter;

    /** Uploads rows added since the last draw, growing the texture when it is full; caller holds the state. */
    private static void upload(MapLights state) {
        int needed = DYNAMIC_ROWS + state.rows.size();
        if (state.texture != 0 && state.uploaded == state.rows.size() && needed <= state.capacity) return;
        boolean grow = state.texture == 0 || needed > state.capacity;
        if (grow) {
            if (state.texture != 0) GlStateManager._deleteTexture(state.texture);
            state.capacity = Math.max(DYNAMIC_ROWS + 256, Math.min(MAX_ROWS, Integer.highestOneBit(needed - 1) << 1));
            state.texture = GlStateManager._genTexture();
            GlStateManager._bindTexture(state.texture);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, ROW_TEXELS, state.capacity, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (java.nio.ByteBuffer) null);
            parameters();
            state.uploaded = 0;
            // The new texture holds no moving prop's row.
            java.util.Arrays.fill(state.owners, null);
        } else {
            GlStateManager._bindTexture(state.texture);
        }
        int count = state.rows.size() - state.uploaded;
        if (count > 0) {
            FloatBuffer data = MemoryUtil.memAllocFloat(count * ROW_TEXELS * 4);
            int unpack = UnpackState.begin(4);
            try {
                for (int i = state.uploaded; i < state.rows.size(); i++) data.put(state.rows.get(i));
                data.flip();
                GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, DYNAMIC_ROWS + state.uploaded, ROW_TEXELS, count, GL11.GL_RGBA, GL11.GL_FLOAT, data);
            } finally {
                MemoryUtil.memFree(data);
            }
            UnpackState.end(unpack);
            state.uploaded = state.rows.size();
        }
        GlStateManager._bindTexture(0);
    }

    private static int placeholder() {
        if (placeholder == 0) {
            placeholder = GlStateManager._genTexture();
            GlStateManager._bindTexture(placeholder);
            FloatBuffer zeros = MemoryUtil.memCallocFloat(ROW_TEXELS * 4);
            int unpack = UnpackState.begin(4);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, ROW_TEXELS, 1, 0, GL11.GL_RGBA, GL11.GL_FLOAT, zeros);
            UnpackState.end(unpack);
            MemoryUtil.memFree(zeros);
            parameters();
            GlStateManager._bindTexture(0);
        }
        return placeholder;
    }

    private static void parameters() {
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
    }

    /** Drops every map's rows and textures; a new bundle generation brings new maps. */
    static void clear() {
        synchronized (MAPS) {
            for (MapLights state : MAPS.values()) if (state.texture != 0) GlStateManager._deleteTexture(state.texture);
            MAPS.clear();
            TABLES.clear();
        }
        overflowLogged = false;
    }

    /** Light sets of static props, the local lights they hold, and selections made for moving ones. */
    public static String status() {
        long sets = 0, lights = 0, overflow = 0, culled = 0;
        boolean lists = false;
        synchronized (MAPS) {
            for (MapLights state : MAPS.values()) {
                sets += state.rows.size(); lights += state.lights; overflow += state.overflow; culled += state.culled;
                lists |= state.sunTable != null && state.sunTable.hasLists();
            }
        }
        return String.format(java.util.Locale.ROOT, "%d light set(s), %.2f local lights each, %d re-selection(s) for moving props (%d/s, %.2f ms/s on the worker, %.2f ms/s on the render thread), sun %s, lightvis %s%s%s",
            sets, sets == 0 ? 0.0 : (double) lights / sets, dynamicSelections, selectionsPerSecond, workerMsPerSecond, renderMsPerSecond, sunOn ? "on" : "off",
            lightVisOn ? "on" : "off", !lists ? " (no visibility lists in the loaded maps)" : String.format(java.util.Locale.ROOT, " (%.2f lights culled per set avg)", sets == 0 ? 0.0 : (double) culled / sets),
            overflow == 0 ? "" : ", " + overflow + " static prop(s) over the " + (MAX_ROWS - DYNAMIC_ROWS) + "-set limit (no phong)");
    }
}
