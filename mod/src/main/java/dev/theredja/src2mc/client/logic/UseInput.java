package dev.theredja.src2mc.client.logic;

import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.bundle.BundleGeneration;
import dev.theredja.src2mc.bundle.BundleMap;
import dev.theredja.src2mc.bundle.LogicTable;
import dev.theredja.src2mc.logic.LogicNetwork;
import dev.theredja.src2mc.logic.Variant;
import dev.theredja.src2mc.network.PlacementNetwork;
import dev.theredja.src2mc.world.MapPlacement;
import dev.theredja.src2mc.world.MoverRegistry;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The use key on a running map's buttons and doors, Source's {@code +use}: the look ray within
 * block reach is tested against every usable entity's brushes, and a hit nothing nearer blocks
 * goes to the server instead of Minecraft's own use. A momentary button, or an INFRA button that
 * can be held, keeps receiving the use while the key stays down on it.
 */
@EventBusSubscriber(modid = Src2mc.MOD_ID, value = Dist.CLIENT)
public final class UseInput {
    private static final int BUTTON_USE_ACTIVATES = 1024, DOOR_USE_OPENS = 256, INFRA_CAN_BE_HELD = 16384;
    /** How much nearer than the entity a block may be hit before it counts as in the way: the map's own blocks hold its faces. */
    private static final double BLOCK_TOLERANCE = 0.5;

    private record Target(long anchor, int entity, boolean continuous) {}

    private static Target held;

    private UseInput() {}

