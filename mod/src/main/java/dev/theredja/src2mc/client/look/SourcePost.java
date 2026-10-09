package dev.theredja.src2mc.client.look;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.bundle.LookTable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

/**
 * Source's view post-processing on the frame, after the world and the hand are drawn and before
 * the HUD (D30), as {@code DoEnginePostProcessing} does it for integer HDR, the PC's mode: the
 * frame's luminance histogram measured by occlusion queries, one bucket a frame, and the tone
 * map scale chasing its target ({@code CTonemapSystem}, which the 2013 material system does
 * alike); bloom from a quarter-size, shaped and blurred copy; colour correction and the
 * vignette. The scale it settles on lights the next frame, as Source's does. Raw GL throughout,
 * every binding it changes put back, so Minecraft's state caches stay right.
 */
public final class SourcePost {
    private SourcePost() {}

    // ---- Settings Source reads from console variables, at their defaults.

    /** mat_tonemap_percent_target, mat_tonemap_percent_bright_pixels, mat_tonemap_min_avglum. */
    private static final float PERCENT_TARGET = 60, PERCENT_BRIGHT = 2, MIN_AVG_LUM = 3;
    /** mat_accelerate_adjust_exposure_down. */
    private static final float ACCELERATE_DOWN = 3;
    /** mat_exposure_center_region_x/y: the share of the screen measured, centred. */
    private static final float REGION_X = 0.9f, REGION_Y = 0.85f;
    /** mat_bloomamount_rate: how far the bloom amount moves to its target each frame. */
    private static final float BLOOM_RATE = 0.05f;
    /** r_bloomtintr/g/b, r_bloomtintexponent. */
    private static final float[] TINT = {0.3f, 0.59f, 0.11f, 2.2f};
    /** N_LUMINANCE_RANGES_NEW: sixteen ranges and the pixel-count bucket. */
    private static final int BUCKETS = 17;

    // ---- Toggles and counters for /src2mc_look.

    static boolean enabled = true, bloomOn = true, exposureOn = true, correctionOn = true, vignetteOn = true;
    /** mat_force_tonemap_scale: above 0 the scale is held there. */
    static float forcedScale = 0;
    /** The histogram measures linear light (sRGB decode) instead of the stored gamma values. */
    static boolean measureLinear = false;
    static long frames, queries;
    static float lastTarget = 1;

    // ---- Tone map state.

    private static float current = 1, target = 1;
    private static final float[] history = new float[10];
    private static int historyCount;
    private static float bloomAmount = 1;
    private static long lastFrame = -1;
    /** The map the scale belongs to; a new one starts at 1, as Source resets on level load. */
    private static Object scaleMap;

    /** The scale the map is drawn at this frame. */
    public static float scale() { return current; }

    /** One line on the tone map and bloom, for {@code /src2mc_look status}. */
    static String status() {
        StringBuilder histogram = new StringBuilder();
        int total = 0;
        for (int i = 0; i < BUCKETS - 1; i++) if (buckets[i].valid()) total += buckets[i].pixels;
        for (int i = 0; i < BUCKETS - 1; i++) {
            histogram.append(i == 0 ? "" : " ").append(buckets[i].valid() && total > 0 ? Math.round(100f * buckets[i].pixels / total) : -1);
        }
        return String.format(java.util.Locale.ROOT, "scale %.3f (target %.3f%s), bloom amount %.3f, %d frame(s), %d histogram quer(ies), %d lookup texture(s)%s"
                + "\nhistogram %%, dark to bright: %s; 2%% brightest at %.4f, half at %.4f (%s)",
            current, lastTarget, forcedScale > 0 ? ", held at " + forcedScale : "", bloomAmount, frames, queries, LOOKUP_TEXTURES.size(),
            failed ? "; the post shaders failed to load, see the log" : "", histogram,
            locationOfPercentBright(PERCENT_BRIGHT, -1), locationOfPercentBright(50, -1), measureLinear ? "linear" : "gamma");
    }

