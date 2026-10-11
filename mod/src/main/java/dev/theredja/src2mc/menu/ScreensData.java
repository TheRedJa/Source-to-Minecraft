package dev.theredja.src2mc.menu;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.bundle.BundleManifest;
import dev.theredja.src2mc.bundle.BundleMap;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipFile;

/**
 * A map's {@code screens.json} (format section 26): what its {@code vgui_screen} panels are, their layouts, the
 * colour schemes, the images and sounds layouts use and the localized texts.
 */
public record ScreensData(Map<String, KvTree> panels, Map<String, KvTree> layouts, Map<String, KvTree> schemes,
                          List<Image> images, List<Sound> sounds, Map<String, String> strings) {
    /** A material the layouts draw: {@code path} is the PNG's entry in the bundle. */
    public record Image(String material, String path, int width, int height, boolean translucent, boolean additive) {}

    /** A wave the menu plays: {@code path} is the Ogg's entry in the bundle; {@code loopStart} seconds, or -1. */
    public record Sound(String wave, String path, boolean loop, double loopStart) {}

    public static final String MAIN_PANEL_TYPE = "infra_menu_main";

    /** The {@code type} of a panel, lower case, or null. */
    public String panelType(String panelName) {
        KvTree panel = panels.get(panelName.toLowerCase(Locale.ROOT));
        String type = panel == null ? null : panel.get("type");
        return type == null ? null : type.toLowerCase(Locale.ROOT);
    }

    /** Whether any panel is the INFRA main menu; the map is then a menu map. */
    public boolean hasMainPanel() {
        for (String name : panels.keySet()) if (MAIN_PANEL_TYPE.equals(panelType(name))) return true;
        return false;
    }

    public Image image(String material) {
        if (material == null) return null;
        String key = material.toLowerCase(Locale.ROOT).replace('\\', '/');
        for (Image image : images) if (image.material().equals(key)) return image;
        return null;
    }

    public Sound sound(String wave) {
        for (Sound sound : sounds) if (sound.wave().equals(wave)) return sound;
        return null;
    }

    /** Reads the table from its JSON text. */
    public static ScreensData parse(String text) {
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        Map<String, KvTree> panels = trees(root.getAsJsonObject("panels")), layouts = trees(root.getAsJsonObject("layouts")),
            schemes = trees(root.getAsJsonObject("schemes"));
        List<Image> images = new ArrayList<>();
        if (root.has("images")) for (JsonElement element : root.getAsJsonArray("images")) {
            JsonObject image = element.getAsJsonObject();
            images.add(new Image(image.get("material").getAsString().toLowerCase(Locale.ROOT), image.get("path").getAsString(),
                image.get("width").getAsInt(), image.get("height").getAsInt(), image.get("translucent").getAsBoolean(), image.get("additive").getAsBoolean()));
        }
        List<Sound> sounds = new ArrayList<>();
        if (root.has("sounds")) for (JsonElement element : root.getAsJsonArray("sounds")) {
            JsonObject sound = element.getAsJsonObject();
            boolean loop = sound.get("loop").getAsBoolean();
            sounds.add(new Sound(sound.get("wave").getAsString(), sound.get("path").getAsString(), loop,
                loop && sound.has("loop_start") ? sound.get("loop_start").getAsDouble() : -1));
        }
        Map<String, String> strings = new HashMap<>();
        if (root.has("strings")) for (var entry : root.getAsJsonObject("strings").entrySet()) strings.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue().getAsString());
        return new ScreensData(panels, layouts, schemes, images, sounds, strings);
    }

    private static Map<String, KvTree> trees(JsonObject object) {
        Map<String, KvTree> out = new HashMap<>();
        if (object != null) for (var entry : object.entrySet()) out.put(entry.getKey().toLowerCase(Locale.ROOT), KvTree.of(entry.getValue()));
        return out;
    }

    // ---- Loading from a bundle.

    private static final Map<String, ScreensData> CACHE = new HashMap<>();
    private static long cacheGeneration = -1;

    /** The map's screens table, or null when it has none or cannot be read (logged once per generation). */
    public static synchronized ScreensData of(BundleManifest bundle, BundleMap map) {
        if (map.screens() == null) return null;
        long generation = Src2mc.bundles().active().sequence();
        if (generation != cacheGeneration) { CACHE.clear(); cacheGeneration = generation; }
        String key = bundle.path() + "|" + map.mapId();
        if (CACHE.containsKey(key)) return CACHE.get(key);
        ScreensData data = null;
        try {
            data = parse(new String(read(bundle, map.screens()), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException exception) {
            Src2mc.LOGGER.error("src2mc: cannot read the screens of {}", map.mapId(), exception);
        }
        CACHE.put(key, data);
        return data;
    }

    /** The bytes of an entry of the bundle, which validation has already checked exists. */
    public static byte[] read(BundleManifest bundle, String entryPath) throws IOException {
        try (ZipFile zip = new ZipFile(bundle.path().toFile())) {
            var entry = zip.getEntry(entryPath);
            if (entry == null) throw new IOException(entryPath + " is not in " + bundle.path());
            try (InputStream input = zip.getInputStream(entry)) {
                return input.readAllBytes();
            }
        }
    }
}