    @SubscribeEvent
    static void onInteract(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isUseItem()) return;
        Target target = aim();
        if (target == null) return;
        event.setCanceled(true);
        event.setSwingHand(true);
        PacketDistributor.sendToServer(new LogicNetwork.UsePayload(target.anchor, target.entity, true));
        held = target.continuous ? target : null;
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        if (held == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !minecraft.options.keyUse.isDown()) { held = null; return; }
        Target now = aim();
        if (now == null || now.anchor != held.anchor || now.entity != held.entity) { held = null; return; }
        PacketDistributor.sendToServer(new LogicNetwork.UsePayload(held.anchor, held.entity, false));
    }

    /** The usable entity the player looks at within reach, or null. */
    private static Target aim() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) return null;
        var dimension = minecraft.level.dimension().location();
        Vec3 eye = minecraft.player.getEyePosition();
        double reach = minecraft.player.blockInteractionRange();
        Vec3 ray = minecraft.player.getViewVector(1.0F).scale(reach);
        BundleGeneration generation = Src2mc.bundles().active();
        Target best = null;
        double bestFraction = Double.POSITIVE_INFINITY;
        for (MapPlacement placement : PlacementNetwork.clientIndex(dimension).view()) {
            long anchor = placement.anchorWorld().asLong();
            if (!ClientLogic.running(dimension, anchor) || !near(placement, eye, reach)) continue;
            BundleMap map = generation.findMap(placement.campaignId(), placement.mapId()).orElse(null);
            LogicTable logic = map == null ? null : map.logic();
            if (logic == null) continue;
            Map<Integer, MoverRegistry.Instance> movers = new HashMap<>();
            for (MoverRegistry.Instance instance : MoverRegistry.instances(true)) {
                if (instance.anchor() == anchor) movers.put(instance.entity(), instance);
            }
            for (int i = 0; i < logic.entities().size(); i++) {
                LogicTable.Entity entity = logic.entities().get(i);
                if (entity.volume() < 0 || !usable(entity)) continue;
                LogicTable.Volume volume = logic.volumes().get(entity.volume());
                // A door that has moved is aimed at where it is now: the ray goes into the frame it was compiled in.
                Vec3 from = eye, to = eye.add(ray);
                MoverRegistry.Instance mover = movers.get(i);
                if (mover != null) {
                    Vec3[] moved = compiled(minecraft.level, mover, placement, from, to);
                    if (moved != null) { from = moved[0]; to = moved[1]; }
                }
                double fx = from.x - placement.translation().getX(), fy = from.y - placement.translation().getY(), fz = from.z - placement.translation().getZ();
                Vec3 segment = to.subtract(from);
                if (!crosses(volume.bounds(), fx, fy, fz, segment)) continue;
                double fraction = volume.clip(fx, fy, fz, segment.x, segment.y, segment.z);
                if (fraction >= 0 && fraction < bestFraction) {
                    bestFraction = fraction;
                    best = new Target(anchor, i, continuous(entity));
                }
            }
        }
        if (best == null) return null;
        HitResult hit = minecraft.hitResult;
        if (hit != null && hit.getType() != HitResult.Type.MISS
            && hit.getLocation().distanceTo(eye) < bestFraction * reach - BLOCK_TOLERANCE) return null;
        return best;
    }

    /**
     * Both ends of a ray moved from the world into the frame a mover's entity was compiled in, or
     * null while its sub-level is not here. A plot point is mover-local cell coordinates from the
     * plot's centre; the compiled map-local point adds the mover's cell origin.
     */
    private static Vec3[] compiled(net.minecraft.client.multiplayer.ClientLevel level, MoverRegistry.Instance instance, MapPlacement placement,
                                   Vec3 from, Vec3 to) {
        var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
        if (container == null || !(container.getSubLevel(instance.subLevel()) instanceof dev.ryanhcode.sable.sublevel.ClientSubLevel subLevel)) return null;
        MoverRegistry.Resolved resolved = MoverRegistry.resolve(level, instance.subLevel());
        if (resolved == null) return null;
        var pose = subLevel.logicalPose();
        net.minecraft.core.BlockPos origin = MoverRegistry.plotOrigin(subLevel.getPlot());
        Vec3 shift = new Vec3(resolved.mover().originX() + placement.translation().getX() - origin.getX(),
            resolved.mover().originY() + placement.translation().getY() - origin.getY(),
            resolved.mover().originZ() + placement.translation().getZ() - origin.getZ());
        return new Vec3[]{pose.transformPositionInverse(from).add(shift), pose.transformPositionInverse(to).add(shift)};
    }

    /** Which entities Source lets the player use: the same spawnflags the server checks. */
    private static boolean usable(LogicTable.Entity entity) {
        int flags = Variant.integer(entity.value("spawnflags"));
        return switch (entity.classname()) {
            case "func_button", "func_rot_button", "momentary_rot_button" -> (flags & BUTTON_USE_ACTIVATES) != 0;
            case "func_door", "func_door_rotating", "infra_button" -> (flags & DOOR_USE_OPENS) != 0;
            default -> false;
        };
    }

    private static boolean continuous(LogicTable.Entity entity) {
        return entity.classname().equals("momentary_rot_button")
            || (entity.classname().equals("infra_button") && (Variant.integer(entity.value("spawnflags")) & INFRA_CAN_BE_HELD) != 0);
    }

    private static boolean near(MapPlacement placement, Vec3 eye, double reach) {
        return eye.x >= placement.worldMin().getX() - reach && eye.x <= placement.worldMax().getX() + 1 + reach
            && eye.y >= placement.worldMin().getY() - reach && eye.y <= placement.worldMax().getY() + 1 + reach
            && eye.z >= placement.worldMin().getZ() - reach && eye.z <= placement.worldMax().getZ() + 1 + reach;
    }

    /** Whether the ray's box overlaps the volume's bounds; most volumes fail this before their brushes are tested. */
    private static boolean crosses(double[] b, double fx, double fy, double fz, Vec3 ray) {
        return Math.max(fx, fx + ray.x) >= b[0] && Math.min(fx, fx + ray.x) <= b[3]
            && Math.max(fy, fy + ray.y) >= b[1] && Math.min(fy, fy + ray.y) <= b[4]
            && Math.max(fz, fz + ray.z) >= b[2] && Math.min(fz, fz + ray.z) <= b[5];
    }
}
