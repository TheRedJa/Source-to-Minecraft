package dev.theredja.src2mc.client.audio;

import dev.theredja.src2mc.bundle.AudioTable;
import dev.theredja.src2mc.bundle.LogicTable;
import dev.theredja.src2mc.client.logic.CaptionOverlay;
import dev.theredja.src2mc.client.logic.ClientLogic;
import dev.theredja.src2mc.logic.LogicNetwork;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * The sounds the map's logic plays: one-shots the server sends (a button's click, a door, a line
 * of a scene, with its caption) and INFRA's music, which plays as the server's copy of each
 * {@code infra_music} says.
 *
 * <p>A line a player speaks is heard by that player as their own voice, everywhere at once, and
 * by others from where that player stands. A caption shows only for a sound that can be heard.
 */
final class LogicSounds {
    private final SoundLibrary library;
    private final RandomGenerator random = RandomGenerator.of("L64X128MixRandom");
    private final Map<Long, SourceSound> oneShots = new HashMap<>();
    private final Map<Key, Music> music = new HashMap<>();
    private long played, captions, missing;

    LogicSounds(SoundLibrary library) { this.library = library; }

    /** An {@code infra_music} entity of one placed map. */
    record MusicEntity(MapSound map, int entity) {}

    private record Key(dev.theredja.src2mc.world.MapPlacement placement, int entity) {}

    private static final class Music {
        SourceSound voice;
        int serial = -1;
        float level;
    }

    String status() {
        long playingMusic = music.values().stream().filter(entry -> entry.voice != null && Voices.active(entry.voice)).count();
        return "one-shots " + played + " (" + oneShots.size() + " playing, " + missing + " missing), captions " + captions
            + ", music playing " + playingMusic;
    }

    void tick(ResourceLocation dimension, Vec3 listener, List<MapSound> maps, List<MusicEntity> musicEntities) {
        for (ClientLogic.Pending pending : ClientLogic.drain()) {
            if (!pending.dimension().equals(dimension)) continue;
            MapSound map = find(maps, pending.anchor());
            if (pending.event() == null) {
                SourceSound voice = oneShots.remove(pending.stop());
                if (voice != null) Voices.stop(voice);
            } else if (map != null) {
                play(map, pending.event(), listener);
            }
        }
        for (Iterator<SourceSound> voices = oneShots.values().iterator(); voices.hasNext(); ) {
            if (!Voices.active(voices.next())) voices.remove();
        }
        for (MusicEntity entity : musicEntities) tickMusic(dimension, entity);
    }

    private static MapSound find(List<MapSound> maps, long anchor) {
        for (MapSound map : maps) if (map.placement().anchorWorld().asLong() == anchor) return map;
        return null;
    }

    private void play(MapSound map, LogicNetwork.SoundEvent event, Vec3 listener) {
        AudioTable.Script script = map.audio().script(event.script());
        if (script == null) { missing++; return; }
        AudioTable.Sound sound = map.sound(script.sound(random.nextInt(script.soundCount())));
        Vec3 at = event.at() == null ? null : map.world(event.at());
        Minecraft minecraft = Minecraft.getInstance();
        if (event.speaker() != null && minecraft.player != null && !event.speaker().equals(minecraft.player.getUUID())) {
            Player speaker = minecraft.level == null ? null : minecraft.level.getPlayerByUUID(event.speaker());
            at = speaker == null ? null : speaker.getEyePosition();
        }
        double soundLevel = at == null ? 0 : script.soundLevel().sample(random);
        float volume = (float) script.volume().sample(random) * (event.volume() >= 0 ? event.volume() : 1);
        SoundSource category = event.voice() ? SoundSource.VOICE : SoundSource.BLOCKS;
        SourceSound voice = new SourceSound(library, map.bundle(), sound, category, at, soundLevel, volume,
            (int) script.pitch().sample(random) / 100.0F, false);
        if (Voices.play(voice)) {
            played++;
            oneShots.put(event.id(), voice);
        }
        caption(map, event, sound, at, soundLevel, listener);
    }

    private void caption(MapSound map, LogicNetwork.SoundEvent event, AudioTable.Sound sound, Vec3 at, double soundLevel, Vec3 listener) {
        LogicTable logic = map.map().logic();
        if (event.caption() == null || logic == null) return;
        if (at != null && soundLevel > 0 && SourceFalloff.gain(soundLevel, listener.distanceTo(at)) <= 0.01) return;
        String text = logic.caption(event.caption());
        if (text == null) return;
        double seconds = event.duration() > 0 ? event.duration() : sound.frames() / (double) sound.sampleRate();
        CaptionOverlay.show(text, seconds);
        captions++;
    }

    private void tickMusic(ResourceLocation dimension, MusicEntity entity) {
        long anchor = entity.map().placement().anchorWorld().asLong();
        LogicNetwork.SoundState state = ClientLogic.state(dimension, anchor, entity.entity());
        Music current = music.computeIfAbsent(new Key(entity.map().placement(), entity.entity()), ignored -> new Music());
        boolean on = state != null && state.on() && state.sound() >= 0 && state.sound() < entity.map().audio().sounds().size();
        if (!on || state.serial() != current.serial) {
            if (current.voice != null) Voices.stop(current.voice);
            current.voice = null;
        }
        if (!on) { current.serial = -1; return; }
        if (current.serial != state.serial()) {
            current.serial = state.serial();
            AudioTable.Script script = entity.map().audio().script(
                dev.theredja.src2mc.logic.LogicNames.sound(entity.map().map().logic().entities().get(entity.entity()).value("sound")));
            current.level = script == null ? 1 : (float) script.volume().sample(random);
            AudioTable.Sound sound = entity.map().sound(state.sound());
            SourceSound voice = new SourceSound(library, entity.map().bundle(), sound, SoundSource.MUSIC, null, 0,
                current.level * state.volume(), 1.0F, false);
            if (Voices.play(voice)) {
                current.voice = voice;
                // The map's own music replaces Minecraft's while it plays.
                Minecraft.getInstance().getMusicManager().stopPlaying();
            }
        }
        if (current.voice != null) current.voice.setLevel(current.level * Math.max(0, state.volume()));
    }

    void stop() {
        for (SourceSound voice : oneShots.values()) Voices.stop(voice);
        oneShots.clear();
        for (Music entry : music.values()) if (entry.voice != null) Voices.stop(entry.voice);
        music.clear();
    }
}
