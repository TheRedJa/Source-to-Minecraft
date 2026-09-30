package dev.theredja.src2mc.client.render;

import dev.theredja.src2mc.bundle.BundleGeneration;
import dev.theredja.src2mc.bundle.BundleMap;
import dev.theredja.src2mc.bundle.PropVisibility;
import dev.theredja.src2mc.network.PlacementNetwork;
import dev.theredja.src2mc.world.MapPlacement;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * Resolves which map placement the camera is currently inside and that
 * placement's PVS row/cluster. The placement is looked up once per camera block move; the
 * cluster is walked from the eye's exact position whenever it moves. Shared by every
 * mod-owned renderer that wants to reject sections/props/faces the current
 * BSP leaf can't see; the resolution itself is placement-level, not tied to
 * props or surfaces specifically.
 *
 * <p>Every unresolved case — camera outside any map placement, no visibility
 * table, solid leaf, or unknown cluster — leaves the row null and callers
 * must render without PVS rejection (fail open).
 */
final class CameraVisibility {
    private static PropVisibility table;
    private static MapPlacement placement;
    private static byte[] row;
    private static int cluster = -1;
    private static int cameraX = Integer.MIN_VALUE;
    private static int cameraY = Integer.MIN_VALUE;
    private static int cameraZ = Integer.MIN_VALUE;
    /** Placement and table for the cached camera block; the cluster is re-resolved from the eye. */
    private static MapPlacement blockPlacement;
    private static PropVisibility blockTable;
    private static double eyeX = Double.NaN;
    private static double eyeY = Double.NaN;
    private static double eyeZ = Double.NaN;

    private CameraVisibility() {}

    static void resolve(BundleGeneration generation, Minecraft minecraft) {
        var camera = minecraft.gameRenderer.getMainCamera().getPosition();
        if (camera.x == eyeX && camera.y == eyeY && camera.z == eyeZ) return;
        eyeX = camera.x; eyeY = camera.y; eyeZ = camera.z;
        BlockPos cameraBlock = BlockPos.containing(camera.x, camera.y, camera.z);
        if (cameraBlock.getX() != cameraX || cameraBlock.getY() != cameraY || cameraBlock.getZ() != cameraZ) {
            cameraX = cameraBlock.getX(); cameraY = cameraBlock.getY(); cameraZ = cameraBlock.getZ();
            blockPlacement = PlacementNetwork.clientIndex(minecraft.level.dimension().location()).at(cameraBlock).orElse(null);
            BundleMap map = blockPlacement == null ? null
                : generation.findMap(blockPlacement.campaignId(), blockPlacement.mapId()).orElse(null);
            blockTable = map == null ? null : map.pvs();
        }
        table = null; placement = null; row = null; cluster = -1;
        if (blockPlacement == null || blockTable == null) return;
        // BSP planes are in continuous map-local block coordinates, so walk the tree with the
        // eye itself: the block corner can sit in a neighbouring leaf or exactly on a plane.
        BlockPos translation = blockPlacement.translation();
        int resolvedCluster = blockTable.clusterAt(camera.x - translation.getX(),
            camera.y - translation.getY(), camera.z - translation.getZ());
        if (resolvedCluster < 0) return;
        byte[] resolvedRow = blockTable.row(resolvedCluster);
        if (resolvedRow == null) return;
        table = blockTable; placement = blockPlacement; row = resolvedRow; cluster = resolvedCluster;
    }

    static void reset() {
        table = null; placement = null; row = null; cluster = -1;
        cameraX = Integer.MIN_VALUE; cameraY = Integer.MIN_VALUE; cameraZ = Integer.MIN_VALUE;
        blockPlacement = null; blockTable = null;
        eyeX = Double.NaN; eyeY = Double.NaN; eyeZ = Double.NaN;
    }

    static PropVisibility table() { return table; }
    static MapPlacement placement() { return placement; }
    static byte[] row() { return row; }
    static int cluster() { return cluster; }
}
