package dev.theredja.src2mc.client.menu;

import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.bundle.BundleManifest;
import dev.theredja.src2mc.bundle.BundleMap;
import dev.theredja.src2mc.levels.LevelTransition;
import dev.theredja.src2mc.levels.MenuCommand;
import dev.theredja.src2mc.menu.ChapterMaps;
import dev.theredja.src2mc.menu.KvTree;
import dev.theredja.src2mc.menu.MenuFlow;
import dev.theredja.src2mc.menu.MenuNetwork;
import dev.theredja.src2mc.menu.ScreensData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * What the menu's own commands do ({@code play}, {@code continue}, {@code new_game}, {@code quit}, {@code map <name>};
 * {@code switchscreen} and {@code engine} are {@link MenuClient#run}'s): the decisions are {@link MenuFlow}'s, the
 * rest is the fade, the world folders and the request to the server.
 */
public final class MenuActions {
    private MenuActions() {}

    private static final String GAME_SCREEN = "scripts/screens/infra_menu_main_game.res";
    private static final String DIALOG_LAYOUT = "scripts/screens/infra_menu_dialog.res";
    private static final String CHAPTERS_RELAY = "engine ent_fire menu_chapters_relay trigger";
    /** Our own command: the confirm button of the restart dialog. */
    private static final String CONFIRM = "src2mc_confirm_map ";
    /** Our own text (INFRA has no such dialog on its chapter screen). */
    private static final String DIALOG_MESSAGE = "Starting here deletes this campaign's saved progress.";

    // ---- The campaign's maps and worlds.

    /** The map ids of every loaded bundle of {@code campaign}. */
    static List<String> campaignMaps(String campaign) {
        List<String> out = new ArrayList<>();
        for (BundleManifest bundle : Src2mc.bundles().active().bundles()) {
            if (bundle.campaignId().equalsIgnoreCase(campaign)) for (BundleMap map : bundle.maps()) out.add(map.mapId());
        }
        return out;
    }

    static Set<String> campaignMapsLower(String campaign) {
        Set<String> out = new HashSet<>();
        for (String map : campaignMaps(campaign)) out.add(map.toLowerCase(Locale.ROOT));
        return out;
    }

    private static Path worldPath(String campaign, String map) {
        return Minecraft.getInstance().getLevelSource().getLevelPath(LevelTransition.folderName(campaign, map));
    }

    private static boolean hasLevelData(Path folder) {
        return Files.isRegularFile(folder.resolve("level.dat")) || Files.isRegularFile(folder.resolve("level.dat_old"));
    }

    /** Whether the campaign's last played map has a world to rejoin. */
    static boolean canContinue(String campaign) {
        String last = CampaignProgress.get(campaign).lastMap();
        return !last.isEmpty() && hasLevelData(worldPath(campaign, last));
    }

    /** The existing world folders of the campaign's maps that a restart deletes. */
    private static List<String> wipeList(MenuContext context) {
        Set<String> existing = new HashSet<>();
        String campaign = context.placement.campaignId();
        for (String map : campaignMaps(campaign)) {
            String folder = LevelTransition.folderName(campaign, map);
            if (hasLevelData(Minecraft.getInstance().getLevelSource().getLevelPath(folder))) existing.add(folder);
        }
        var server = Minecraft.getInstance().getSingleplayerServer();
        String current = server == null ? null
            : server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).normalize().getFileName().toString();
        return MenuFlow.wipeFolders(existing, campaign, campaignMaps(campaign), context.placement.mapId(), current);
    }

    // ---- Progress.

    /**
     * The player arrived in {@code mapId} of {@code campaign}: unless it is the menu map, it becomes the last played map
     * and its chapter unlocks (never lowering the number).
     */
    public static void recordArrival(String campaign, String mapId) {
        BundleManifest bundle = Src2mc.bundles().active().bundles().stream()
            .filter(candidate -> candidate.campaignId().equalsIgnoreCase(campaign)).findFirst().orElse(null);
        if (bundle == null) return;
        BundleMap menuMap = MenuCommand.menuMap(bundle, null);
        if (menuMap != null && menuMap.mapId().equalsIgnoreCase(mapId)) return;
        int chapter = ChapterMaps.unlockChapter(mapId);
        CampaignProgress.Progress before = CampaignProgress.get(campaign);
        CampaignProgress.Progress after = before.arrived(mapId, chapter);
        CampaignProgress.set(campaign, after);
        Src2mc.LOGGER.info("src2mc menu: arrived in {} (chapter {}); last map {}, unlocked chapters {} -> {}", mapId, chapter, after.lastMap(), before.unlocked(), after.unlocked());
    }

    static String progressText(String campaign) {
        CampaignProgress.Progress progress = CampaignProgress.get(campaign);
        return "src2mc menu: " + campaign + ": last map " + (progress.lastMap().isEmpty() ? "none" : progress.lastMap())
            + (canContinue(campaign) ? " (world exists)" : " (no world)") + ", unlocked chapters " + progress.unlocked();
    }

    // ---- Commands.

    static void handle(MenuContext context, ScreenView screen, String command) {
        String text = command.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        String campaign = context.placement.campaignId();
        if (lower.equals("play")) {
            switch (MenuFlow.play(canContinue(campaign), CampaignProgress.get(campaign).unlocked())) {
                case GAME_SCREEN -> screen.switchTo(GAME_SCREEN);
                case CHAPTERS -> MenuClient.run(context, screen, CHAPTERS_RELAY);
                case FIRST_MAP -> map(context, screen, firstMap(context));
            }
        } else if (lower.equals("new_game")) {
            switch (MenuFlow.newGame(CampaignProgress.get(campaign).unlocked())) {
                case CHAPTERS -> MenuClient.run(context, screen, CHAPTERS_RELAY);
                default -> map(context, screen, firstMap(context));
            }
        } else if (lower.equals("continue")) {
            if (canContinue(campaign)) begin(context, true, CampaignProgress.get(campaign).lastMap(), List.of());
        } else if (lower.equals("quit")) {
            quit(context);
        } else if (lower.startsWith("map ")) {
            map(context, screen, ChapterMaps.mapOf(text));
        } else if (lower.startsWith(CONFIRM)) {
            String map = text.substring(CONFIRM.length()).trim();
            begin(context, false, map, wipeList(context));
        } else {
            Src2mc.LOGGER.info("src2mc menu: command \"{}\" is not supported (saves and apply do not exist in this port)", text);
        }
    }

    private static String firstMap(MenuContext context) {
        TreeMap<Integer, String> firsts = ChapterMaps.firstMaps(context.data);
        String map = firsts.get(1);
        if (map == null) Src2mc.LOGGER.warn("src2mc menu: the chapters layout names no chapter 1 map");
        return map;
    }

    /** {@code map X}: starts X fresh, after a confirmation when the campaign has map worlds to delete. */
    private static void map(MenuContext context, ScreenView screen, String map) {
        if (map == null) return;
        String campaign = context.placement.campaignId();
        String id = campaignMaps(campaign).stream().filter(candidate -> candidate.equalsIgnoreCase(map)).findFirst().orElse(null);
        if (id == null) {
            Src2mc.LOGGER.warn("src2mc menu: map {} is not in the {} bundle", map, campaign);
            return;
        }
        if (wipeList(context).isEmpty()) {
            begin(context, false, id, List.of());
            return;
        }
        KvTree dialog = context.data.layouts().get(DIALOG_LAYOUT);
        if (dialog == null) {
            Src2mc.LOGGER.warn("src2mc menu: the bundle has no {}; not deleting saved progress without asking", DIALOG_LAYOUT);
            return;
        }
        String back = screen.res();
        screen.showOwn("src2mc:confirm", ConfirmDialog.build(dialog, screen.pixelsWide, screen.pixelsHigh, DIALOG_MESSAGE,
            "#infra_menu_new_game", CONFIRM + id, "#GameUI_Back", "switchscreen " + back));
    }

    /** Fades out like INFRA, then deletes {@code wipe}, then asks the server to start {@code map} (fresh, or Continue when {@code resume}). */
    private static void begin(MenuContext context, boolean resume, String map, List<String> wipe) {
        if (ScreenFade.fadingOut()) return;
        String campaign = context.placement.campaignId();
        MenuClient.fadeMusic(ScreenFade.FADE_OUT_SECONDS);
        ScreenFade.fadeOut(() -> {
            Minecraft minecraft = Minecraft.getInstance();
            for (String folder : wipe) {
                try (var access = minecraft.getLevelSource().createAccess(folder)) {
                    Src2mc.LOGGER.info("src2mc menu: deleting the world {}", folder);
                    access.deleteLevel();
                } catch (IOException | RuntimeException exception) {
                    Src2mc.LOGGER.error("src2mc menu: cannot delete the world {}", folder, exception);
                    if (minecraft.player != null) minecraft.player.displayClientMessage(Component.literal("src2mc menu: cannot delete the world " + folder + ": " + exception.getMessage()), false);
                    MenuClient.restoreMusic();
                    ScreenFade.clear();
                    return;
                }
            }
            PacketDistributor.sendToServer(new MenuNetwork.StartPayload(resume, campaign, map));
        });
    }

    /** Quit after the confirmation screen: fades out, then leaves to Minecraft's title screen as Save and Quit does. */
    private static void quit(MenuContext context) {
        if (ScreenFade.fadingOut()) return;
        MenuClient.fadeMusic(ScreenFade.FADE_OUT_SECONDS);
        ScreenFade.fadeOut(() -> {
            Minecraft minecraft = Minecraft.getInstance();
            boolean local = minecraft.isLocalServer();
            if (minecraft.level != null) minecraft.level.disconnect();
            if (local) minecraft.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")));
            else minecraft.disconnect();
            minecraft.setScreen(new TitleScreen());
            ScreenFade.clear();
        });
    }
}
