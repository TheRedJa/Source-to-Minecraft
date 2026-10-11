package dev.theredja.src2mc.client.menu;


/**
 * Which chapters the chapters screen shows, and the number of unlocked chapters per campaign that decides it.
 * INFRA keeps that number in the server cvar {@code sv_unlockedchapters} (default 1); here it is a client value
 * kept with the rest of the campaign's progress ({@link CampaignProgress}) in {@code config/src2mc/menu.properties}, raised when the player arrives in a map of a later chapter ({@link MenuActions#recordArrival}) and
 * settable with {@code /src2mc_menu chapters <n>}.
 */
public final class ChapterRule {
    private ChapterRule() {}

    /** INFRA's {@code sv_unlockedchapters} default. */
    public static final int DEFAULT_UNLOCKED = 1;
    /** Chapters per part of the game; a multiple of this unlocked means whole parts are. */
    private static final int PART_SIZE = 15;

    /**
     * Whether {@code CINFRAMenuItemChapter} hides the chapter of {@code part} for {@code unlocked} = {@code u}:
     * with {@code r = u mod 15}, when {@code r} is 0 a part is hidden unless {@code u >= part * 15}, else a
     * chapter is hidden unless {@code u >= chapter}.
     */
    public static boolean hidden(int unlocked, int chapter, int part) {
        if (unlocked % PART_SIZE == 0) return unlocked < part * PART_SIZE;
        return unlocked < chapter;
    }

    // ---- The stored value (see CampaignProgress).

    /** The campaign's unlocked chapters; {@link #DEFAULT_UNLOCKED} until set. */
    public static int unlocked(String campaign) { return CampaignProgress.get(campaign).unlocked(); }

    public static void setUnlocked(String campaign, int unlocked) {
        CampaignProgress.Progress current = CampaignProgress.get(campaign);
        CampaignProgress.set(campaign, new CampaignProgress.Progress(current.lastMap(), unlocked));
    }
}
