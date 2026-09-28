package dev.theredja.src2mc.client;

import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.client.render.MapSurfaceRenderer;
import dev.theredja.src2mc.network.PlacementNetwork;
import dev.theredja.src2mc.world.LightOcclusion;
import dev.theredja.src2mc.world.MapPlacement;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;

/**
 * Redraws the chunk whose sky light we just replaced.
 *
 * Vanilla rebuilds a section's mesh when its light changes because it is told
 * so by a light update; light handed straight to the engine arrives without
 * one, so the blocks in a placed map would keep the shading they were built
 * with until something else disturbed them. The mod's own surface and prop
 * renderers sample light per frame and need no help.
 *
 * It is also where the surface renderer hears about {@code src2mc:surface}
 * blocks it did not see when it built: server-reported changes, and chunks
 * that arrived after the region covering them was built.
 */
@EventBusSubscriber(modid = Src2mc.MOD_ID, value = Dist.CLIENT)
public final class ClientLightRefresh {
    private ClientLightRefresh() {}

    /**
     * A chunk arriving from the server carries the light the server had when it
     * was sent, which for a chunk loaded before the bake finished is vanilla's.
     */
    @SubscribeEvent
    static void onChunkLoad(ChunkEvent.Load event) {
        if (!event.getLevel().isClientSide() || !(event.getLevel() instanceof net.minecraft.world.level.Level level)) return;
        LightOcclusion.applyChunk(level, event.getChunk().getPos().x, event.getChunk().getPos().z);
        // A region built before this chunk arrived took its owned cells as present.
        if (level instanceof net.minecraft.client.multiplayer.ClientLevel client
            && event.getChunk() instanceof net.minecraft.world.level.chunk.LevelChunk chunk) {
            MapSurfaceRenderer.chunkLoaded(client, chunk);
            MapSurfaceRenderer.checkOwnedCells(client, chunk);
        }
    }

    @SubscribeEvent
    static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof net.minecraft.client.multiplayer.ClientLevel client) {
            MapSurfaceRenderer.chunkUnloaded(client, event.getChunk().getPos());
        }
    }

    /** The server saw {@code src2mc:surface} blocks appear or vanish in these world sections. */
    public static void onSurfaceBlocksChanged(net.minecraft.resources.ResourceLocation dimension, long[] sections) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !minecraft.level.dimension().location().equals(dimension)) return;
        var placements = PlacementNetwork.clientIndex(dimension).view();
        if (placements.isEmpty()) return;
        for (long packed : sections) {
            SectionPos section = SectionPos.of(packed);
            int minX = section.minBlockX(), minY = section.minBlockY(), minZ = section.minBlockZ();
            for (MapPlacement placement : placements) {
                BlockPos lo = placement.worldMin(), hi = placement.worldMax();
                if (minX > hi.getX() || minX + 15 < lo.getX() || minY > hi.getY() || minY + 15 < lo.getY()
                    || minZ > hi.getZ() || minZ + 15 < lo.getZ()) continue;
                MapSurfaceRenderer.invalidateSurfaceSection(placement, section);
            }
        }
    }

    public static void markChunkDirty(int chunkX, int chunkZ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        for (int sectionY = minecraft.level.getMinSection(); sectionY < minecraft.level.getMaxSection(); sectionY++) {
            minecraft.levelRenderer.setSectionDirtyWithNeighbors(chunkX, sectionY, chunkZ);
        }
    }
}
