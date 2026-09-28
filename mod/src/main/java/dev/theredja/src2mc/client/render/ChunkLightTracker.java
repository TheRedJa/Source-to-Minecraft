package dev.theredja.src2mc.client.render;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * Which loaded chunks are still waiting for their light.
 *
 * A chunk arrives with its light in one packet, but the client applies the two apart: the blocks
 * at once, the light through {@link ClientLevel#queueLightUpdate}, which drains a tenth of its
 * queue per frame. A mesh built in between samples the light of a chunk that has none yet, and
 * is rebuilt the moment the light lands. On joining a world that is every mesh, several times.
 *
 * NeoForge posts the client's chunk load event from {@code ClientChunkCache#replaceWithPacketData},
 * before {@code ClientPacketListener#handleLevelChunkWithLight} queues the chunk's light. A marker
 * queued from the event therefore runs ahead of that light; the marker queues a second one, which
 * lands behind it, and that one clears the chunk. A reload of the same chunk takes a new token,
 * so an older marker cannot clear it early. Render thread only.
 */
final class ChunkLightTracker {
    private static final Long2IntOpenHashMap PENDING = new Long2IntOpenHashMap();
    private static ClientLevel level;
    private static int nextToken;

    private ChunkLightTracker() {}

    static void onChunkLoad(ClientLevel chunkLevel, ChunkPos pos) {
        if (chunkLevel != level) { PENDING.clear(); level = chunkLevel; }
        long key = pos.toLong();
        int token = ++nextToken;
        PENDING.put(key, token);
        chunkLevel.queueLightUpdate(() -> chunkLevel.queueLightUpdate(() -> {
            if (level != chunkLevel || PENDING.get(key) != token) return;
            PENDING.remove(key);
        }));
    }

    static void onChunkUnload(ClientLevel chunkLevel, ChunkPos pos) {
        if (chunkLevel == level) PENDING.remove(pos.toLong());
    }

    /** Whether the chunk is loaded in {@code current} and its light has been applied. */
    static boolean ready(ClientLevel current, int chunkX, int chunkZ) {
        if (!current.hasChunk(chunkX, chunkZ)) return false;
        return current != level || !PENDING.containsKey(ChunkPos.asLong(chunkX, chunkZ));
    }

    static int pending() { return PENDING.size(); }

    /**
     * Whether every chunk a build over the world block box reads is ready. A chunk outside the
     * render distance around the camera's chunk is not waited for: the server may never send it.
     */
    static boolean settled(ClientLevel current, int minX, int minZ, int maxX, int maxZ,
                           int cameraChunkX, int cameraChunkZ, int distance) {
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                if (Math.max(Math.abs(cx - cameraChunkX), Math.abs(cz - cameraChunkZ)) > distance) continue;
                if (!ready(current, cx, cz)) return false;
            }
        }
        return true;
    }
}
