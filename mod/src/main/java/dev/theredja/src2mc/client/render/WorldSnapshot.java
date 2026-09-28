package dev.theredja.src2mc.client.render;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;

/**
 * A frozen copy of the block states and light around one mesh build, so the build can run on a
 * worker thread while the render thread goes on changing the level. Sodium does the same with its
 * chunk-build world slices; reading the live {@link ClientLevel} from another thread would race
 * with chunk loads and light updates.
 *
 * Taken on the render thread. Section copies are shared through {@link #SHARED} by the builds both
 * renderers start in one frame, and dropped when the next frame begins: nothing changes the level
 * inside a frame, and across frames no event reliably covers every change. An earlier version
 * kept copies for a second and dropped them on chunk, light and surface events; on joining a
 * world it still served copies taken before chunks arrived, and whole regions were built with
 * their surface blocks missing. A copy is never written again, so handing it to several workers
 * is safe.
 *
 * Light is read the way the engine reads it. A section with a data layer answers from a copy of
 * that layer. A section without one answers what the engine answers for it: nothing for block
 * light, and for sky light the bottom row of the next lit section above, which is one value per
 * column. That column is read through the engine itself, 256 queries, rather than reimplemented.
 */
final class WorldSnapshot implements BlockAndTintGetter {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private final int minSectionX, minSectionY, minSectionZ, sizeX, sizeY, sizeZ;
    private final Section[] sections;
    /** Whether each chunk column of the box was loaded when the copy was taken. */
    private final boolean[] chunks;
    private final int minBuildHeight, height;

    /** One section's copy. {@code states} is null for a section of only air. */
    record Section(PalettedContainer<BlockState> states, Light sky, Light block) {}

    /** A data layer's copy, or one value per column where the engine has no layer. */
    record Light(DataLayer layer, byte[] columns) {
        int get(int x, int y, int z) {
            return layer != null ? layer.get(x & 15, y & 15, z & 15) : columns[(z & 15) << 4 | (x & 15)];
        }
    }

    static final Copies SHARED = new Copies();

    /** Section copies shared by the builds of one frame; render thread only. */
    static final class Copies {
        private final Map<Long, Section> sections = new HashMap<>();
        private ClientLevel level;
        long copied, reused;

        /** Drops every copy; called once at the start of each frame. */
        void newFrame() { sections.clear(); }

        /** Called before capturing; drops everything when the level changed. */
        void use(ClientLevel current) {
            if (current != level) { sections.clear(); level = current; }
        }

        void clear() { sections.clear(); level = null; }

        Section section(int sectionX, int sectionY, int sectionZ) {
            long key = SectionPos.asLong(sectionX, sectionY, sectionZ);
            Section taken = sections.get(key);
            if (taken != null) { reused++; return taken; }
            copied++;
            Section section = copy(level, sectionX, sectionY, sectionZ);
            sections.put(key, section);
            return section;
        }
    }

    private WorldSnapshot(int minSectionX, int minSectionY, int minSectionZ, int sizeX, int sizeY, int sizeZ,
                          Section[] sections, boolean[] chunks, int minBuildHeight, int height) {
        this.minSectionX = minSectionX; this.minSectionY = minSectionY; this.minSectionZ = minSectionZ;
        this.sizeX = sizeX; this.sizeY = sizeY; this.sizeZ = sizeZ;
        this.sections = sections; this.chunks = chunks;
        this.minBuildHeight = minBuildHeight; this.height = height;
    }

    /** Copies every section overlapping the world-space block box, bounds inclusive. */
    static WorldSnapshot capture(ClientLevel level, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        int minSectionX = SectionPos.blockToSectionCoord(minX), maxSectionX = SectionPos.blockToSectionCoord(maxX);
        int minSectionY = SectionPos.blockToSectionCoord(minY), maxSectionY = SectionPos.blockToSectionCoord(maxY);
        int minSectionZ = SectionPos.blockToSectionCoord(minZ), maxSectionZ = SectionPos.blockToSectionCoord(maxZ);
        Copies copies = SHARED;
        copies.use(level);
        int sizeX = maxSectionX - minSectionX + 1, sizeY = maxSectionY - minSectionY + 1, sizeZ = maxSectionZ - minSectionZ + 1;
        Section[] sections = new Section[sizeX * sizeY * sizeZ];
        boolean[] chunks = new boolean[sizeX * sizeZ];
        for (int x = 0; x < sizeX; x++) {
            for (int z = 0; z < sizeZ; z++) {
                chunks[x * sizeZ + z] = level.hasChunk(minSectionX + x, minSectionZ + z);
                for (int y = 0; y < sizeY; y++) {
                    sections[(x * sizeY + y) * sizeZ + z] = copies.section(minSectionX + x, minSectionY + y, minSectionZ + z);
                }
            }
        }
        return new WorldSnapshot(minSectionX, minSectionY, minSectionZ, sizeX, sizeY, sizeZ, sections, chunks,
            level.getMinBuildHeight(), level.getHeight());
    }

