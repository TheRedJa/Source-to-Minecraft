package dev.theredja.src2mc.client.audio;

import static net.minecraft.commands.Commands.literal;

import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.bundle.AudioTable;
import dev.theredja.src2mc.bundle.BundleGeneration;
import dev.theredja.src2mc.bundle.PropVisibility;
import dev.theredja.src2mc.network.PlacementNetwork;
import dev.theredja.src2mc.world.MapPlacement;
import dev.theredja.src2mc.world.PlacementIndex;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;

/**
 * The maps' sound: soundscapes, ambient sounds that play from the start, and the surfaces' own
 * footsteps and impacts, all played through Minecraft's sound engine so its volume sliders, and
 * any mod that processes its sounds -- reverb, occlusion -- apply as to any other sound.
 *
 * <p>{@code /src2mc_audio} reports what plays; {@code soundscapes}, {@code ambient} and
 * {@code surfaces} switch each part on and off for comparison, and {@code steps <gain>} sets how
 * much louder than Source's own levels footsteps, jumps and landings play.
 */
@EventBusSubscriber(modid = Src2mc.MOD_ID, value = Dist.CLIENT)
public final class SourceAudio {
    private static final SoundLibrary LIBRARY = new SoundLibrary();
    private static final SoundscapePlayer SOUNDSCAPES = new SoundscapePlayer(LIBRARY);
    private static final AmbientPlayer AMBIENTS = new AmbientPlayer(LIBRARY);
    private static final SurfaceSounds SURFACES = new SurfaceSounds(LIBRARY);
    private static final LogicSounds LOGIC = new LogicSounds(LIBRARY);
    private static boolean soundscapes = true, ambient = true, surfaces = true;

    private static ClientLevel level;
    private static long generationSequence = -1, placementEpoch = -1;
    private static List<MapSound> maps = List.of();
    private static List<SoundscapePlayer.Emitter> emitters = List.of();
    private static List<AmbientPlayer.Ambient> ambients = List.of();
    private static List<LogicSounds.MusicEntity> music = List.of();

