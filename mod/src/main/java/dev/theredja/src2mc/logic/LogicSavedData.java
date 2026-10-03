package dev.theredja.src2mc.logic;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The started maps of one dimension and their logic state, saved with it. A loaded state waits in
 * {@link #pending} until its placement and bundle are both loaded; a running map is saved from
 * its live state.
 */
final class LogicSavedData extends SavedData {
    private static final String FILE = "src2mc_logic";
    private final Map<Long, CompoundTag> pending = new HashMap<>();
    private ServerLevel level;

    static LogicSavedData get(ServerLevel level) {
        LogicSavedData data = level.getDataStorage().computeIfAbsent(new Factory<>(LogicSavedData::new, LogicSavedData::load), FILE);
        data.level = level;
        return data;
    }

    private static LogicSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        LogicSavedData data = new LogicSavedData();
        CompoundTag maps = tag.getCompound("maps");
        for (String key : maps.getAllKeys()) {
            try {
                data.pending.put(Long.parseLong(key), maps.getCompound(key));
            } catch (NumberFormatException ignored) {
                // Not one of ours.
            }
        }
        return data;
    }

    Map<Long, CompoundTag> pending() { return pending; }

    void store(long anchor, CompoundTag state) {
        pending.put(anchor, state);
        setDirty();
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag maps = new CompoundTag();
        pending.forEach((anchor, state) -> maps.put(Long.toString(anchor), state));
        if (level != null) for (MapLogic logic : LogicSystem.runningMaps(level)) maps.put(Long.toString(logic.placement.anchorWorld().asLong()), logic.save());
        tag.put("maps", maps);
        return tag;
    }
}