    private static Section copy(ClientLevel level, int sectionX, int sectionY, int sectionZ) {
        PalettedContainer<BlockState> states = null;
        LevelChunk chunk = level.getChunkSource().getChunk(sectionX, sectionZ, ChunkStatus.FULL, false);
        int index = level.getSectionIndexFromSectionY(sectionY);
        if (chunk != null && index >= 0 && index < chunk.getSections().length) {
            LevelChunkSection section = chunk.getSections()[index];
            if (section != null && !section.hasOnlyAir()) states = section.getStates().copy();
        }
        LevelLightEngine engine = level.getLightEngine();
        SectionPos at = SectionPos.of(sectionX, sectionY, sectionZ);
        return new Section(states, light(engine.getLayerListener(LightLayer.SKY), at),
            light(engine.getLayerListener(LightLayer.BLOCK), at));
    }

    private static Light light(LayerLightEventListener listener, SectionPos at) {
        DataLayer layer = listener.getDataLayerData(at);
        if (layer != null) return new Light(layer.copy(), null);
        byte[] columns = new byte[256];
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                cursor.set(at.minBlockX() + x, at.minBlockY(), at.minBlockZ() + z);
                columns[z << 4 | x] = (byte) listener.getLightValue(cursor);
            }
        }
        return new Light(null, columns);
    }

    private Section sectionAt(int x, int y, int z) {
        int sx = SectionPos.blockToSectionCoord(x) - minSectionX;
        int sy = SectionPos.blockToSectionCoord(y) - minSectionY;
        int sz = SectionPos.blockToSectionCoord(z) - minSectionZ;
        if (sx < 0 || sy < 0 || sz < 0 || sx >= sizeX || sy >= sizeY || sz >= sizeZ) return null;
        return sections[(sx * sizeY + sy) * sizeZ + sz];
    }

    /** Whether the chunk column holding world (x, z) was loaded, as {@code Level#hasChunk} said. */
    boolean hasChunkAt(int x, int z) {
        int cx = SectionPos.blockToSectionCoord(x) - minSectionX, cz = SectionPos.blockToSectionCoord(z) - minSectionZ;
        return cx >= 0 && cz >= 0 && cx < sizeX && cz < sizeZ && chunks[cx * sizeZ + cz];
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        Section section = sectionAt(pos.getX(), pos.getY(), pos.getZ());
        if (section == null || section.states() == null) return AIR;
        return section.states().get(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) { return null; }

    @Override
    public int getBrightness(LightLayer layer, BlockPos pos) {
        Section section = sectionAt(pos.getX(), pos.getY(), pos.getZ());
        if (section == null) return layer == LightLayer.SKY ? 15 : 0;
        return (layer == LightLayer.SKY ? section.sky() : section.block()).get(pos.getX(), pos.getY(), pos.getZ());
    }

    @Override
    public int getRawBrightness(BlockPos pos, int darkening) {
        return Math.max(getBrightness(LightLayer.SKY, pos) - darkening, getBrightness(LightLayer.BLOCK, pos));
    }

    /** Never handed out: every light read goes through {@link #getBrightness}, which the copy answers. */
    @Override
    public LevelLightEngine getLightEngine() {
        throw new UnsupportedOperationException("a world snapshot has no light engine");
    }

    @Override
    public float getShade(Direction direction, boolean shade) { return 1.0f; }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver resolver) { return -1; }

    @Override
    public int getHeight() { return height; }

    @Override
    public int getMinBuildHeight() { return minBuildHeight; }
}