    private SourceAudio() {}

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            if (level != null) {
                reset(false);
                dev.theredja.src2mc.client.logic.ClientLogic.clear();
                dev.theredja.src2mc.client.logic.CaptionOverlay.clear();
                dev.theredja.src2mc.world.MoverRegistry.clear(true);
                dev.theredja.src2mc.world.PropStates.clear(true);
                dev.theredja.src2mc.client.render.MoverRenderer.clear();
            }
            return;
        }
        refresh(minecraft.level);
        if (minecraft.isPaused() || !LIBRARY.available()) return;
        Vec3 ear = minecraft.player.getEyePosition();
        Vec3 listener = minecraft.gameRenderer.getMainCamera().getPosition();
        var dimension = minecraft.level.dimension().location();
        if (soundscapes) SOUNDSCAPES.tick(minecraft.level, ear, emitters);
        if (ambient) AMBIENTS.tick(dimension, listener, ambients);
        LOGIC.tick(dimension, listener, maps, music);
        if (surfaces) movement(minecraft.player);
        else airborne = false;
    }

    private static boolean wasOnGround = true, airborne;
    private static double airTop;

    /**
     * Jumps and landings of the local player, which Minecraft gives no sound of its own to
     * replace: leaving the ground upwards is a jump; touching down ends a fall measured from the
     * highest point reached. Flying, swimming, climbing and riding are neither.
     */
    private static void movement(net.minecraft.client.player.LocalPlayer player) {
        boolean onGround = player.onGround();
        boolean free = !player.getAbilities().flying && !player.isInWater() && !player.onClimbable()
            && !player.isPassenger() && !player.isFallFlying();
        if (!free) {
            airborne = false;
        } else if (!onGround) {
            if (!airborne) {
                airborne = true;
                airTop = player.getY();
                if (wasOnGround && player.getDeltaMovement().y > 0) SURFACES.jump(player, holding(player.position()));
            }
            airTop = Math.max(airTop, player.getY());
        } else if (airborne) {
            airborne = false;
            SURFACES.land(player, airTop - player.getY(), holding(player.position()));
        }
        wasOnGround = onGround;
    }

    /** The maps with sound whose placement holds {@code at} or the block under it. */
    private static List<MapSound> holding(Vec3 at) {
        BlockPos cell = BlockPos.containing(at), below = BlockPos.containing(at.x, at.y - 0.25, at.z);
        List<MapSound> holding = new ArrayList<>(1);
        for (MapSound map : maps) if (map.placement().contains(cell) || map.placement().contains(below)) holding.add(map);
        return holding;
    }

    @SubscribeEvent
    static void onPlaySound(PlaySoundEvent event) {
        SoundInstance sound = event.getSound();
        if (!surfaces || sound == null || sound instanceof SourceSound || maps.isEmpty()) return;
        SurfaceSounds.Kind kind = SurfaceSounds.kind(sound.getLocation());
        if (kind == null || !LIBRARY.available()) return;
        // A step sounds at the feet, which may stand on the cell below.
        List<MapSound> holding = holding(new Vec3(sound.getX(), sound.getY(), sound.getZ()));
        if (holding.isEmpty()) return;
        SoundInstance replacement = SURFACES.replace(sound, kind, holding);
        if (replacement != sound) event.setSound(replacement);
    }

    /**
     * Inside a placed map Minecraft's own music never plays: the map has its own soundscapes and,
     * with its logic running, its own music (user, 2026-10-03). Outside, Minecraft picks as usual.
     */
    @SubscribeEvent
    static void onSelectMusic(net.neoforged.neoforge.client.event.SelectMusicEvent event) {
        var player = Minecraft.getInstance().player;
        if (player == null || maps.isEmpty()) return;
        BlockPos at = player.blockPosition();
        for (MapSound map : maps) {
            if (map.placement().contains(at)) {
                event.setMusic(null);
                return;
            }
        }
    }

    /** Rebuilds what plays from the placements and bundles whenever either changes. */
    private static void refresh(ClientLevel current) {
        BundleGeneration generation = Src2mc.bundles().active();
        long epoch = PlacementIndex.epoch();
        if (current == level && generation.sequence() == generationSequence && epoch == placementEpoch) return;
        boolean newGeneration = generation.sequence() != generationSequence;
        reset(newGeneration);
        level = current;
        generationSequence = generation.sequence();
        placementEpoch = epoch;
        List<MapSound> nextMaps = new ArrayList<>();
        List<SoundscapePlayer.Emitter> nextEmitters = new ArrayList<>();
        List<AmbientPlayer.Ambient> nextAmbients = new ArrayList<>();
        List<LogicSounds.MusicEntity> nextMusic = new ArrayList<>();
        for (MapPlacement placement : PlacementNetwork.clientIndex(current.dimension().location()).view()) {
            var located = generation.findLocatedMap(placement.campaignId(), placement.mapId()).orElse(null);
            if (located == null || located.map().audio() == null) continue;
            MapSound map = new MapSound(placement, located.bundle(), located.map(), PropGround.of(located.map()));
            nextMaps.add(map);
            AudioTable audio = located.map().audio();
            PropVisibility pvs = located.map().pvs();
            for (int i = 0; i < audio.emitters().size(); i++) {
                AudioTable.Emitter emitter = audio.emitters().get(i);
                int cluster = pvs == null ? -1 : pvs.clusterAt(emitter.x(), emitter.y(), emitter.z());
                nextEmitters.add(new SoundscapePlayer.Emitter(map, i, map.world(emitter.x(), emitter.y(), emitter.z()),
                    emitter.radius(), cluster));
            }
            for (int i = 0; i < audio.ambients().size(); i++) {
                AudioTable.Ambient entry = audio.ambients().get(i);
                nextAmbients.add(new AmbientPlayer.Ambient(map, i, map.world(entry.x(), entry.y(), entry.z())));
            }
            var logic = located.map().logic();
            if (logic != null) {
                for (int i = 0; i < logic.entities().size(); i++) {
                    if (logic.entities().get(i).classname().equals("infra_music")) nextMusic.add(new LogicSounds.MusicEntity(map, i));
                }
            }
        }
        maps = List.copyOf(nextMaps);
        emitters = List.copyOf(nextEmitters);
        ambients = List.copyOf(nextAmbients);
        music = List.copyOf(nextMusic);
    }

    private static void reset(boolean freeBuffers) {
        SOUNDSCAPES.stop();
        AMBIENTS.stop();
        LOGIC.stop();
        if (freeBuffers) LIBRARY.clear();
        level = null;
        maps = List.of();
        emitters = List.of();
        ambients = List.of();
        music = List.of();
        generationSequence = -1;
        placementEpoch = -1;
    }

    @SubscribeEvent
    static void registerCommand(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(literal("src2mc_audio")
            .executes(context -> status(context.getSource()))
            .then(literal("soundscapes")
                .then(literal("on").executes(context -> { soundscapes = true; return status(context.getSource()); }))
                .then(literal("off").executes(context -> { soundscapes = false; SOUNDSCAPES.stop(); return status(context.getSource()); })))
            .then(literal("ambient")
                .then(literal("on").executes(context -> { ambient = true; return status(context.getSource()); }))
                .then(literal("off").executes(context -> { ambient = false; AMBIENTS.stop(); return status(context.getSource()); })))
            .then(literal("surfaces")
                .then(literal("on").executes(context -> { surfaces = true; return status(context.getSource()); }))
                .then(literal("off").executes(context -> { surfaces = false; return status(context.getSource()); })))
            .then(literal("captions")
                .then(literal("on").executes(context -> { dev.theredja.src2mc.client.logic.CaptionOverlay.setEnabled(true); return status(context.getSource()); }))
                .then(literal("off").executes(context -> { dev.theredja.src2mc.client.logic.CaptionOverlay.setEnabled(false); return status(context.getSource()); })))
            .then(literal("steps")
                .then(net.minecraft.commands.Commands.argument("gain", com.mojang.brigadier.arguments.FloatArgumentType.floatArg(0, 5))
                    .executes(context -> {
                        SURFACES.setStepGain(com.mojang.brigadier.arguments.FloatArgumentType.getFloat(context, "gain"));
                        return status(context.getSource());
                    }))));
    }

    private static int status(net.minecraft.commands.CommandSourceStack source) {
        String text = "src2mc audio: " + (LIBRARY.available() ? "" : "UNAVAILABLE (see log); ")
            + maps.size() + " map(s) with sound, " + emitters.size() + " soundscape entities, " + ambients.size() + " ambient sounds\n"
            + "  soundscapes " + (soundscapes ? "on" : "off") + ": " + SOUNDSCAPES.status() + "\n"
            + "  ambient " + (ambient ? "on" : "off") + ": " + AMBIENTS.status() + "\n"
            + "  surfaces " + (surfaces ? "on" : "off") + ": " + SURFACES.status() + "\n"
            + "  logic: " + LOGIC.status() + ", captions " + (dev.theredja.src2mc.client.logic.CaptionOverlay.enabled() ? "on" : "off") + "\n"
            + "  decoded " + LIBRARY.decodedCount() + ", failed " + LIBRARY.failedCount();
        source.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }
}
