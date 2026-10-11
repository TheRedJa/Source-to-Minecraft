package dev.theredja.src2mc.client.menu;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.theredja.src2mc.Src2mc;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * {@code /src2mc_menu}: {@code progress} prints the campaign's stored progress (last map, unlocked chapters); {@code status} says what the menu map's viewcontrol and screens are doing and what the cursor
 * is over; {@code chapters <n>} sets how many chapters of the campaign are unlocked, which the chapters screen shows
 * (the game will set it itself once chapters are finished).
 */
@EventBusSubscriber(modid = Src2mc.MOD_ID, value = Dist.CLIENT)
final class MenuClientCommands {
    private MenuClientCommands() {}

    @SubscribeEvent
    static void register(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(literal("src2mc_menu")
            .then(literal("status").executes(context -> {
                context.getSource().sendSuccess(() -> Component.literal(MenuClient.status()), false);
                return 1;
            }))
            .then(literal("progress").executes(context -> {
                String campaign = campaign();
                if (campaign == null) {
                    context.getSource().sendFailure(Component.literal("src2mc menu: no campaign is loaded"));
                    return 0;
                }
                context.getSource().sendSuccess(() -> Component.literal(MenuActions.progressText(campaign)), false);
                return 1;
            }))
            .then(literal("chapters")
                .then(argument("unlocked", IntegerArgumentType.integer(0, 1000)).executes(context -> {
                    String campaign = campaign();
                    int unlocked = IntegerArgumentType.getInteger(context, "unlocked");
                    if (campaign == null) {
                        context.getSource().sendFailure(Component.literal("src2mc menu: no campaign is loaded"));
                        return 0;
                    }
                    ChapterRule.setUnlocked(campaign, unlocked);
                    context.getSource().sendSuccess(() -> Component.literal("src2mc menu: " + unlocked + " chapter(s) of " + campaign + " unlocked"), false);
                    return 1;
                }))));
    }

    /** The campaign of the menu map the player is in, else the first loaded one. */
    private static String campaign() {
        MenuContext context = MenuClient.context();
        if (context != null) return context.placement.campaignId();
        var bundles = Src2mc.bundles().active().bundles();
        return bundles.isEmpty() ? null : bundles.get(0).campaignId();
    }
}
