package dev.theredja.src2mc.client.audio;

import dev.theredja.src2mc.bundle.AudioTable;
import dev.theredja.src2mc.bundle.PropVisibility;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Source's soundscape system, after the SDK's {@code soundscape_system.cpp},
 * {@code soundscape.cpp} and {@code c_soundscape.cpp}.
 *
 * <p>An {@code env_soundscape} the listener is within the radius of and can see takes over; when
 * several can, the nearest wins, and the last one to win stays until another does, however far
 * the listener walks. Seeing is a straight line between the two that no block blocks. Source
 * first narrows the entities down to those whose PVS holds the listener's cluster, which the
 * map's own PVS table does here too.
 *
 * <p>The active soundscape plays its loops, fading each toward its volume over three seconds, and
 * schedules its random sounds; its sub-soundscapes add theirs. Starting another fades every loop
 * out, except those the new one plays too, which carry on at their current volume.
 */
final class SoundscapePlayer {
    private static final double FADE_SECONDS = 3.0;
    private static final double TICK_SECONDS = 0.05;
    private static final int MAX_DEPTH = 8;
    private static final int MAX_POSITION = 31;
    /** {@code DEFAULT_SOUND_RADIUS}: random positions sit 36 units from the listener. */
    private static final double RANDOM_RADIUS = 36.0 / SourceFalloff.UNITS_PER_BLOCK;
    /** {@code VectorsAreEqual(..., 0.1)}, in blocks. */
    private static final double SAME_POSITION = 0.1 / SourceFalloff.UNITS_PER_BLOCK;
    private static final double SNDLVL_NORM = 75;

    private final SoundLibrary library;
    private final RandomGenerator random = RandomGenerator.of("L64X128MixRandom");
    private final List<Loop> loops = new ArrayList<>();
    private final List<RandomSound> randoms = new ArrayList<>();
    private Emitter current;
    private int loopId;
    private double time, nextRandomTime;
    private final Vec3[] localSound = new Vec3[AudioTable.LOCAL_POSITIONS];
    private long traces, switches;

    SoundscapePlayer(SoundLibrary library) { this.library = library; }

    /** One {@code env_soundscape} of one placed map. */
    record Emitter(MapSound map, int index, Vec3 position, double radius, int cluster) {
        AudioTable.Emitter entry() { return map.audio().emitters().get(index); }
        boolean same(Emitter other) { return other != null && other.map.placement().equals(map.placement()) && other.index == index; }
    }

    String status() {
        if (current == null) return "no soundscape";
        String name = current.map.audio().soundscapes().get(current.entry().soundscape()).name();
        long playing = loops.stream().filter(loop -> loop.voice != null).count();
        return name + " (" + current.map.placement().mapId() + " #" + current.index + "), loops " + playing + "/" + loops.size()
            + ", randoms " + randoms.size() + ", switches " + switches + ", traces " + traces;
    }

    void tick(Level level, Vec3 ear, List<Emitter> emitters) {
        time += TICK_SECONDS;
        // CEnvSoundscape::UpdateForPlayer: a disabled soundscape lets go of the player.
        if (current != null && !enabled(level, current)) current = null;
        select(level, ear, emitters);
        fadeLoops();
        playRandoms(ear);
    }

    /** {@code CSoundscapeSystem::FrameUpdatePostEntityThink} and {@code CEnvSoundscape::UpdateForPlayer}. */
    private void select(Level level, Vec3 ear, List<Emitter> emitters) {
        boolean inRange = false;
        double currentDistance = 0;
        if (current != null) {
            currentDistance = ear.distanceTo(current.position);
            inRange = reaches(current, currentDistance) && visible(level, current.position, ear);
        }
        Emitter chosen = current;
        for (Emitter candidate : emitters) {
            if (candidate.same(chosen) || !enabled(level, candidate) || !inPvs(candidate, ear)) continue;
            double range = ear.distanceTo(candidate.position);
            if ((!inRange || range < currentDistance) && reaches(candidate, range) && visible(level, candidate.position, ear)) {
                chosen = candidate;
                inRange = true;
                currentDistance = range;
            }
        }
        if (chosen != null && !chosen.same(current)) {
            current = chosen;
            switches++;
            start(chosen);
        }
    }

    /**
     * Whether the entity may be chosen: as the map's logic says while it runs, else unless it
     * starts disabled.
     */
    private static boolean enabled(Level level, Emitter emitter) {
        var dimension = level.dimension().location();
        long anchor = emitter.map.placement().anchorWorld().asLong();
        if (!dev.theredja.src2mc.client.logic.ClientLogic.running(dimension, anchor)) return !emitter.entry().startDisabled();
        var state = dev.theredja.src2mc.client.logic.ClientLogic.state(dimension, anchor, emitter.entry().entity());
        return state != null && state.on();
    }

    private static boolean reaches(Emitter emitter, double range) { return emitter.radius > range || emitter.radius == -1; }

