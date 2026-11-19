package dev.theredja.src2mc.client.view;

import dev.theredja.src2mc.network.PlacementNetwork;
import dev.theredja.src2mc.world.MapPlacement;
import dev.theredja.src2mc.view.ViewNetwork;
import dev.theredja.src2mc.view.ViewState;
import dev.theredja.src2mc.world.PropStates;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.joml.Quaternionf;
import org.joml.Vector3d;

/**
 * The camera entities the server networks ({@link ViewState}), per placed map, and where their cameras are this
 * frame. A moving camera's pose arrives every tick it moves; frames between two arrivals interpolate between them.
 */
public final class ViewClient {
    private ViewClient() {}

    /** A map's state, the game tick it was sent at, and the one before it (for interpolating moving cameras). */
    record Received(ViewState state, long tick, ViewState previous, long previousTick) {}

    private static final Map<PropStates.Key, Received> STATES = new HashMap<>();

    public static void receive(ViewNetwork.StatePayload payload) {
        PropStates.Key key = new PropStates.Key(payload.dimension(), payload.anchor());
        if (payload.state() == null) {
            STATES.remove(key);
            return;
        }
        Received old = STATES.get(key);
        STATES.put(key, new Received(payload.state(), payload.tick(), old == null ? null : old.state, old == null ? 0 : old.tick));
    }

    public static void clear() { STATES.clear(); }

    /** A camera's world pose this frame: eye position in world blocks and its rotation in Minecraft's axes. */
    public record WorldPose(Vector3d position, Quaternionf rotation) {}

    /** A map's state with the placement it belongs to. */
    public record Placed(MapPlacement placement, ViewState state, Received received) {}

    /** The game time of this frame in ticks, the clock the server's times are in. */
    public static double now(float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level == null ? 0 : minecraft.level.getGameTime() + partialTick;
    }

    /**
     * The state of the placed map the player is in (else the first with a camera state) in the player's dimension,
     * or null when none.
     */
    public static Placed current() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || STATES.isEmpty()) return null;
        ResourceLocation dimension = minecraft.level.dimension().location();
        BlockPos at = minecraft.player == null ? BlockPos.ZERO : minecraft.player.blockPosition();
        Placed chosen = null;
        for (MapPlacement placement : PlacementNetwork.clientIndex(dimension).view()) {
            Received received = STATES.get(new PropStates.Key(dimension, placement.anchorWorld().asLong()));
            if (received == null) continue;
            if (chosen == null || placement.contains(at)) chosen = new Placed(placement, received.state, received);
        }
        return chosen;
    }

    /** The states of every placed map in the player's dimension. */
    public static java.util.List<Placed> all() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || STATES.isEmpty()) return java.util.List.of();
        ResourceLocation dimension = minecraft.level.dimension().location();
        java.util.List<Placed> out = new java.util.ArrayList<>();
        for (MapPlacement placement : PlacementNetwork.clientIndex(dimension).view()) {
            Received received = STATES.get(new PropStates.Key(dimension, placement.anchorWorld().asLong()));
            if (received != null) out.add(new Placed(placement, received.state, received));
        }
        return out;
    }

    /** A dynamic light's world position and direction this frame, between the last two states as camera poses are. */
    public static WorldLight light(Placed placed, ViewState.Light light, float partialTick) {
        ViewState.Light before = null;
        if (placed.received.previous != null) {
            for (ViewState.Light old : placed.received.previous.lights()) if (old.entity() == light.entity()) before = old;
        }
        double t = 1;
        long gap = placed.received.tick - placed.received.previousTick;
        if (before != null && gap > 0 && gap <= 2) t = Math.min(1, Math.max(0, (now(partialTick) - placed.received.tick) / gap));
        ViewState.Light from = before == null ? light : before;
        BlockPos translation = placed.placement.translation();
        Vector3d position = new Vector3d(from.x() + (light.x() - from.x()) * t, from.y() + (light.y() - from.y()) * t,
            from.z() + (light.z() - from.z()) * t).add(translation.getX(), translation.getY(), translation.getZ());
        Vector3d direction = new Vector3d(from.dirX() + (light.dirX() - from.dirX()) * t, from.dirY() + (light.dirY() - from.dirY()) * t,
            from.dirZ() + (light.dirZ() - from.dirZ()) * t);
        if (direction.lengthSquared() < 1e-12) direction.set(light.dirX(), light.dirY(), light.dirZ());
        direction.normalize();
        return new WorldLight(position, direction);
    }

    /** A dynamic light's world position in blocks and unit direction in Minecraft's axes. */
    public record WorldLight(Vector3d position, Vector3d direction) {}

    /** The active {@code script_intro} of the current map, or null. */
    public static ViewState.Intro activeIntro() {
        Placed placed = current();
        return placed == null || placed.state.intro() == null || !placed.state.intro().active() ? null : placed.state.intro();
    }

    /** Where the intro's camera is this frame, or null when it has none. */
    public static WorldPose introCamera(Placed placed, float partialTick) {
        ViewState.Intro intro = placed.state.intro();
        if (intro == null || intro.camera() == null) return null;
        ViewState.Pose before = placed.received.previous == null || placed.received.previous.intro() == null ? null
            : placed.received.previous.intro().camera();
        return interpolate(placed, before, intro.camera(), partialTick);
    }

    /** Where camera entity {@code camera} is this frame. */
    public static WorldPose cameraPose(Placed placed, ViewState.Camera camera, float partialTick) {
        ViewState.Pose before = null;
        if (placed.received.previous != null) {
            for (ViewState.Camera old : placed.received.previous.cameras()) if (old.entity() == camera.entity()) before = old.pose();
        }
        return interpolate(placed, before, camera.pose(), partialTick);
    }

    /**
     * {@code now} between two poses sent a tick or more apart: the newer is reached one tick after it was sent,
     * the server's own pace; with no older pose, or one sent long before, the newer one as it is.
     */
    private static WorldPose interpolate(Placed placed, ViewState.Pose before, ViewState.Pose after, float partialTick) {
        double t = 1;
        long gap = placed.received.tick - placed.received.previousTick;
        if (before != null && gap > 0 && gap <= 2) t = Math.min(1, Math.max(0, (now(partialTick) - placed.received.tick) / gap));
        ViewState.Pose from = before == null ? after : before;
        BlockPos translation = placed.placement.translation();
        Vector3d position = new Vector3d(from.x() + (after.x() - from.x()) * t, from.y() + (after.y() - from.y()) * t,
            from.z() + (after.z() - from.z()) * t).add(translation.getX(), translation.getY(), translation.getZ());
        Quaternionf rotation = new Quaternionf(from.qx(), from.qy(), from.qz(), from.qw())
            .slerp(new Quaternionf(after.qx(), after.qy(), after.qz(), after.qw()), (float) t);
        return new WorldPose(position, rotation);
    }
}
