package dev.theredja.src2mc.client.menu;

import dev.theredja.src2mc.Src2mc;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import net.neoforged.fml.loading.FMLPaths;

/**
 * One campaign's progress as the menu keeps it, client side in {@code config/src2mc/menu.properties}: the map the
 * player last arrived in (whose world Continue rejoins) and the unlocked chapters, INFRA's {@code sv_unlockedchapters}
 * (default 1).
 */
public final class CampaignProgress {
    private CampaignProgress() {}

    /** @param lastMap the last non-menu map arrived in, lower case, or empty */
    public record Progress(String lastMap, int unlocked) {
        public Progress {
            lastMap = lastMap == null ? "" : lastMap;
            unlocked = Math.max(0, unlocked);
        }

        /** The progress after arriving in {@code map}, which belongs to {@code chapter} (0 when unknown): unlocked only ever grows. */
        public Progress arrived(String map, int chapter) {
            return new Progress(map.toLowerCase(Locale.ROOT), Math.max(unlocked, chapter));
        }
    }

    private static final Properties VALUES = new Properties();
    private static boolean loaded;

    private static Path file() { return FMLPaths.CONFIGDIR.get().resolve("src2mc").resolve("menu.properties"); }

    private static synchronized void load() {
        if (loaded) return;
        loaded = true;
        Path path = file();
        if (!Files.isRegularFile(path)) return;
        try (InputStream input = Files.newInputStream(path)) {
            VALUES.load(input);
        } catch (IOException | RuntimeException exception) {
            Src2mc.LOGGER.warn("src2mc menu: cannot read {}", path, exception);
        }
    }

    private static String unlockedKey(String campaign) { return "unlockedChapters." + campaign.toLowerCase(Locale.ROOT); }

    private static String lastKey(String campaign) { return "lastMap." + campaign.toLowerCase(Locale.ROOT); }

    public static synchronized Progress get(String campaign) {
        load();
        int unlocked;
        try {
            unlocked = Integer.parseInt(VALUES.getProperty(unlockedKey(campaign), Integer.toString(ChapterRule.DEFAULT_UNLOCKED)).trim());
        } catch (NumberFormatException exception) {
            unlocked = ChapterRule.DEFAULT_UNLOCKED;
        }
        return new Progress(VALUES.getProperty(lastKey(campaign), "").trim(), unlocked);
    }

    public static synchronized void set(String campaign, Progress progress) {
        load();
        VALUES.setProperty(unlockedKey(campaign), Integer.toString(progress.unlocked()));
        if (progress.lastMap().isEmpty()) VALUES.remove(lastKey(campaign));
        else VALUES.setProperty(lastKey(campaign), progress.lastMap());
        Path path = file();
        try {
            Files.createDirectories(path.getParent());
            try (OutputStream output = Files.newOutputStream(path)) {
                VALUES.store(output, "src2mc menu state (client)");
            }
        } catch (IOException exception) {
            Src2mc.LOGGER.warn("src2mc menu: cannot write {}", path, exception);
        }
    }
}
