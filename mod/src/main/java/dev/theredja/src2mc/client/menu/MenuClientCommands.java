package dev.theredja.src2mc.client.menu;

import dev.theredja.src2mc.Src2mc;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

/**
 * Handlers for {@code /src2mcc menu}: {@code progress} prints the campaign's stored progress (last map, unlocked
 * chapters); {@code chapters <n>} sets how many chapters of the campaign are unlocked, which the chapters screen shows
 * (the game will set it itself once chapters are finished); {@link #statusText()} says what the menu map's viewcontrol
 * and screens are doing and what the cursor is over ({@code /src2mcc status menu}).
 */
public final class MenuClientCommands {
    private MenuClientCommands() {}

    public static String statusText() {
        return MenuClient.status();
    }

    public static int progress(CommandSourceStack source) {
        String campaign = campaign();
        if (campaign == null) {
            source.sendFailure(Component.literal("src2mc menu: no campaign is loaded"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(MenuActions.progressText(campaign)), false);
        return 1;
    }

    public static int chapters(CommandSourceStack source, int unlocked) {
        String campaign = campaign();
        if (campaign == null) {
            source.sendFailure(Component.literal("src2mc menu: no campaign is loaded"));
            return 0;
        }
        ChapterRule.setUnlocked(campaign, unlocked);
        source.sendSuccess(() -> Component.literal("src2mc menu: " + unlocked + " chapter(s) of " + campaign + " unlocked"), false);
        return 1;
    }

    /** The campaign of the menu map the player is in, else the first loaded one. */
    private static String campaign() {
        MenuContext context = MenuClient.context();
        if (context != null) return context.placement.campaignId();
        var bundles = Src2mc.bundles().active().bundles();
        return bundles.isEmpty() ? null : bundles.get(0).campaignId();
    }
}
