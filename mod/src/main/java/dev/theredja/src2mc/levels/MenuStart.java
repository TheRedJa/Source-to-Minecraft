package dev.theredja.src2mc.levels;

import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.bundle.BundleManifest;
import dev.theredja.src2mc.bundle.BundleMap;
import dev.theredja.src2mc.menu.MenuNetwork;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The menu's game starts, asked for by the client from the menu map: a chapter (a {@link LevelTransition.Kind#NEW_GAME}
 * into the map's world, a fresh player at {@code info_player_start}) or Continue (a {@link LevelTransition.Kind#CONTINUE}
 * into the map's existing world, the player as that world saved them). Only a player standing in a menu map's world
 * may ask, in singleplayer.
 */
public final class MenuStart {
    private MenuStart() {}

    public static void start(ServerPlayer player, MenuNetwork.StartPayload request) {
        String problem = check(player, request);
        if (problem != null) {
            Src2mc.LOGGER.warn("src2mc menu: {}'s start of {} was refused: {}", player.getGameProfile().getName(), request.mapId(), problem);
            return;
        }
        BundleManifest bundle = bundle(request.campaignId());
        BundleMap map = bundle.maps().stream().filter(candidate -> candidate.mapId().equalsIgnoreCase(request.mapId())).findFirst().orElseThrow();
        LevelTransition.set(new LevelTransition.Pending(request.resume() ? LevelTransition.Kind.CONTINUE : LevelTransition.Kind.NEW_GAME,
            bundle.campaignId(), map.mapId(), null, Vec3.ZERO, player.getYRot(), player.getXRot(), Vec3.ZERO, null));
        PacketDistributor.sendToPlayer(player, new LevelNetwork.ChangePayload(bundle.campaignId(), map.mapId()));
    }

    private static BundleManifest bundle(String campaign) {
        return Src2mc.bundles().active().bundles().stream().filter(candidate -> candidate.campaignId().equalsIgnoreCase(campaign)).findFirst().orElse(null);
    }

    /** Why the request is refused, or null. */
    private static String check(ServerPlayer player, MenuNetwork.StartPayload request) {
        if (!player.server.isSingleplayer()) return "world changes work in singleplayer only";
        BundleManifest bundle = bundle(request.campaignId());
        if (bundle == null) return "no loaded bundle has campaign " + request.campaignId();
        if (bundle.maps().stream().noneMatch(map -> map.mapId().equalsIgnoreCase(request.mapId()))) return "no map " + request.mapId();
        LevelWorldData world = LevelWorldData.get(player.server.overworld());
        BundleMap menuMap = MenuCommand.menuMap(bundle, null);
        if (!world.claimed() || menuMap == null || !world.matches(bundle.campaignId(), menuMap.mapId())) return "the player is not in the menu map's world";
        return null;
    }
}