    /** Holds the scale at {@code value}, or with 0 lets the auto exposure move it again. */
    public static void force(float value) { forcedScale = Math.max(0, value); }

    private static final class Bucket {
        float min, max;
        int query = -1, frameQueued, pixels;
        /** 0 initial, 1 first query in flight, 2 in flight, 3 done. */
        int state;
        boolean valid() { return state == 2 || state == 3; }
    }

    private static final Bucket[] buckets = new Bucket[BUCKETS];
    private static int queryFrame;

    static {
        for (int i = 0; i < BUCKETS; i++) {
            Bucket b = buckets[i] = new Bucket();
            if (i != BUCKETS - 1) {
                // Even ranges, more of them low: raised to 1.5.
                b.min = (float) Math.pow(i / (float) (BUCKETS - 1), 1.5);
                b.max = (float) Math.pow((i + 1) / (float) (BUCKETS - 1), 1.5);
            } else {
                b.min = 0;
                b.max = 100000;
            }
        }
    }

    // ---- GL resources.

    private static int vao = -1, frameTexture = -1, frameFbo = -1, small0 = -1, small0Fbo = -1, small1 = -1, small1Fbo = -1;
    private static int width, height;
    /** The main target's framebuffer this frame; the histogram queries draw into it with colour writes off. */
    private static int mainFbo;
    private static int lumProgram = -1, downProgram = -1, blurProgram = -1, combineProgram = -1;
    private static boolean failed;
    private static final Map<byte[], Integer> LOOKUP_TEXTURES = new IdentityHashMap<>();
    private static final Map<LookTable.Vignette, Integer> VIGNETTES = new IdentityHashMap<>();

    /** Runs after the world and hand are drawn; the main target is the frame. */
    public static void afterLevel() {
        RenderSystem.assertOnRenderThread();
        long nowNanos = System.nanoTime();
        float frameTime = lastFrame < 0 ? 0 : Math.max(0, (nowNanos - lastFrame) / 1e9f);
        lastFrame = nowNanos;
        LookClient.Frame frame = LookClient.frame();
        Object map = frame == null ? null : frame.map();
        if (map != scaleMap) {
            scaleMap = map;
            reset(1);
        }
        if (frame == null || !enabled || failed || dev.theredja.src2mc.client.render.IrisCompat.shaderPackInUse()) {
            if (frame == null || !enabled) current = 1;
            return;
        }
        boolean hdr = frame.look() != null && frame.look().hdr();
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        if (main.width <= 0 || main.height <= 0) return;
        Saved saved = Saved.capture();
        try {
            if (!ensure(main.width, main.height)) return;
            mainFbo = main.frameBufferId;
            frames++;
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDepthMask(false);
            GL30.glBindVertexArray(vao);
            // UpdateScreenEffectTexture: a copy of the frame to read from.
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, frameFbo);
            GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);

            if (hdr && exposureOn) tonemap(frame, frameTime);
            else if (!hdr) reset(1);
            if (forcedScale > 0) reset(forcedScale);

            // GetBloomAmount: only maps with HDR light bloom; the amount eases towards its scale every frame.
            float bloom = 0;
            if (hdr) {
                bloomAmount = frame.bloomScale() * BLOOM_RATE + (1 - BLOOM_RATE) * bloomAmount;
                bloom = bloomOn ? bloomAmount : 0;
            }
            LookTable look = frame.look();
            if (bloom > 0) bloom(look);

