package dev.theredja.src2mc.world;

import dev.theredja.src2mc.network.SurfaceChangePayload;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Collects the world sections whose {@code src2mc:surface} blocks appeared or disappeared and
 * tells the clients in that dimension, so they rebuild the surface regions that draw them.
 *
 * Deduplicated per section: {@code /src2mc place} and the reconciler write hundreds of
 * thousands of blocks, and one entry per block would flood the connection. A batch is held back
 * one extra tick before it is sent. Block changes reach clients through the chunk broadcast in
 * the next level tick, while the placer writes in the post-tick phase, after that broadcast; a
 * batch sent at the end of the tick it was marked in could overtake the block updates it
 * describes, and the client would rebuild against the old blocks and never hear of it again.
 * Between two post-tick phases there is always a full level tick, so the second one is safe.
 */
public final class SurfaceChangeTracker {
    private static final Map<ResourceKey<Level>, Batches> DIRTY = new HashMap<>();

    private SurfaceChangeTracker() {}

    private static final class Batches {
        LongOpenHashSet marking = new LongOpenHashSet();
        LongOpenHashSet ready = new LongOpenHashSet();
    }

    static void mark(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel)) return;
        long section = SectionPos.asLong(SectionPos.blockToSectionCoord(pos.getX()),
            SectionPos.blockToSectionCoord(pos.getY()), SectionPos.blockToSectionCoord(pos.getZ()));
        // Block changes happen on the server thread; the lock is uncontended and only guards
        // against a mod writing blocks from elsewhere.
        synchronized (DIRTY) {
            DIRTY.computeIfAbsent(level.dimension(), ignored -> new Batches()).marking.add(section);
        }
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        synchronized (DIRTY) {
            if (DIRTY.isEmpty()) return;
            var iterator = DIRTY.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                Batches batches = entry.getValue();
                if (!batches.ready.isEmpty()) {
                    ServerLevel level = event.getServer().getLevel(entry.getKey());
                    if (level != null) send(level, batches.ready.toLongArray());
                    batches.ready.clear();
                }
                LongOpenHashSet swap = batches.ready;
                batches.ready = batches.marking;
                batches.marking = swap;
                if (batches.ready.isEmpty()) iterator.remove();
            }
        }
    }

    private static void send(ServerLevel level, long[] sections) {
        for (int from = 0; from < sections.length; from += SurfaceChangePayload.MAX_SECTIONS) {
            int to = Math.min(sections.length, from + SurfaceChangePayload.MAX_SECTIONS);
            PacketDistributor.sendToPlayersInDimension(level,
                new SurfaceChangePayload(level.dimension().location(), java.util.Arrays.copyOfRange(sections, from, to)));
        }
    }

    public static void clear() {
        synchronized (DIRTY) { DIRTY.clear(); }
    }
}
