package dev.theredja.src2mc.client.render;

import static net.minecraft.commands.Commands.literal;

import dev.theredja.src2mc.Src2mc;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL33C;

/**
 * Where the GPU spends a whole frame, not only the mod's own draws ({@link GpuTimer}): a GPU
 * timestamp at the frame's start, after every render stage (shadow-pass stages marked as such) and
 * at its end, so each span between two marks is the GPU time of whatever the game, Iris and the
 * shader pack drew in between -- the shadow pass before the first main stage, the pack's deferred
 * passes before translucent terrain, its composite passes before {@code AFTER_LEVEL}, the GUI after.
 * Timestamps do not nest-conflict with {@link GpuTimer}'s elapsed-time queries. Off until
 * {@code /src2mc_gpu_frame on}; results are read a few frames late and never waited for.
 */
@EventBusSubscriber(modid = Src2mc.MOD_ID, value = Dist.CLIENT)
public final class FrameTimeline {
    private FrameTimeline() {}

    private static final int RING = 6;
    private static boolean enabled;

    /** One frame's marks: labels and their timestamp queries, in order. */
    private static final class Frame {
        final List<String> labels = new ArrayList<>();
        final List<Integer> queries = new ArrayList<>();
        boolean pending;
    }

    private static final Frame[] FRAMES = new Frame[RING];
    private static final List<Integer> POOL = new ArrayList<>();
    private static Frame current;
    private static int next;
    /** Summed nanoseconds per span since the last report, in first-seen order. */
    private static final Map<String, Long> SPANS = new LinkedHashMap<>();
    private static long frames, totalNanos;

    @SubscribeEvent
    public static void frameStart(RenderFrameEvent.Pre event) {
        if (!enabled || !GpuTimer.supported()) return;
        for (Frame frame : FRAMES) if (frame != null && frame.pending) read(frame);
        Frame frame = FRAMES[next];
        if (frame == null) FRAMES[next] = frame = new Frame();
        if (frame.pending) { current = null; return; }
        recycle(frame);
        next = (next + 1) % RING;
        current = frame;
        mark("frame start");
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void stage(RenderLevelStageEvent event) {
        if (current == null) return;
        mark((IrisCompat.renderingShadowPass() ? "shadow " : "") + event.getStage());
    }

    @SubscribeEvent
    public static void frameEnd(RenderFrameEvent.Post event) {
        if (current == null) return;
        mark("frame end (GUI)");
        current.pending = true;
        current = null;
    }

    private static void mark(String label) {
        int query = POOL.isEmpty() ? GL15C.glGenQueries() : POOL.removeLast();
        GL33C.glQueryCounter(query, GL33C.GL_TIMESTAMP);
        current.labels.add(label);
        current.queries.add(query);
    }

    private static void read(Frame frame) {
        for (int query : frame.queries) {
            if (GL15C.glGetQueryObjecti(query, GL15C.GL_QUERY_RESULT_AVAILABLE) == 0) return;
        }
        long first = 0, previous = 0;
        for (int i = 0; i < frame.queries.size(); i++) {
            long time = GL33C.glGetQueryObjecti64(frame.queries.get(i), GL15C.GL_QUERY_RESULT);
            if (i == 0) {
                first = time;
            } else {
                // Each span is named for the mark that ends it: the work drawn up to that stage.
                SPANS.merge("to " + frame.labels.get(i), time - previous, Long::sum);
            }
            previous = time;
        }
        totalNanos += previous - first;
        frames++;
        recycle(frame);
    }

    private static void recycle(Frame frame) {
        POOL.addAll(frame.queries);
        frame.queries.clear();
        frame.labels.clear();
        frame.pending = false;
    }

    @SubscribeEvent
    public static void registerCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(literal("src2mc_gpu_frame")
            .then(literal("on").executes(context -> setEnabled(context.getSource(), true)))
            .then(literal("off").executes(context -> setEnabled(context.getSource(), false)))
            .executes(context -> report(context.getSource())));
    }

    private static int setEnabled(CommandSourceStack source, boolean value) {
        enabled = value;
        SPANS.clear();
        frames = totalNanos = 0;
        source.sendSuccess(() -> Component.literal("src2mc GPU frame timeline " + (value ? "on" : "off")
            + (GpuTimer.supported() ? "" : " (timer queries unsupported)")), false);
        return 1;
    }

    /** Average GPU milliseconds per span since the last report, then starts over. */
    private static int report(CommandSourceStack source) {
        StringBuilder text = new StringBuilder("src2mc GPU frame timeline (" + (enabled ? "on" : "off") + "), " + frames
            + " frames, GPU frame " + ms(totalNanos) + " ms avg:");
        for (var span : SPANS.entrySet()) text.append("\n  ").append(ms(span.getValue())).append("  ").append(span.getKey());
        // The mod's own draws inside those spans, from GpuTimer's 120-frame windows.
        text.append("\n  mod draws (GPU ms, 120-frame avg):");
        for (GpuTimer.Phase phase : GpuTimer.Phase.values()) {
            double average = GpuTimer.averageMs(phase);
            if (average >= 0) text.append(String.format(java.util.Locale.ROOT, "\n  %6.2f  %s", average, phase.name().toLowerCase(java.util.Locale.ROOT)));
        }
        SPANS.clear();
        frames = totalNanos = 0;
        String message = text.toString();
        source.sendSuccess(() -> Component.literal(message), false);
        Src2mc.LOGGER.info(message);
        return 1;
    }

    private static String ms(long nanos) {
        return String.format(java.util.Locale.ROOT, "%6.2f", frames == 0 ? 0 : nanos / 1e6 / frames);
    }
}