            // engine_post into the main target.
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, main.frameBufferId);
            GL11.glViewport(0, 0, width, height);
            GL20.glUseProgram(combineProgram);
            bind(0, GL11.GL_TEXTURE_2D, frameTexture);
            bind(1, GL11.GL_TEXTURE_2D, bloom > 0 ? small0 : frameTexture);
            uniform(combineProgram, "Frame", 0);
            uniform(combineProgram, "Bloom", 1);
            GL20.glUniform1f(GL20.glGetUniformLocation(combineProgram, "BloomAmount"), bloom);
            List<LookClient.Lookup> lookups = correctionOn ? frame.lookups() : List.of();
            float[] weights = new float[4];
            for (int i = 0; i < 4; i++) {
                int texture = i < lookups.size() ? lookup(lookups.get(i).cells()) : 0;
                if (i < lookups.size()) weights[i] = lookups.get(i).weight();
                bind(2 + i, GL12.GL_TEXTURE_3D, texture);
                uniform(combineProgram, "Lookup" + i, 2 + i);
            }
            uniform(combineProgram, "Lookups", lookups.size());
            GL20.glUniform1f(GL20.glGetUniformLocation(combineProgram, "DefaultWeight"), lookups.isEmpty() ? 1 : frame.defaultWeight());
            GL20.glUniform4f(GL20.glGetUniformLocation(combineProgram, "LookupWeights"), weights[0], weights[1], weights[2], weights[3]);
            LookTable.Vignette vignette = vignetteOn && look != null ? look.vignette() : null;
            bind(6, GL11.GL_TEXTURE_2D, vignette == null ? 0 : vignette(vignette));
            uniform(combineProgram, "Vignette", 6);
            uniform(combineProgram, "VignetteOn", vignette == null ? 0 : 1);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        } finally {
            saved.restore();
        }
    }

    // ---- Tone mapping.

    private static void reset(float value) {
        current = target = value;
        historyCount = 0;
    }

    private static void tonemap(LookClient.Frame frame, float frameTime) {
        updateHistogram();
        float targetScalar = targetScalar();
        lastTarget = targetScalar;
        float min = frame.exposureMin(), max = frame.exposureMax();
        float clamped = Math.max(0.001f, Math.max(min, Math.min(max, targetScalar)));
        setScale(clamped, min, max, frameTime, frame.rate());
    }

    /** {@code CTonemapSystem::IssueAndReceiveBucketQueries}: one query a frame, results read two frames on. */
    private static void updateHistogram() {
        queryFrame++;
        int issued = 0;
        for (Bucket b : buckets) {
            switch (b.state) {
                case 0 -> {
                    if (issued < 1) { issue(b); issued++; }
                }
                case 1, 2 -> {
                    if (queryFrame > b.frameQueued + 2 && GL15.glGetQueryObjecti(b.query, GL15.GL_QUERY_RESULT_AVAILABLE) != 0) {
                        b.pixels = GL15.glGetQueryObjecti(b.query, GL15.GL_QUERY_RESULT);
                        b.state = 3;
                    }
                }
                default -> {}
            }
        }
        while (issued < 1) {
            Bucket oldest = null;
            for (Bucket b : buckets) if (b.state == 3 && (oldest == null || b.frameQueued < oldest.frameQueued)) oldest = b;
            if (oldest == null) break;
            issue(oldest);
            issued++;
        }
    }

    private static void issue(Bucket b) {
        if (b.query < 0) b.query = GL15.glGenQueries();
        // Not the frame copy's own framebuffer: sampling a texture while it is attached to the
        // target drawn into is undefined in GL, even with colour writes off.
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, mainFbo);
        GL11.glViewport(0, 0, width, height);
        GL11.glColorMask(false, false, false, false);
        GL20.glUseProgram(lumProgram);
        bind(0, GL11.GL_TEXTURE_2D, frameTexture);
        uniform(lumProgram, "Frame", 0);
        uniform(lumProgram, "Linear", measureLinear ? 1 : 0);
        // A range ending at 1 counts everything brighter too.
        GL20.glUniform2f(GL20.glGetUniformLocation(lumProgram, "Range"), b.min, b.max == 1 ? 10000 : b.max);
        int skipX = (int) (width * 0.5f * (1 - REGION_X)), skipY = (int) (height * 0.5f * (1 - REGION_Y));
        GL20.glUniform4f(GL20.glGetUniformLocation(lumProgram, "Region"), skipX, skipY, width - 1 - skipX, height - 1 - skipY);
        GL15.glBeginQuery(GL15.GL_SAMPLES_PASSED, b.query);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        GL15.glEndQuery(GL15.GL_SAMPLES_PASSED);
        GL11.glColorMask(true, true, true, true);
        b.state = b.state == 0 ? 1 : 2;
        b.frameQueued = queryFrame;
        queries++;
    }

    /** {@code FindLocationOfPercentBrightPixels}; -1 until every bucket has been measured. */
    private static float locationOfPercentBright(float percentBright, float snapTarget) {
        int total = 0;
        for (int i = 0; i < BUCKETS - 1; i++) if (buckets[i].valid()) total += buckets[i].pixels;
        if (total == 0) return -1;
        float rangeTested = 0, pixelsTested = 0;
        for (int i = BUCKETS - 2; i >= 0; i--) {
            Bucket b = buckets[i];
            if (!b.valid()) return -1;
            float needed = percentBright / 100f - pixelsTested;
            float share = b.pixels / (float) total;
            float range = b.max - b.min;
            if (share >= needed) {
                if (snapTarget >= 0 && b.min <= snapTarget / 100f && b.max >= snapTarget / 100f) return snapTarget / 100f;
                float location = 1 - (rangeTested + range * (needed / share));
                return Math.max(b.min, Math.min(b.max, location));
            }
            pixelsTested += share;
            rangeTested += range;
        }
        return -1;
    }

    /** {@code ComputeTargetTonemapScalar}, the new algorithm (mat_tonemap_algorithm 1). */
    private static float targetScalar() {
        float location = locationOfPercentBright(PERCENT_BRIGHT, PERCENT_TARGET);
        if (location < 0) location = PERCENT_TARGET / 100f;
        location = Math.max(0.0001f, location);
        float scalar = (PERCENT_TARGET / 100f) / location;
        float average = locationOfPercentBright(50, -1);
        if (average > 0) scalar = Math.max(scalar, (MIN_AVG_LUM / 100f) / average);
        return Math.max(0.001f, scalar * current);
    }

    /** {@code CTonemapSystem::SetTonemapScale}: a weighted moving average as the target, chased at the map's rate. */
    private static void setScale(float value, float min, float max, float frameTime, float manualRate) {
        if (!Float.isFinite(value)) return;
        if (historyCount < history.length) {
            history[historyCount++] = value;
        } else {
            System.arraycopy(history, 1, history, 0, history.length - 1);
            history[history.length - 1] = value;
        }
        if (historyCount == history.length) {
            float sum = 0, weights = 0;
            int mid = history.length / 2;
            for (int i = 0; i < history.length; i++) {
                float weight = Math.abs(i - mid) * (1f / (history.length / 2));
                weights += weight;
                sum += weight * history[i];
            }
            target = Math.max(min, Math.min(max, sum / weights));
        } else {
            target = value;
        }
        // The new algorithm doubles the rate so it feels like the original.
        float rate = manualRate * 2;
        if (rate == 0) {
            current = target;
            return;
        }
        if (target < current) {
            float accelerated = ACCELERATE_DOWN * rate;
            rate = Math.min(accelerated, rate + (accelerated - rate) * ((current - target) / 1.5f));
        }
        float alpha = Math.min(rate * frameTime, (1f / (BUCKETS - 1)) * 0.25f);
        alpha = Math.max(0, Math.min(1, alpha));
        current = target * alpha + current * (1 - alpha);
        if (!Float.isFinite(current)) current = target;
    }

    // ---- Bloom.

    /** {@code Generate8BitBloomTexture}: downsample to a quarter, blur across, blur down. */
    private static void bloom(LookTable look) {
        boolean later = look != null && look.bloomType() >= 0;
        int w = width / 4, h = height / 4;
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, small0Fbo);
        GL11.glViewport(0, 0, w, h);
        GL20.glUseProgram(downProgram);
        bind(0, GL11.GL_TEXTURE_2D, frameTexture);
        uniform(downProgram, "Frame", 0);
        GL20.glUniform2f(GL20.glGetUniformLocation(downProgram, "Texel"), 1f / width, 1f / height);
        GL20.glUniform4f(GL20.glGetUniformLocation(downProgram, "Tint"), TINT[0], TINT[1], TINT[2], TINT[3]);
        uniform(downProgram, "PerTap", later ? 0 : 1);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);

        GL20.glUseProgram(blurProgram);
        uniform(blurProgram, "Source", 0);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, small1Fbo);
        bind(0, GL11.GL_TEXTURE_2D, small0);
        uniform(blurProgram, "Kernel", look == null ? 0 : look.kernelX());
        GL20.glUniform2f(GL20.glGetUniformLocation(blurProgram, "Step"), 1f / w, 0);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, small0Fbo);
        bind(0, GL11.GL_TEXTURE_2D, small1);
        uniform(blurProgram, "Kernel", look == null ? 0 : look.kernelY());
        // The 2013 BlurFilterY steps by the texture's width, not its height.
        GL20.glUniform2f(GL20.glGetUniformLocation(blurProgram, "Step"), 0, later ? 1f / h : 1f / w);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
    }

    // ---- Resources.

    private static boolean ensure(int w, int h) {
        if (vao < 0) {
            try {
                vao = GL30.glGenVertexArrays();
                lumProgram = program("lumcompare");
                downProgram = program("downsample");
                blurProgram = program("blur");
                combineProgram = program("combine");
            } catch (IOException | RuntimeException e) {
                failed = true;
                Src2mc.LOGGER.error("src2mc look: post-processing shaders failed; the view is drawn without them", e);
                return false;
            }
        }
        if (w == width && h == height && frameTexture >= 0) return true;
        width = w;
        height = h;
        frameTexture = texture(frameTexture, w, h);
        small0 = texture(small0, Math.max(1, w / 4), Math.max(1, h / 4));
        small1 = texture(small1, Math.max(1, w / 4), Math.max(1, h / 4));
        frameFbo = framebuffer(frameFbo, frameTexture);
        small0Fbo = framebuffer(small0Fbo, small0);
        small1Fbo = framebuffer(small1Fbo, small1);
        return true;
    }

    private static int texture(int existing, int w, int h) {
        if (existing >= 0) GL11.glDeleteTextures(existing);
        int id = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        return id;
    }

    private static int framebuffer(int existing, int texture) {
        if (existing >= 0) GL30.glDeleteFramebuffers(existing);
        int id = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, id);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);
        return id;
    }

    private static int lookup(byte[] cells) {
        Integer existing = LOOKUP_TEXTURES.get(cells);
        if (existing != null) return existing;
        int id = GL11.glGenTextures();
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, id);
        ByteBuffer data = MemoryUtil.memAlloc(cells.length);
        try {
            data.put(cells).flip();
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL11.GL_RGB8, LookTable.CELLS, LookTable.CELLS, LookTable.CELLS, 0,
                GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, data);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        } finally {
            MemoryUtil.memFree(data);
        }
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE);
        LOOKUP_TEXTURES.put(cells, id);
        return id;
    }

    private static int vignette(LookTable.Vignette vignette) {
        Integer existing = VIGNETTES.get(vignette);
        if (existing != null) return existing;
        int id = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        ByteBuffer data = MemoryUtil.memAlloc(vignette.red().length);
        try {
            data.put(vignette.red()).flip();
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R8, vignette.width(), vignette.height(), 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, data);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        } finally {
            MemoryUtil.memFree(data);
        }
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        VIGNETTES.put(vignette, id);
        return id;
    }

    /** Drops the textures made for a bundle generation's tables. */
    public static void clearTables() {
        RenderSystem.assertOnRenderThread();
        for (int id : LOOKUP_TEXTURES.values()) GL11.glDeleteTextures(id);
        for (int id : VIGNETTES.values()) GL11.glDeleteTextures(id);
        LOOKUP_TEXTURES.clear();
        VIGNETTES.clear();
    }

    private static void bind(int unit, int target, int texture) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        GL11.glBindTexture(target, texture);
    }

    private static void uniform(int program, String name, int value) {
        GL20.glUniform1i(GL20.glGetUniformLocation(program, name), value);
    }

    private static int program(String fragment) throws IOException {
        int vertex = shader(GL20.GL_VERTEX_SHADER, "fullscreen.vsh");
        int pixel = shader(GL20.GL_FRAGMENT_SHADER, fragment + ".fsh");
        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vertex);
        GL20.glAttachShader(program, pixel);
        GL20.glLinkProgram(program);
        GL20.glDeleteShader(vertex);
        GL20.glDeleteShader(pixel);
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) {
            throw new IllegalStateException("linking " + fragment + ": " + GL20.glGetProgramInfoLog(program));
        }
        return program;
    }

    private static int shader(int type, String name) throws IOException {
        String text;
        try (InputStream in = SourcePost.class.getResourceAsStream("/assets/src2mc/shaders/post/" + name)) {
            if (in == null) throw new IOException("missing shader " + name);
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, text);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
            throw new IllegalStateException("compiling " + name + ": " + GL20.glGetShaderInfoLog(shader));
        }
        return shader;
    }

    /** The GL state the passes change, to put back. */
    private record Saved(int program, int vertexArray, int readFbo, int drawFbo, int activeTexture, int[] textures2d, int[] textures3d,
                         int[] viewport, boolean depthTest, boolean blend, boolean cull, boolean scissor, boolean depthMask,
                         boolean[] colorMask) {
        private static final int UNITS = 7;

        static Saved capture() {
            int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            int[] t2 = new int[UNITS], t3 = new int[UNITS];
            for (int i = 0; i < UNITS; i++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
                t2[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                t3[i] = GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D);
            }
            GL13.glActiveTexture(active);
            int[] viewport = new int[4];
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            ByteBuffer mask = MemoryUtil.memAlloc(4);
            boolean[] colorMask = new boolean[4];
            try {
                GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, mask);
                for (int i = 0; i < 4; i++) colorMask[i] = mask.get(i) != 0;
            } finally {
                MemoryUtil.memFree(mask);
            }
            return new Saved(GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM), GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING),
                GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING), GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING), active, t2, t3,
                viewport, GL11.glIsEnabled(GL11.GL_DEPTH_TEST), GL11.glIsEnabled(GL11.GL_BLEND), GL11.glIsEnabled(GL11.GL_CULL_FACE),
                GL11.glIsEnabled(GL11.GL_SCISSOR_TEST), GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK), colorMask);
        }

        void restore() {
            GL20.glUseProgram(program);
            GL30.glBindVertexArray(vertexArray);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFbo);
            for (int i = 0; i < UNITS; i++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, textures2d[i]);
                GL11.glBindTexture(GL12.GL_TEXTURE_3D, textures3d[i]);
            }
            GL13.glActiveTexture(activeTexture);
            GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            set(GL11.GL_DEPTH_TEST, depthTest);
            set(GL11.GL_BLEND, blend);
            set(GL11.GL_CULL_FACE, cull);
            set(GL11.GL_SCISSOR_TEST, scissor);
            GL11.glDepthMask(depthMask);
            GL11.glColorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
        }

        private static void set(int capability, boolean on) {
            if (on) GL11.glEnable(capability);
            else GL11.glDisable(capability);
        }
    }
}
