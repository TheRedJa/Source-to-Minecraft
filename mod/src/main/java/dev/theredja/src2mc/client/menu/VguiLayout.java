package dev.theredja.src2mc.client.menu;

import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.menu.KvTree;
import dev.theredja.src2mc.menu.PanelPosition;
import dev.theredja.src2mc.menu.ScreensData;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A panel's layout resolved into widgets with pixel rectangles: the controls of a layout TREE (every top-level block
 * of it, since the converter has merged {@code #base} files in as further blocks), placed as
 * {@code Panel::ApplySettings} places them, and the lists' items placed as {@code CINFRAMenuItemList} does.
 */
final class VguiLayout {
    private VguiLayout() {}

    enum Kind { PANEL, IMAGE, LABEL, LIST, BUTTON, CHAPTER }

    /** One control. Rectangles are absolute panel pixels; {@code clip} is the part of the panel its ancestors let through. */
    static final class Widget {
        final String name;
        final Kind kind;
        final KvTree props;
        final Widget parent;
        final List<Widget> children = new ArrayList<>();
        int x, y, w, h, zpos, rank;
        int[] clip = new int[4];
        String text = "", command = "";
        int chapter, part, background;
        boolean multicolumn, visible = true;
        /** Shown greyed and dead: no hover, click or sound (set by {@link ScreenView#layout}). */
        boolean disabled;

        Widget(String name, Kind kind, KvTree props, Widget parent) {
            this.name = name;
            this.kind = kind;
            this.props = props;
            this.parent = parent;
        }

        boolean interactive() { return (kind == Kind.BUTTON || kind == Kind.CHAPTER) && !command.isBlank() && !disabled; }

        /** Whether the panel pixel is inside the control and what its ancestors let through. */
        boolean contains(double px, double py) {
            return px >= Math.max(x, clip[0]) && px < Math.min(x + w, clip[2]) && py >= Math.max(y, clip[1]) && py < Math.min(y + h, clip[3]);
        }
    }

    /** The widgets in paint order (parents before their children, siblings by zpos), and the same list's chapters. */
    record Result(List<Widget> order, List<Widget> roots) {
        List<Widget> chapters() {
            return order.stream().filter(widget -> widget.kind == Kind.CHAPTER).toList();
        }
    }

    private static final Set<String> UNKNOWN = new HashSet<>();

    private static Kind kind(String controlName) {
        return switch (controlName.toLowerCase(Locale.ROOT)) {
            case "panel", "editablepanel" -> Kind.PANEL;
            case "materialimage" -> Kind.IMAGE;
            case "label" -> Kind.LABEL;
            case "cinframenuitemlist" -> Kind.LIST;
            case "cinframenuitembutton" -> Kind.BUTTON;
            case "cinframenuitemchapter" -> Kind.CHAPTER;
            default -> null;
        };
    }

    /** Resolves {@code layout} for a panel {@code pixelsWide} by {@code pixelsHigh}, with {@code unlocked} chapters. */
    static Result build(ScreensData data, VguiScheme scheme, KvTree layout, int pixelsWide, int pixelsHigh, int unlocked) {
        List<Widget> roots = new ArrayList<>();
        int itemHeight = scheme.number("INFRAMenuItemList.ItemHeight", 40);
        for (KvTree.Entry top : layout.entries()) {
            if (!top.isBlock()) continue;
            for (KvTree.Entry child : top.block().entries()) {
                Widget widget = control(data, scheme, child, null, pixelsWide, pixelsHigh, 0, 0, itemHeight, unlocked);
                if (widget != null) roots.add(widget);
            }
        }
        roots.sort(Comparator.comparingInt(widget -> widget.zpos));
        List<Widget> order = new ArrayList<>();
        int[] rootClip = {0, 0, pixelsWide, pixelsHigh};
        for (Widget root : roots) paintOrder(root, rootClip, order);
        for (int i = 0; i < order.size(); i++) order.get(i).rank = i;
        return new Result(List.copyOf(order), List.copyOf(roots));
    }

    private static void paintOrder(Widget widget, int[] parentClip, List<Widget> order) {
        if (!widget.visible) return;
        widget.clip = parentClip;
        order.add(widget);
        int[] inner = {Math.max(parentClip[0], widget.x), Math.max(parentClip[1], widget.y),
            Math.min(parentClip[2], widget.x + widget.w), Math.min(parentClip[3], widget.y + widget.h)};
        // The list places its items itself, in list order; others paint by zpos.
        List<Widget> children = new ArrayList<>(widget.children);
        if (widget.kind != Kind.LIST) children.sort(Comparator.comparingInt(child -> child.zpos));
        for (Widget child : children) paintOrder(child, inner, order);
    }

    /** One control and, for those that hold others, its children; null for a control that is not drawn. */
    private static Widget control(ScreensData data, VguiScheme scheme, KvTree.Entry entry, Widget parent, int parentWide, int parentTall,
                                  int parentX, int parentY, int itemHeight, int unlocked) {
        if (!entry.isBlock()) return null;
        String controlName = entry.block().get("ControlName");
        if (controlName == null) return null;
        Kind kind = kind(controlName);
        if (kind == null) {
            if (UNKNOWN.add(controlName.toLowerCase(Locale.ROOT))) Src2mc.LOGGER.info("src2mc menu: skipping the unknown vgui control {}", controlName);
            return null;
        }
        KvTree props = entry.block();
        Widget widget = new Widget(entry.key(), kind, props, parent);
        boolean item = kind == Kind.BUTTON || kind == Kind.CHAPTER;
        int w = PanelPosition.size(props.get("wide"), item ? 150 : 64, parentWide);
        int h = PanelPosition.size(props.get("tall"), item ? 40 : 24, parentTall);
        widget.w = w;
        widget.h = h;
        widget.x = parentX + PanelPosition.position(props.get("xpos"), 0, w, parentWide);
        widget.y = parentY + PanelPosition.position(props.get("ypos"), 0, h, parentTall);
        widget.zpos = (int) PanelPosition.parse(props.get("zpos", "0"));
        widget.command = props.get("command", "");
        widget.text = localize(data, props.get("labelText", ""));
        widget.chapter = (int) PanelPosition.parse(props.get("chapter", "0"));
        widget.part = (int) PanelPosition.parse(props.get("part", "1"));
        widget.background = (int) PanelPosition.parse(props.get("background", "0"));
        widget.multicolumn = PanelPosition.flag(props.get("multicolumn"), false);
        widget.visible = PanelPosition.flag(props.get("visible"), true);
        if (kind == Kind.LIST) {
            items(data, scheme, widget, itemHeight, unlocked);
        } else if (!item) {
            for (KvTree.Entry child : props.entries()) {
                Widget built = control(data, scheme, child, widget, w, h, widget.x, widget.y, itemHeight, unlocked);
                if (built != null) widget.children.add(built);
            }
        }
        return widget;
    }

    /** {@code #token} texts from the screens table's strings; other text as it is. */
    static String localize(ScreensData data, String text) {
        if (text == null) return "";
        if (!text.startsWith("#")) return text;
        String found = data.strings().get(text.substring(1).toLowerCase(Locale.ROOT));
        return found == null ? text : found;
    }

    /** Whether the item is shown: not a playtest-only item, and a chapter that is unlocked. */
    private static boolean shown(Widget item, int unlocked) {
        if (PanelPosition.flag(item.props.get("visible"), true) == false) return false;
        // visibleinplaytest 1 items exist for the playtest build only; this is the retail game.
        if (PanelPosition.flag(item.props.get("visibleinplaytest"), false)) return false;
        return item.kind != Kind.CHAPTER || !ChapterRule.hidden(unlocked, item.chapter, item.part);
    }

    /**
     * {@code CINFRAMenuItemList}: items as wide as the list and {@code ItemHeight} tall, in rows by
     * {@code listposition}; hidden items take no row, but a position no item holds is a blank row between
     * visible ones. {@code multicolumn} continues in a further column when the list is full; the content sits
     * north, centred or south in the list's height.
     */
    private static void items(ScreensData data, VguiScheme scheme, Widget list, int itemHeight, int unlocked) {
        List<Widget> all = new ArrayList<>();
        for (KvTree.Entry child : list.props.entries()) {
            Widget built = control(data, scheme, child, list, list.w, itemHeight, 0, 0, itemHeight, unlocked);
            if (built != null && (built.kind == Kind.BUTTON || built.kind == Kind.CHAPTER)) all.add(built);
        }
        Set<Integer> occupied = new HashSet<>();
        for (Widget item : all) occupied.add((int) PanelPosition.parse(item.props.get("listposition", "0")));
        List<Widget> visible = new ArrayList<>();
        for (Widget item : all) if (shown(item, unlocked)) visible.add(item);
        visible.sort(Comparator.comparingInt(item -> (int) PanelPosition.parse(item.props.get("listposition", "0"))));
        int rowsPerColumn = Math.max(1, list.h / itemHeight);
        int[] rows = new int[visible.size()];
        int row = 0, previous = Integer.MIN_VALUE;
        for (int i = 0; i < visible.size(); i++) {
            int position = (int) PanelPosition.parse(visible.get(i).props.get("listposition", "0"));
            if (i == 0) row = 0;
            else if (position == previous) row++;
            else {
                int blank = 0;
                for (int q = previous + 1; q < position; q++) if (!occupied.contains(q)) blank++;
                row += 1 + blank;
            }
            rows[i] = row;
            previous = position;
        }
        int totalRows = visible.isEmpty() ? 0 : rows[rows.length - 1] + 1;
        int contentRows = list.multicolumn ? Math.min(totalRows, rowsPerColumn) : totalRows;
        int free = list.h - contentRows * itemHeight;
        String alignment = list.props.get("contentalignment", "north").toLowerCase(Locale.ROOT);
        int offset = alignment.equals("center") ? Math.max(0, free / 2) : alignment.equals("south") ? Math.max(0, free) : 0;
        for (int i = 0; i < visible.size(); i++) {
            Widget item = visible.get(i);
            int column = list.multicolumn ? rows[i] / rowsPerColumn : 0;
            int inColumn = list.multicolumn ? rows[i] % rowsPerColumn : rows[i];
            item.w = list.w;
            item.h = itemHeight;
            item.x = list.x + column * list.w;
            item.y = list.y + offset + inColumn * itemHeight;
            list.children.add(item);
        }
    }
}
