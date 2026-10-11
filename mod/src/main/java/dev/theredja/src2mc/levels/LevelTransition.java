package dev.theredja.src2mc.levels;

import java.util.Locale;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;

/**
 * A move of the player into the Minecraft world of another Source map (one world per map). The
 * integrated server of the old world records it here before the client switches worlds, and the
 * server of the new world takes it when the player joins. Singleplayer only: both servers run in
 * this JVM, one after the other.
 */
public final class LevelTransition {
    /** How the player enters the next map. */
    public enum Kind {
        /** A {@code trigger_changelevel}: logic starts as a transition, the player keeps their place at the landmark. */
        CHANGE_LEVEL,
        /** A chapter started from the menu: logic starts as a new game, the player spawns at {@code info_player_start}. */
        NEW_GAME,
        /** The menu's background map. */
        MENU
    }

    /**
     * @param landmark  {@code info_landmark} shared by both maps (CHANGE_LEVEL only)
     * @param offset    player position minus the landmark's world position in the old world, in blocks (CHANGE_LEVEL only)
     * @param player    the old world's player data ({@code ServerPlayer.saveWithoutId}), or null to keep the new world's player
     */
    public record Pending(Kind kind, String campaignId, String mapId, String landmark, Vec3 offset,
                          float yRot, float xRot, Vec3 motion, CompoundTag player) {}

    /** A pending transition nobody took in this long is stale (the switch never happened) and counts as absent. */
    private static final long EXPIRY_NANOS = 120_000_000_000L;

    private static volatile Pending pending;
    private static volatile long createdNanos;

    private LevelTransition() {}

    public static void set(Pending transition) {
        createdNanos = System.nanoTime();
        pending = transition;
    }

    /** The pending transition, cleared; null when none or when it has expired. */
    public static Pending take() {
        Pending taken = peek();
        pending = null;
        return taken;
    }

    /** The pending transition, or null when none or when it has expired. */
    public static Pending peek() {
        Pending current = pending;
        if (current != null && System.nanoTime() - createdNanos > EXPIRY_NANOS) {
            pending = null;
            return null;
        }
        return current;
    }

    /** The save folder of the world holding {@code mapId} of {@code campaignId}. */
    public static String folderName(String campaignId, String mapId) {
        String name = campaignId.equalsIgnoreCase(mapId) ? "src2mc " + mapId : "src2mc " + campaignId + " " + mapId;
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_ .-]", "_");
    }
}
