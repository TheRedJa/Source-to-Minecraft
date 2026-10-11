package dev.theredja.src2mc.levels;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.bundle.BundleManifest;
import dev.theredja.src2mc.bundle.BundleMap;
import dev.theredja.src2mc.menu.ScreensData;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * {@code /src2mc menu <campaign> [map]}: takes the player into the world of a campaign's menu map, as a level
 * change without a landmark does (the player spawns at {@code info_player_start}), but as a fresh player: nothing
 * of the old world's player is carried over. Without a map it is the campaign's map that has an INFRA main-menu
 * panel in its screens table.
 */
public final class MenuCommand {
    private MenuCommand() {}

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(literal(Src2mc.MOD_ID).then(literal("menu")
            .requires(source -> source.hasPermission(2) || source.getServer().isSingleplayer())
            .then(argument("campaign", StringArgumentType.word())
                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                    Src2mc.bundles().active().bundles().stream().map(BundleManifest::campaignId).distinct().toList(), builder))
                .executes(context -> open(context.getSource(), StringArgumentType.getString(context, "campaign"), null))
                .then(argument("map", StringArgumentType.word())
                    .suggests((context, builder) -> {
                        String campaign = StringArgumentType.getString(context, "campaign");
                        return SharedSuggestionProvider.suggest(Src2mc.bundles().active().bundles().stream()
                            .filter(bundle -> bundle.campaignId().equalsIgnoreCase(campaign))
                            .flatMap(bundle -> bundle.maps().stream().map(BundleMap::mapId)).toList(), builder);
                    })
                    .executes(context -> open(context.getSource(), StringArgumentType.getString(context, "campaign"),
                        StringArgumentType.getString(context, "map")))))));
    }

    /** The map of {@code bundle} the menu runs in: {@code mapId} if given, else the one with a main-menu panel; null when none. */
    public static BundleMap menuMap(BundleManifest bundle, String mapId) {
        if (mapId != null) return bundle.maps().stream().filter(map -> map.mapId().equalsIgnoreCase(mapId)).findFirst().orElse(null);
        List<BundleMap> candidates = bundle.maps().stream().filter(map -> {
            ScreensData data = ScreensData.of(bundle, map);
            return data != null && data.hasMainPanel();
        }).toList();
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private static int open(CommandSourceStack source, String campaign, String mapId) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("src2mc menu: only a player can open a menu"));
            return 0;
        }
        if (!source.getServer().isSingleplayer()) {
            source.sendFailure(Component.literal("src2mc menu: world changes work in singleplayer only"));
            return 0;
        }
        BundleManifest bundle = Src2mc.bundles().active().bundles().stream()
            .filter(candidate -> candidate.campaignId().equalsIgnoreCase(campaign)).findFirst().orElse(null);
        if (bundle == null) {
            source.sendFailure(Component.literal("src2mc menu: no loaded bundle has campaign `" + campaign + "`"));
            return 0;
        }
        BundleMap map = menuMap(bundle, mapId);
        if (map == null) {
            source.sendFailure(Component.literal(mapId == null
                ? "src2mc menu: no map of `" + campaign + "` has a main-menu panel; name the map"
                : "src2mc menu: `" + campaign + "` has no map `" + mapId + "`"));
            return 0;
        }
        LevelTransition.set(new LevelTransition.Pending(LevelTransition.Kind.MENU, bundle.campaignId(), map.mapId(), null, Vec3.ZERO,
            player.getYRot(), player.getXRot(), Vec3.ZERO, null));
        PacketDistributor.sendToPlayer(player, new LevelNetwork.ChangePayload(bundle.campaignId(), map.mapId()));
        source.sendSuccess(() -> Component.literal("src2mc menu: opening " + map.mapId() + " of " + bundle.campaignId()), false);
        return 1;
    }
}
