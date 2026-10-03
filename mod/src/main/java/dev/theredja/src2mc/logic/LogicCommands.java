package dev.theredja.src2mc.logic;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.world.MapPlacement;
import dev.theredja.src2mc.world.PlacementSavedData;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /src2mc logic ...}: starting and stopping a placed map's logic, and seeing into it. Each
 * subcommand acts on the placement holding the command's position, else the nearest, so a
 * command block beside or inside a map works on that map; a map ID narrows it to that map.
 */
public final class LogicCommands {
    private LogicCommands() {}

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(literal(Src2mc.MOD_ID).then(literal("logic")
            .requires(source -> source.hasPermission(2))
            .then(literal("start")
                .executes(context -> start(context.getSource(), null))
                .then(argument("mapId", StringArgumentType.word()).executes(context -> start(context.getSource(), StringArgumentType.getString(context, "mapId")))))
            .then(literal("stop")
                .executes(context -> stop(context.getSource(), null))
                .then(argument("mapId", StringArgumentType.word()).executes(context -> stop(context.getSource(), StringArgumentType.getString(context, "mapId")))))
            .then(literal("status")
                .executes(context -> status(context.getSource(), null))
                .then(argument("mapId", StringArgumentType.word()).executes(context -> status(context.getSource(), StringArgumentType.getString(context, "mapId")))))
            .then(literal("list")
                .then(argument("filter", StringArgumentType.word()).executes(context -> list(context.getSource(), StringArgumentType.getString(context, "filter")))))
            .then(literal("trace")
                .then(literal("on").executes(context -> trace(context.getSource(), true)))
                .then(literal("off").executes(context -> trace(context.getSource(), false))))
            // "target input [parameter]" as one text: Source names start with @ or ! and hold
            // characters a Brigadier word does not take.
            .then(literal("fire")
                .then(argument("event", StringArgumentType.greedyString())
                    .executes(context -> {
                        String[] parts = StringArgumentType.getString(context, "event").trim().split("\\s+", 3);
                        if (parts.length < 2) {
                            context.getSource().sendFailure(Component.literal("src2mc logic: fire <target> <input> [parameter]"));
                            return 0;
                        }
                        return fire(context.getSource(), parts[0], parts[1], parts.length > 2 ? parts[2] : null);
                    })))));
        event.getDispatcher().register(literal(Src2mc.MOD_ID).then(literal("movers")
            .requires(source -> source.hasPermission(2))
            .then(literal("status").executes(context -> {
                context.getSource().sendSuccess(() -> Component.literal(MoverSystem.status(context.getSource().getLevel())), false);
                return 1;
            }))
            .then(literal("respawn").executes(context -> {
                int count = MoverSystem.respawn(context.getSource().getLevel());
                context.getSource().sendSuccess(() -> Component.literal("src2mc movers: removed " + count + " sub-levels and built them again"), true);
                return 1;
            }))));
    }

    /** The placement the command means: of {@code mapId} if given, holding the source's position, else nearest it. */
    private static MapPlacement placement(CommandSourceStack source, String mapId) {
        Vec3 at = source.getPosition();
        MapPlacement best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (MapPlacement placement : PlacementSavedData.get(source.getLevel()).index().view()) {
            if (mapId != null && !placement.mapId().equalsIgnoreCase(mapId)) continue;
            double dx = Math.max(0, Math.max(placement.worldMin().getX() - at.x, at.x - placement.worldMax().getX() - 1));
            double dy = Math.max(0, Math.max(placement.worldMin().getY() - at.y, at.y - placement.worldMax().getY() - 1));
            double dz = Math.max(0, Math.max(placement.worldMin().getZ() - at.z, at.z - placement.worldMax().getZ() - 1));
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance < bestDistance) { bestDistance = distance; best = placement; }
        }
        if (best == null) source.sendFailure(Component.literal("src2mc logic: no placed map" + (mapId == null ? "" : " " + mapId) + " in this dimension"));
        return best;
    }

    private static MapLogic running(CommandSourceStack source, String mapId) {
        MapPlacement placement = placement(source, mapId);
        if (placement == null) return null;
        MapLogic logic = LogicSystem.get(source.getLevel(), placement);
        if (logic == null) source.sendFailure(Component.literal("src2mc logic: " + placement.mapId() + " is not running; /src2mc logic start it"));
        return logic;
    }

    private static int start(CommandSourceStack source, String mapId) {
        MapPlacement placement = placement(source, mapId);
        if (placement == null) return 0;
        String failure = LogicSystem.start(source.getLevel(), placement, MapLogic.LoadType.NEW_GAME);
        if (failure != null) { source.sendFailure(Component.literal("src2mc logic: " + failure)); return 0; }
        source.sendSuccess(() -> Component.literal("src2mc logic: started " + placement.mapId() + " at " + placement.anchorWorld().toShortString()
            + "; it runs while a player is inside it"), true);
        return 1;
    }

    private static int stop(CommandSourceStack source, String mapId) {
        MapPlacement placement = placement(source, mapId);
        if (placement == null) return 0;
        boolean stopped = LogicSystem.stop(source.getLevel(), placement);
        source.sendSuccess(() -> Component.literal("src2mc logic: " + (stopped ? "stopped " : "was not running: ") + placement.mapId()), true);
        return stopped ? 1 : 0;
    }

    private static int status(CommandSourceStack source, String mapId) {
        List<MapLogic> maps = mapId == null ? LogicSystem.runningMaps(source.getLevel()) : null;
        if (maps == null) {
            MapLogic logic = running(source, mapId);
            if (logic == null) return 0;
            maps = List.of(logic);
        }
        if (maps.isEmpty()) {
            source.sendSuccess(() -> Component.literal("src2mc logic: no map running in this dimension"), false);
            return 1;
        }
        for (MapLogic logic : maps) {
            String text = logic.status() + (logic.inside().isEmpty() ? "\n  waiting: nobody inside" : "")
                + (source.getLevel().tickRateManager().runsNormally() ? "" : "\n  waiting: tick rate frozen");
            source.sendSuccess(() -> Component.literal(text), false);
        }
        return maps.size();
    }

    private static int list(CommandSourceStack source, String filter) {
        MapLogic logic = running(source, null);
        if (logic == null) return 0;
        List<String> lines = logic.list(filter, 40);
        source.sendSuccess(() -> Component.literal(lines.isEmpty() ? "src2mc logic: no entity matches " + filter
            : String.join("\n", lines) + (lines.size() == 40 ? "\n(first 40)" : "")), false);
        return lines.size();
    }

    private static int trace(CommandSourceStack source, boolean on) {
        if (!(source.getEntity() instanceof ServerPlayer player)) { source.sendFailure(Component.literal("src2mc logic: tracing is per player")); return 0; }
        for (MapLogic logic : LogicSystem.runningMaps(source.getLevel())) logic.setTrace(player.getUUID(), on);
        source.sendSuccess(() -> Component.literal("src2mc logic: output trace " + (on ? "on" : "off")), false);
        return 1;
    }

    /** {@code ent_fire}: queues an input as if an output fired it, with the player as activator. */
    private static int fire(CommandSourceStack source, String target, String input, String parameter) {
        MapLogic logic = running(source, null);
        if (logic == null) return 0;
        Actor activator = source.getEntity() instanceof ServerPlayer player ? new PlayerActor(player.getUUID()) : null;
        logic.queue(0, target, null, input, parameter, activator, null);
        source.sendSuccess(() -> Component.literal("src2mc logic: queued " + target + "." + input + (parameter == null ? "" : "(" + parameter + ")")
            + " in " + logic.placement.mapId()), false);
        return 1;
    }
}
