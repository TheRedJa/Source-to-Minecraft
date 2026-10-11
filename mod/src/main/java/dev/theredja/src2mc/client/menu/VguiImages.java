package dev.theredja.src2mc.client.menu;

import com.mojang.blaze3d.platform.NativeImage;
import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.bundle.BundleManifest;
import dev.theredja.src2mc.menu.ScreensData;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * The PNGs of a screens table as textures: each is read from its bundle and uploaded the first time a layout needs
 * it, and freed when the world is left or the bundles reload.
 */
final class VguiImages {
    private VguiImages() {}

    private static final Map<String, ResourceLocation> TEXTURES = new HashMap<>();
    private static final Set<String> FAILED = new HashSet<>();
    private static long generation = -1;

    /** The texture of an image, uploading it now if it is new; null for one that cannot be read (logged once). */
    static ResourceLocation texture(BundleManifest bundle, ScreensData.Image image) {
        long current = Src2mc.bundles().active().sequence();
        if (current != generation) {
            clear();
            generation = current;
        }
        String key = bundle.path() + "|" + image.path();
        ResourceLocation found = TEXTURES.get(key);
        if (found != null || FAILED.contains(key)) return found;
        try {
            NativeImage pixels = NativeImage.read(new ByteArrayInputStream(ScreensData.read(bundle, image.path())));
            DynamicTexture texture = new DynamicTexture(pixels);
            // Linear without mipmaps: the image is a flat quad drawn near its own size.
            texture.setFilter(true, false);
            ResourceLocation location = ResourceLocation.fromNamespaceAndPath(Src2mc.MOD_ID, "menu/image_" + TEXTURES.size() + "_" + Integer.toHexString(key.hashCode()));
            Minecraft.getInstance().getTextureManager().register(location, texture);
            TEXTURES.put(key, location);
            return location;
        } catch (IOException | RuntimeException exception) {
            Src2mc.LOGGER.warn("src2mc menu: cannot load the image {}", image.material(), exception);
            FAILED.add(key);
            return null;
        }
    }

    /** Whether the image is uploaded already. */
    static boolean loaded(BundleManifest bundle, ScreensData.Image image) {
        return TEXTURES.containsKey(bundle.path() + "|" + image.path());
    }

    static void clear() {
        Minecraft minecraft = Minecraft.getInstance();
        for (ResourceLocation location : TEXTURES.values()) minecraft.getTextureManager().release(location);
        TEXTURES.clear();
        FAILED.clear();
    }
}
