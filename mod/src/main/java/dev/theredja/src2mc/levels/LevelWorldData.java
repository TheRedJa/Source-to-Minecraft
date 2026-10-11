package dev.theredja.src2mc.levels;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** Which Source map a Minecraft world was made for (one world per map); blank until claimed. */
public final class LevelWorldData extends SavedData {
    private static final String FILE = "src2mc_level_world";
    private String campaignId = "";
    private String mapId = "";

    public static LevelWorldData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new Factory<>(LevelWorldData::new, LevelWorldData::load), FILE);
    }

    private static LevelWorldData load(CompoundTag tag, HolderLookup.Provider registries) {
        LevelWorldData data = new LevelWorldData();
        data.campaignId = tag.getString("campaign_id");
        data.mapId = tag.getString("map_id");
        return data;
    }

    public boolean claimed() { return !mapId.isEmpty(); }

    public String campaignId() { return campaignId; }

    public String mapId() { return mapId; }

    public boolean matches(String campaignId, String mapId) {
        return claimed() && this.campaignId.equals(campaignId) && this.mapId.equalsIgnoreCase(mapId);
    }

    public void claim(String campaignId, String mapId) {
        this.campaignId = campaignId;
        this.mapId = mapId;
        setDirty();
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putString("campaign_id", campaignId);
        tag.putString("map_id", mapId);
        return tag;
    }
}
