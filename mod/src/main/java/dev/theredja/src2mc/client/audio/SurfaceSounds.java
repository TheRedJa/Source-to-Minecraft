package dev.theredja.src2mc.client.audio;

import dev.theredja.src2mc.bundle.AudioTable;
import dev.theredja.src2mc.bundle.BundleMap;
import dev.theredja.src2mc.bundle.BundleMaterial;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Inside a map, movement sounds are Source's alone, and blocks sound like the map's surfaces.
 *
 * <p>Every step Minecraft plays inside a placed map -- on a map block or any other -- becomes
 * the step of the surface underfoot: its {@code $surfaceprop} names a surface property, and that
 * names alternating left and right soundscripts. Where no surface is found, Source's
 * {@code default} property plays. Minecraft's landing and fall-damage sounds are dropped; a
 * jump and a hard landing play steps instead, as {@code CGameMovement} does
 * ({@link #jump}, {@link #land}).
 *
 * <p>Minecraft decides when a step sounds, about every 1.7 blocks walked: 0.39 s walking, 0.30 s
 * sprinting, close to Source's 0.4 s and 0.3 s. The volume is Source's ({@code CBasePlayer::
 * UpdateStepSound}): 0.2 walking, 0.5 running -- sprinting here -- and 65% of that crouched,
 * times {@link #stepGain()}. Map blocks also report their own hit, break and place sounds
 * ({@code Src2mcSounds}): {@code impactsoft} for a hit, {@code break} (or {@code impacthard})
 * for a break; those keep stone's sound where no surface is found.
 */
final class SurfaceSounds {
    enum Kind { STEP, FALL, HIT, BREAK, PLACE }

    private static final float WALK_VOLUME = 0.2F, RUN_VOLUME = 0.5F, CROUCHED = 0.65F;
    /** Minecraft's own loudness for each action on a block of volume 1. */
    private static final float HIT_VOLUME = 0.25F, BREAK_VOLUME = 1.0F, PLACE_VOLUME = 1.0F;
    /**
     * {@code CheckFalling}'s thresholds as fall heights, in blocks: Source sets them as landing
     * speeds under its gravity of 600 (303 and 526.5 units per second, HL2's values), which are
     * falls of 76.5 and 231 units. Minecraft's gravity differs, so the heights are what carries over.
     */
    private static final double ROUGH_LANDING = 76.5 / SourceFalloff.UNITS_PER_BLOCK;
    private static final double HURTING_LANDING = 231.0 / SourceFalloff.UNITS_PER_BLOCK;
    /** HL2's sound for a fall that hurts. */
    private static final String FALL_DAMAGE_SCRIPT = "player.falldamage";
    /**
     * INFRA's own landing sounds. Its player code, which decides when they play, is not public;
     * they are played on the same two landings Source's thresholds tell apart, light on a rough
     * one and medium on a hurting one, which is the user's choice (2026-10-02).
     */
    private static final String FALL_LIGHT_SCRIPT = "player.falllight", FALL_MEDIUM_SCRIPT = "player.fallmedium";

    private final SoundLibrary library;
    private final RandomGenerator random = RandomGenerator.of("L64X128MixRandom");
    private final Map<Integer, Boolean> leftFoot = new HashMap<>();
    private float stepGain = 2.0F;
    private long steps, dropped, impacts, unresolved;

    SurfaceSounds(SoundLibrary library) { this.library = library; }

    String status() {
        return "steps " + steps + " (gain " + stepGain + "), Minecraft movement sounds dropped " + dropped
            + ", impacts " + impacts + ", left to stone " + unresolved;
    }

    /** Loudness on top of Source's step volumes; Source's own levels sounded too quiet in Minecraft. */
    float stepGain() { return stepGain; }
    void setStepGain(float gain) { stepGain = gain; }

    /**
     * What a sound means here, or null for one this leaves alone: the map blocks' own events,
     * and any block's step and landing, and any entity's fall damage.
     */
    static Kind kind(ResourceLocation location) {
        String path = location.getPath();
        if (location.getNamespace().equals("src2mc")) {
            return switch (path) {
                case "surface.step" -> Kind.STEP;
                case "surface.fall" -> Kind.FALL;
                case "surface.hit" -> Kind.HIT;
                case "surface.break" -> Kind.BREAK;
                case "surface.place" -> Kind.PLACE;
                default -> null;
            };
        }
        if (path.endsWith(".step")) return Kind.STEP;
        if (path.endsWith(".fall") || path.endsWith(".small_fall") || path.endsWith(".big_fall")) return Kind.FALL;
        return null;
    }

    /**
     * What to play instead of {@code original}, inside {@code maps}: a bundle sound, null to play
     * nothing, or {@code original} itself to let it play.
     */
    SoundInstance replace(SoundInstance original, Kind kind, List<MapSound> maps) {
        Vec3 at = new Vec3(original.getX(), original.getY(), original.getZ());
        switch (kind) {
            case FALL -> {
                // Landings are played from the movement itself; Minecraft's would double them.
                dropped++;
                return null;
            }
            case STEP -> {
                Entity walker = walker(at);
                float volume = walker != null && walker.isSprinting() ? RUN_VOLUME : WALK_VOLUME;
                if (walker != null && walker.isCrouching()) volume *= CROUCHED;
                SourceSound step = step(maps, at, walker, volume, original.getSource());
                if (step == null) dropped++;
                return step;
            }
            default -> {
                SourceSound impact = impact(original, kind, at, maps);
                if (impact == null) { unresolved++; return original; }
                impacts++;
                return impact;
            }
        }
    }

    /** {@code CheckJumpButton} plays a full-volume step as the player leaves the ground. */
    void jump(Entity player, List<MapSound> maps) {
        play(step(maps, player.position(), player, 1.0F, SoundSource.PLAYERS));
    }

    /**
     * {@code CheckFalling} and {@code PlayerRoughLandingEffects}: a landing after a fall of the
     * punch threshold plays a step at 0.85, after a hurting one a full step. On top, a rough
     * landing plays the game's light-fall sound and a hurting one its fall-damage sound, or its
     * medium-fall sound where it has no fall-damage one; a game without them plays only the step.
     * Smaller landings make no sound.
     */
    void land(Entity player, double fallen, List<MapSound> maps) {
        if (fallen < ROUGH_LANDING) return;
        boolean hurt = fallen >= HURTING_LANDING;
        play(step(maps, player.position(), player, hurt ? 1.0F : 0.85F, SoundSource.PLAYERS));
        if (!playScript(maps, hurt ? FALL_DAMAGE_SCRIPT : FALL_LIGHT_SCRIPT, player.position()) && hurt) {
            playScript(maps, FALL_MEDIUM_SCRIPT, player.position());
        }
    }

    /** Plays a named script of the first map that has it, at its own volume; false if none has. */
    private boolean playScript(List<MapSound> maps, String name, Vec3 at) {
        for (MapSound map : maps) {
            for (AudioTable.Script script : map.audio().scripts()) {
                if (!script.name().equals(name)) continue;
                play(scripted(map, script, at, (float) script.volume().sample(random), SoundSource.PLAYERS));
                return true;
            }
        }
        return false;
    }

    private void play(SourceSound sound) {
        if (sound != null) Minecraft.getInstance().getSoundManager().play(sound);
    }

    /** The step of the surface under {@code at}, alternating feet per walker; null where nothing plays. */
    private SourceSound step(List<MapSound> maps, Vec3 at, Entity walker, float volume, SoundSource category) {
        for (MapSound map : maps) {
            AudioTable.Surface surface = surface(map, at, true);
            if (surface == null) continue;
            boolean left = leftFoot.merge(walker == null ? 0 : walker.getId(), true, (was, ignored) -> !was);
            if (leftFoot.size() > 256) leftFoot.clear();
            int script = left ? surface.stepLeft() : surface.stepRight();
            if (script < 0) return null;
            steps++;
            return scripted(map, map.audio().scripts().get(script), at, Math.min(1.0F, volume * stepGain), category);
        }
        return null;
    }

    private SourceSound impact(SoundInstance original, Kind kind, Vec3 at, List<MapSound> maps) {
        for (MapSound map : maps) {
            AudioTable.Surface surface = surface(map, at, false);
            if (surface == null) continue;
            int script;
            float volume;
            switch (kind) {
                case HIT -> { script = surface.impactSoft(); volume = HIT_VOLUME; }
                case BREAK -> { script = surface.breakSound() >= 0 ? surface.breakSound() : surface.impactHard(); volume = BREAK_VOLUME; }
                default -> { script = surface.impactHard(); volume = PLACE_VOLUME; }
            }
            if (script < 0) return null;
            AudioTable.Script entry = map.audio().scripts().get(script);
            // The script's own volume, scaled by Minecraft's loudness for the action.
            return scripted(map, entry, at, volume * (float) entry.volume().sample(random), original.getSource());
        }
        return null;
    }

    /**
     * The surface a sound at {@code at} comes from: the floor under it for something underfoot,
     * the block's largest face otherwise. Underfoot, a spot with no map face -- a prop, a block
     * placed since -- is Source's {@code default}; a block with no face is no surface at all.
     */
    private static AudioTable.Surface surface(MapSound map, Vec3 at, boolean underfoot) {
        Vec3 local = map.local(at);
        BundleMap bundleMap = map.map();
        int material = underfoot
            ? SurfaceProbe.ground(bundleMap.surfaces(), local.x, local.y, local.z)
            : SurfaceProbe.block(bundleMap.surfaces(), (int) Math.floor(local.x), (int) Math.floor(local.y), (int) Math.floor(local.z));
        if (material >= bundleMap.materials().size()) return null;
        if (material < 0) return underfoot ? map.audio().surface(null) : null;
        BundleMaterial surfaceMaterial = bundleMap.materials().get(material);
        return map.audio().surface(surfaceMaterial.surfaceProp());
    }

    private SourceSound scripted(MapSound map, AudioTable.Script script, Vec3 at, float volume, SoundSource category) {
        AudioTable.Sound sound = map.sound(script.sound(random.nextInt(script.soundCount())));
        SourceSound voice = new SourceSound(library, map.bundle(), sound, category, at,
            (int) script.soundLevel().sample(random), volume, (int) script.pitch().sample(random) / 100.0F, false);
        return voice.prepare() ? voice : null;
    }

    /** The entity whose step this is: Minecraft plays a step exactly where the entity stands. */
    private static Entity walker(Vec3 at) {
        var level = Minecraft.getInstance().level;
        if (level == null) return null;
        for (Entity entity : level.getEntities((Entity) null, AABB.ofSize(at, 0.01, 0.01, 0.01))) {
            if (entity.position().distanceToSqr(at) < 1e-6) return entity;
        }
        return null;
    }
}
