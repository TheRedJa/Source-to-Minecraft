package dev.theredja.src2mc.client.menu;

import dev.theredja.src2mc.menu.KvTree;
import dev.theredja.src2mc.menu.PanelPosition;
import dev.theredja.src2mc.menu.ScreensData;
import dev.theredja.src2mc.menu.VguiBasis;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The client's state of one {@code vgui_screen}: where it is, which layout it shows (the panel's {@code resfile}
 * until a {@code switchscreen} changes it), the control under the cursor and the chapter backgrounds' fades.
 */
final class ScreenView {
    /** The chapters screen fades a background to this alpha while its chapter is hovered (scripts/infra_vgui_screen_animations.txt). */
    static final int BACKGROUND_ALPHA = 30;
    /** The fade's length in seconds; the interpolation is vgui's {@code Deaccel}: the square root of the progress. */
    static final double FADE_SECONDS = 0.25;
    private static final Pattern BACKGROUND_NAME = Pattern.compile("background(\\d+)");

    final int entity;
    final String name;
    final String panelName;
    final VguiBasis basis;
    final int pixelsWide, pixelsHigh;
    final boolean acceptsInput;
    final VguiScheme scheme;
    final ScreensData data;
    /** Whether a control (command, label text) is shown disabled; set by {@link MenuContext}. */
    java.util.function.BiPredicate<String, String> disabledTest = (command, text) -> false;
    /** Layouts this client made itself (the confirmation dialog), by the key {@link #res} takes. */
    private final Map<String, KvTree> ownLayouts = new java.util.HashMap<>();

    private String res;
    private VguiLayout.Result layout;
    private int layoutUnlocked = Integer.MIN_VALUE;
    private String layoutRes;
    VguiLayout.Widget hovered;
    private int hoveredBackground = -1;
    private boolean hasChapters;
    private final Map<VguiLayout.Widget, Fade> fades = new IdentityHashMap<>();

    /** A background's alpha moving from {@code from} to {@code to}, started at {@code started} (seconds). */
    private static final class Fade {
        double from, to, started;
    }

    ScreenView(int entity, String name, String panelName, KvTree panel, VguiBasis basis, ScreensData data, VguiScheme scheme) {
        this.entity = entity;
        this.name = name;
        this.panelName = panelName;
        this.basis = basis;
        this.data = data;
        this.scheme = scheme;
        this.pixelsWide = Math.max(1, (int) PanelPosition.parse(panel.get("pixelswide", "640")));
        this.pixelsHigh = Math.max(1, (int) PanelPosition.parse(panel.get("pixelshigh", "480")));
        this.acceptsInput = PanelPosition.flag(panel.get("acceptsinput"), true);
        this.res = panel.get("resfile", "").toLowerCase(Locale.ROOT).replace('\\', '/');
    }

    String res() { return res; }

    /** {@code switchscreen}: shows another layout, at once. False when the table has none of that name. */
    boolean switchTo(String next) {
        String key = next.trim().toLowerCase(Locale.ROOT).replace('\\', '/');
        if (!data.layouts().containsKey(key) && !ownLayouts.containsKey(key)) return false;
        res = key;
        hovered = null;
        return true;
    }

    /** Shows a layout made by this client under {@code key} (replacing an earlier one of that key). */
    void showOwn(String key, KvTree tree) {
        ownLayouts.put(key, tree);
        res = key;
        layoutRes = null;
        hovered = null;
    }

    /** The layout for {@code unlocked} chapters, laid out again when the layout or that number changed. */
    VguiLayout.Result layout(int unlocked) {
        if (layout == null || !res.equals(layoutRes) || unlocked != layoutUnlocked) {
            KvTree tree = ownLayouts.containsKey(res) ? ownLayouts.get(res) : data.layouts().get(res);
            layout = VguiLayout.build(data, scheme, tree == null ? KvTree.EMPTY : tree, pixelsWide, pixelsHigh, unlocked);
            for (VguiLayout.Widget widget : layout.order()) {
                widget.disabled = (widget.kind == VguiLayout.Kind.BUTTON || widget.kind == VguiLayout.Kind.CHAPTER)
                    && disabledTest.test(widget.command, widget.props.get("labelText", ""));
            }
            layoutRes = res;
            hasChapters = !layout.chapters().isEmpty();
            layoutUnlocked = unlocked;
            hovered = null;
            fades.clear();
            hoveredBackground = -1;
        }
        return layout;
    }

    /** The topmost control under panel pixel ({@code px}, {@code py}) that can be clicked, or null. */
    VguiLayout.Widget hit(double px, double py, int unlocked) {
        List<VguiLayout.Widget> order = layout(unlocked).order();
        for (int i = order.size() - 1; i >= 0; i--) {
            VguiLayout.Widget widget = order.get(i);
            if (widget.interactive() && widget.contains(px, py)) return widget;
        }
        return null;
    }

    /** The chapter background number the fades follow: the hovered chapter's, or none. */
    void follow(VguiLayout.Widget hover, double now) {
        hovered = hover;
        if (layout == null) return;
        int next = hover != null && hover.kind == VguiLayout.Kind.CHAPTER ? hover.background : -1;
        if (next == hoveredBackground) return;
        hoveredBackground = next;
        // ChaptersScreenBackground<N> raises background N to 30 and every other to 0, over 0.25 s; None lowers all.
        for (VguiLayout.Widget widget : layout.order()) {
            Integer number = backgroundNumber(widget);
            if (number == null) continue;
            Fade fade = fades.computeIfAbsent(widget, ignored -> new Fade());
            double current = alpha(widget, now);
            fade.from = current;
            fade.to = number == next ? BACKGROUND_ALPHA : 0;
            fade.started = now;
        }
    }

    /** {@code N} of a control named {@code background<N>} on a screen that has chapters; null otherwise. */
    private Integer backgroundNumber(VguiLayout.Widget widget) {
        if (widget.kind != VguiLayout.Kind.IMAGE) return null;
        Matcher matcher = BACKGROUND_NAME.matcher(widget.name.toLowerCase(Locale.ROOT));
        if (!matcher.matches() || !hasChapters) return null;
        return Integer.parseInt(matcher.group(1));
    }

    /** The alpha (0..255) an image is drawn with at {@code now} seconds: its {@code alpha} key, or the running fade. */
    double alpha(VguiLayout.Widget widget, double now) {
        Fade fade = fades.get(widget);
        double base = PanelPosition.parse(widget.props.get("alpha", "255"));
        if (fade == null) return base;
        double progress = Math.min(1, Math.max(0, (now - fade.started) / FADE_SECONDS));
        return fade.from + (fade.to - fade.from) * Math.sqrt(progress);
    }
}
