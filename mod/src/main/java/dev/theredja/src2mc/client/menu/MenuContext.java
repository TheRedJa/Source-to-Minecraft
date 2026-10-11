package dev.theredja.src2mc.client.menu;

import dev.theredja.src2mc.bundle.BundleManifest;
import dev.theredja.src2mc.bundle.LogicTable;
import dev.theredja.src2mc.menu.KvTree;
import dev.theredja.src2mc.menu.ScreensData;
import dev.theredja.src2mc.menu.SourceSpace;
import dev.theredja.src2mc.menu.VguiBasis;
import dev.theredja.src2mc.world.MapPlacement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The map whose menu the client shows: its placement and bundle, the screens table, and a {@link ScreenView}
 * for each of its {@code vgui_screen}s that names a panel the table knows. Never a hash key: it holds tables
 * whose hashes walk everything.
 */
final class MenuContext {
    final MapPlacement placement;
    final BundleManifest bundle;
    final ScreensData data;
    final SourceSpace space;
    final long generation;
    final List<ScreenView> screens;

    private MenuContext(MapPlacement placement, BundleManifest bundle, ScreensData data, SourceSpace space, long generation, List<ScreenView> screens) {
        this.placement = placement;
        this.bundle = bundle;
        this.data = data;
        this.space = space;
        this.generation = generation;
        this.screens = screens;
    }

    long anchor() { return placement.anchorWorld().asLong(); }

    ScreenView screen(int entity) {
        for (ScreenView screen : screens) if (screen.entity == entity) return screen;
        return null;
    }

    /** The menu context of a placed map, or null when it has no screens table or no logic. */
    static MenuContext of(MapPlacement placement, BundleManifest bundle, dev.theredja.src2mc.bundle.BundleMap map, long generation) {
        ScreensData data = ScreensData.of(bundle, map);
        LogicTable logic = map.logic();
        if (data == null || logic == null) return null;
        double[] source = logic.sourceOrigin();
        SourceSpace space = new SourceSpace(source[0], source[1], source[2]);
        String schemePath = data.schemes().containsKey("resource/menuscheme.res") ? "resource/menuscheme.res"
            : data.schemes().keySet().stream().sorted().findFirst().orElse(null);
        VguiScheme scheme = new VguiScheme(schemePath == null ? null : data.schemes().get(schemePath));
        List<ScreenView> screens = new ArrayList<>();
        for (int i = 0; i < logic.entities().size() + logic.engineEntities().size(); i++) {
            LogicTable.Entity entity = i < logic.entities().size() ? logic.entities().get(i) : logic.engineEntities().get(i - logic.entities().size());
            if (!entity.classname().equals("vgui_screen")) continue;
            String panelName = entity.value("panelname");
            KvTree panel = panelName == null ? null : data.panels().get(panelName.toLowerCase(Locale.ROOT));
            if (panel == null) continue;
            VguiBasis basis = VguiBasis.of(vector(entity.value("origin")), vector(entity.value("angles")),
                number(entity.value("width"), 1), number(entity.value("height"), 1));
            String name = entity.targetname();
            ScreenView view = new ScreenView(i, name == null ? "#" + i : name, panelName.toLowerCase(Locale.ROOT), panel, basis, data, scheme);
            String campaign = placement.campaignId();
            java.util.Set<String> maps = MenuActions.campaignMapsLower(campaign);
            view.disabledTest = (command, text) -> dev.theredja.src2mc.menu.MenuFlow.disabled(command, text, maps, MenuActions.canContinue(campaign));
            screens.add(view);
        }
        return new MenuContext(placement, bundle, data, space, generation, List.copyOf(screens));
    }

    private static double[] vector(String value) {
        double[] out = new double[3];
        if (value == null) return out;
        String[] parts = value.trim().split("\\s+");
        for (int i = 0; i < 3 && i < parts.length; i++) out[i] = number(parts[i], 0);
        return out;
    }

    private static double number(String value, double fallback) {
        if (value == null) return fallback;
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }
}