    private static boolean inPvs(Emitter emitter, Vec3 ear) {
        PropVisibility pvs = emitter.map.map().pvs();
        if (pvs == null || emitter.cluster < 0) return true;
        Vec3 local = emitter.map.local(ear);
        int earCluster = pvs.clusterAt(local.x, local.y, local.z);
        byte[] row = pvs.row(emitter.cluster);
        return earCluster < 0 || row == null || (row[earCluster >> 3] & (1 << (earCluster & 7))) != 0;
    }

    private boolean visible(Level level, Vec3 from, Vec3 to) {
        traces++;
        return level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, CollisionContext.empty()))
            .getType() == HitResult.Type.MISS;
    }

    /** {@code StartNewSoundscape}. */
    private void start(Emitter emitter) {
        for (Loop loop : loops) loop.target = 0;
        loopId++;
        randoms.clear();
        nextRandomTime = time;
        AudioTable.Emitter entry = emitter.entry();
        for (int i = 0; i < localSound.length; i++) localSound[i] = emitter.map.world(entry.position(i));
        startSub(emitter.map, entry.soundscape(), new Params(1.0, 0, 0, -1, -1));
    }

    private record Params(double masterVolume, int startingPosition, int depth, int positionOverride, int ambientPositionOverride) {}

    /** {@code StartSubSoundscape}. */
    private void startSub(MapSound map, int soundscapeIndex, Params params) {
        AudioTable.Soundscape soundscape = map.audio().soundscapes().get(soundscapeIndex);
        for (AudioTable.Loop loop : soundscape.loops()) playLooping(map, loop, params);
        for (AudioTable.Random entry : soundscape.randoms()) playRandom(map, entry, params);
        for (AudioTable.Child child : soundscape.children()) {
            // Sub-soundscapes never set DSP; there is none here to set.
            if (params.depth + 1 > MAX_DEPTH) continue;
            int positionOverride = params.positionOverride, ambientOverride = params.ambientPositionOverride;
            if (child.positionOverride() >= 0 && params.positionOverride < 0) {
                positionOverride = params.startingPosition + child.positionOverride();
                ambientOverride = positionOverride;
            }
            if (child.ambientPositionOverride() >= 0 && params.ambientPositionOverride < 0) {
                ambientOverride = params.startingPosition + child.ambientPositionOverride();
            }
            startSub(map, child.soundscape(), new Params(params.masterVolume * child.volume().sample(random),
                params.startingPosition + child.position(), params.depth + 1, positionOverride, ambientOverride));
        }
    }

    /** {@code ProcessPlayLooping}. */
    private void playLooping(MapSound map, AudioTable.Loop entry, Params params) {
        double volume = params.masterVolume * entry.volume().sample(random);
        int pitch = (int) entry.pitch().sample(random);
        int soundLevel = (int) entry.soundLevel().sample(random);
        int position = entry.position() >= 0 ? params.startingPosition + entry.position() : -1;
        if (position < 0) position = params.ambientPositionOverride;
        else if (params.positionOverride >= 0) position = params.positionOverride;
        if (volume == 0) return;
        if (position < 0) {
            addLoop(map, entry.sound(), true, volume, SNDLVL_NORM, pitch, null);
        } else {
            Vec3 at = position > MAX_POSITION || position >= localSound.length ? null : localSound[position];
            // Suppressed when the entity names no such position.
            if (at != null) addLoop(map, entry.sound(), false, volume, soundLevel, pitch, at);
        }
    }

    /** {@code AddLoopingSound}: reuses a fading-out slot playing the same wave, which prevents pops. */
    private void addLoop(MapSound map, int soundIndex, boolean ambient, double volume, double soundLevel, int pitch, Vec3 position) {
        AudioTable.Sound sound = map.sound(soundIndex);
        Loop slot = null;
        boolean restart = false;
        for (int i = loops.size() - 1; i >= 0; i--) {
            Loop candidate = loops.get(i);
            if (candidate.id == loopId || candidate.pitch != pitch || !candidate.sound.contentId().equals(sound.contentId())) continue;
            if (ambient && candidate.ambient) { slot = candidate; break; }
            if (ambient == candidate.ambient) {
                slot = candidate;
                if (candidate.position.distanceTo(position) > SAME_POSITION) {
                    // Positional sounds always restart where they now are.
                    candidate.stop();
                    restart = true;
                }
                break;
            }
        }
        if (slot == null) {
            slot = new Loop();
            // Non-ambient sounds at zero volume are culled in Source, so they start at 0.05.
            slot.current = ambient ? 0.0 : 0.05;
            loops.add(slot);
            restart = true;
        }
        slot.map = map;
        slot.sound = sound;
        slot.target = volume;
        slot.pitch = pitch;
        slot.id = loopId;
        slot.ambient = ambient;
        slot.position = position;
        slot.soundLevel = soundLevel;
        if (restart) slot.start(library);
    }

    /** {@code UpdateLoopingSounds}: each loop approaches its target volume over the fade time. */
    private void fadeLoops() {
        double amount = TICK_SECONDS / FADE_SECONDS;
        for (int i = loops.size() - 1; i >= 0; i--) {
            Loop loop = loops.get(i);
            if (loop.current != loop.target) {
                loop.current = loop.target > loop.current ? Math.min(loop.target, loop.current + amount)
                    : Math.max(loop.target, loop.current - amount);
                if (loop.target == 0 && loop.current == 0) {
                    loop.stop();
                    loops.remove(i);
                    continue;
                }
            }
            loop.update(library);
        }
    }

    /** {@code ProcessPlayRandom}. */
    private void playRandom(MapSound map, AudioTable.Random entry, Params params) {
        int position = entry.position() >= 0 ? params.startingPosition + entry.position() : -1;
        boolean randomPosition = entry.randomPosition();
        if (position < 0) position = params.ambientPositionOverride;
        else if (params.positionOverride >= 0) { position = params.positionOverride; randomPosition = false; }
        RandomSound sound = new RandomSound(map, entry, params.masterVolume);
        if (position < 0 && !randomPosition) {
            sound.ambient = true;
        } else if (randomPosition) {
            sound.randomPosition = true;
        } else {
            sound.position = position > MAX_POSITION || position >= localSound.length ? null : localSound[position];
            if (sound.position == null) return;
        }
        sound.nextPlayTime = time + 0.5 * entry.time().sample(random);
        randoms.add(sound);
    }

    /** {@code UpdateRandomSounds}. */
    private void playRandoms(Vec3 ear) {
        if (time < nextRandomTime) return;
        nextRandomTime = time + 3600;
        for (int i = randoms.size() - 1; i >= 0; i--) {
            RandomSound sound = randoms.get(i);
            if (time >= sound.nextPlayTime) {
                play(sound, ear);
                sound.nextPlayTime = time + sound.entry.time().sample(random);
            }
            nextRandomTime = Math.min(nextRandomTime, sound.nextPlayTime);
        }
    }

    /** {@code PlayRandomSound}. */
    private void play(RandomSound sound, Vec3 ear) {
        AudioTable.Random entry = sound.entry;
        AudioTable.Sound asset = sound.map.sound(entry.sound(random.nextInt(entry.soundCount())));
        float volume = (float) (sound.masterVolume * entry.volume().sample(random));
        float pitch = (int) entry.pitch().sample(random) / 100.0F;
        Vec3 position = null;
        double soundLevel = 0;
        if (!sound.ambient) {
            soundLevel = (int) entry.soundLevel().sample(random);
            position = sound.randomPosition ? randomPosition(ear) : sound.position;
        }
        Voices.play(new SourceSound(library, sound.map.bundle(), asset, SoundSource.AMBIENT, position, soundLevel, volume, pitch, false));
    }

    /** {@code GenerateRandomSoundPosition}: on a circle around the listener, in the plane it looks along. */
    private Vec3 randomPosition(Vec3 ear) {
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vec3 forward = new Vec3(camera.getLookVector());
        Vec3 right = new Vec3(camera.getLeftVector()).scale(-1);
        double angle = random.nextDouble() * Math.PI * 2;
        return ear.add(right.scale(Math.cos(angle) * RANDOM_RADIUS)).add(forward.scale(Math.sin(angle) * RANDOM_RADIUS));
    }

    /** Silences everything at once, as {@code StartNewSoundscape(NULL)} does. */
    void stop() {
        for (Loop loop : loops) loop.stop();
        loops.clear();
        randoms.clear();
        current = null;
    }

    private static final class Loop {
        MapSound map;
        AudioTable.Sound sound;
        double current, target, soundLevel;
        int pitch, id;
        boolean ambient;
        Vec3 position;
        SourceSound voice;

        void start(SoundLibrary library) {
            stop();
            voice = new SourceSound(library, map.bundle(), sound, SoundSource.AMBIENT, ambient ? null : position,
                ambient ? 0 : soundLevel, (float) current, pitch / 100.0F, true);
            if (!Voices.play(voice)) voice = null;
        }

        /** Keeps the voice at the loop's volume, and restarts a loop Minecraft dropped. */
        void update(SoundLibrary library) {
            if (voice == null) return;
            voice.setLevel((float) current);
            if (sound.loops() && !Voices.active(voice) && Voices.audible(SoundSource.AMBIENT)) start(library);
        }

        void stop() {
            if (voice != null) Voices.stop(voice);
            voice = null;
        }
    }

    private static final class RandomSound {
        final MapSound map;
        final AudioTable.Random entry;
        final double masterVolume;
        boolean ambient, randomPosition;
        Vec3 position;
        double nextPlayTime;

        RandomSound(MapSound map, AudioTable.Random entry, double masterVolume) {
            this.map = map;
            this.entry = entry;
            this.masterVolume = masterVolume;
        }
    }
}
