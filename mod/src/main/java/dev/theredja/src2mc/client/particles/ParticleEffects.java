package dev.theredja.src2mc.client.particles;

import static net.minecraft.commands.Commands.literal;

import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.bundle.BundleGeneration;
import dev.theredja.src2mc.bundle.BundleManifest;
import dev.theredja.src2mc.bundle.BundleMap;
import dev.theredja.src2mc.bundle.LogicTable;
import dev.theredja.src2mc.bundle.ParticleTable;
import dev.theredja.src2mc.client.logic.ClientLogic;
import dev.theredja.src2mc.logic.LogicNetwork;
import dev.theredja.src2mc.network.PlacementNetwork;
import dev.theredja.src2mc.world.MapPlacement;
import dev.theredja.src2mc.client.render.DecalRenderer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * The particle effects of every placed map (format.md section 23): each
 * {@code info_particle_system} the map has, run as Source's {@code C_ParticleSystem} runs it, and
 * the one-shot effects impacts start.
 *
 * <p>An entity's effect runs while the server's logic says it is on -- Start turns it on and starts
 * it over, Stop ends its emission and lets its particles live out, DestroyImmediately takes them
 * away -- or, while the map's logic is not running, when it starts active. As
 * {@code C_ParticleSystem::ClientThink} skips an effect to the time it has been running, an effect
 * found already on is simulated ahead before it is shown. Effects further than {@link #range} from
 * the camera are dropped and started again on return.
 */
@EventBusSubscriber(modid = Src2mc.MOD_ID, value = Dist.CLIENT)
public final class ParticleEffects {
    private ParticleEffects() {}

    /** How far ahead an effect found running is simulated before it shows: its particles' usual life. */
    private static final float CATCH_UP_SECONDS = 4;

    private static boolean enabled = true;
    private static double range = 160;
    private static long lastNanos = -1;

    /** One placed map's particle setup. Never a hash key: it holds a {@link BundleMap}. */
    private record MapRef(MapPlacement placement, BundleManifest bundle, BundleMap map, Space space) {}

    /** An {@code info_particle_system} of a placed map. */
    private static final class Source {
        final MapRef map;
        final int entity, system;
        final double[] origin, angles;
        final List<double[]> controlPoints = new ArrayList<>();
        final List<Integer> controlPointNumbers = new ArrayList<>();
        final boolean startActive;
        Effect current;
        final List<Effect> fading = new ArrayList<>();
        int serial = Integer.MIN_VALUE;

        Source(MapRef map, int entity, int system, double[] origin, double[] angles, boolean startActive) {
            this.map = map; this.entity = entity; this.system = system; this.origin = origin; this.angles = angles; this.startActive = startActive;
        }
    }

    /**
     * An {@code env_spark} of a placed map: it draws a spark each time the server's logic moves its
     * serial on; while the logic is not running, a start-on one sparks on its own clock as
     * {@code CEnvSpark::SparkThink} would.
     */
    private static final class SparkSource {
        final MapRef map;
        final int entity, magnitude, trailLength;
        final double[] origin, direction;
        final boolean startOn;
        final double maxDelay;
        int serial = Integer.MIN_VALUE;
        double untilNext = Double.NaN;

        SparkSource(MapRef map, int entity, double[] origin, double[] direction, int magnitude, int trailLength, boolean startOn, double maxDelay) {
            this.map = map; this.entity = entity; this.origin = origin; this.direction = direction; this.magnitude = magnitude;
            this.trailLength = trailLength; this.startOn = startOn; this.maxDelay = maxDelay;
        }
    }

    private static final List<Source> SOURCES = new ArrayList<>();
    private static final List<SparkSource> SPARKS = new ArrayList<>();
    static long sparks;
    /** One-shot effects, impacts and the like, with their map. */
    private static final List<ParticleRenderer.Draw> ONE_SHOTS = new ArrayList<>();
    private static ClientLevel level;
    private static long generationSequence = -1, placementEpoch = -1;
    private static long seeds = 1;

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        if (dev.theredja.src2mc.client.render.IrisCompat.shadowPass()) return;
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel current = minecraft.level;
        long now = System.nanoTime();
        float dt = lastNanos < 0 ? 0 : Math.min(0.25F, (now - lastNanos) / 1e9F);
        lastNanos = now;
        if (current == null || !enabled) { clear(); return; }
        if (minecraft.isPaused()) dt = 0;
        refresh(current);
        Vec3 camera = event.getCamera().getPosition();
        List<ParticleRenderer.Draw> draws = new ArrayList<>();
        var dimension = current.dimension().location();
        for (Source source : SOURCES) {
            update(source, dimension, camera, dt);
            if (source.current != null) draws.add(new ParticleRenderer.Draw(source.current, source.map.bundle, source.map.map));
            for (Effect fading : source.fading) draws.add(new ParticleRenderer.Draw(fading, source.map.bundle, source.map.map));
        }
        for (SparkSource spark : SPARKS) update(spark, dimension, camera, dt);
        float step = dt;
        ONE_SHOTS.removeIf(draw -> {
            draw.effect().simulate(step);
            return draw.effect().finished();
        });
        draws.addAll(ONE_SHOTS);
        ParticleRenderer.render(draws, event.getCamera(), new org.joml.Matrix4f(event.getModelViewMatrix()), event.getProjectionMatrix(),
            Src2mc.bundles().active().sequence(), dev.theredja.src2mc.client.render.MapSurfaceRenderer.currentFrame());
    }

    private static void update(Source source, net.minecraft.resources.ResourceLocation dimension, Vec3 camera, float dt) {
        source.fading.removeIf(effect -> {
            effect.simulate(dt);
            return effect.finished();
        });
        long anchor = source.map.placement.anchorWorld().asLong();
        boolean running = ClientLogic.running(dimension, anchor);
        LogicNetwork.SoundState state = running ? ClientLogic.state(dimension, anchor, source.entity) : null;
        boolean on = running ? state != null && state.on() : source.startActive;
        int serial = running ? (state == null ? 0 : state.serial()) : (on ? 1 : 0);
        double dx = source.map.space.worldX(source.origin[0]) - camera.x, dy = source.map.space.worldY(source.origin[2]) - camera.y,
            dz = source.map.space.worldZ(source.origin[1]) - camera.z;
        boolean near = dx * dx + dy * dy + dz * dz <= range * range;
        boolean firstSight = source.serial == Integer.MIN_VALUE;
        boolean restarted = !firstSight && serial != source.serial;
        source.serial = serial;
        if (!on) {
            if (restarted) {
                // DestroyImmediately: gone at once.
                source.current = null;
                source.fading.clear();
            } else if (source.current != null) {
                source.current.stop();
                source.fading.add(source.current);
                source.current = null;
            }
            return;
        }
        if (!near) {
            source.current = null;
            source.fading.clear();
            source.serial = Integer.MIN_VALUE;
            return;
        }
        if (source.current == null || restarted) {
            if (source.current != null) { source.current.stop(); source.fading.add(source.current); }
            source.current = start(source);
            // Started before it was seen: run it to where it would be.
            if (firstSight || !restarted) source.current.simulate(CATCH_UP_SECONDS);
        } else {
            source.current.simulate(dt);
        }
    }

    private static void update(SparkSource spark, net.minecraft.resources.ResourceLocation dimension, Vec3 camera, float dt) {
        long anchor = spark.map.placement.anchorWorld().asLong();
        int count = 0;
        if (ClientLogic.running(dimension, anchor)) {
            LogicNetwork.SoundState state = ClientLogic.state(dimension, anchor, spark.entity);
            int serial = state == null ? 0 : state.serial();
            // A spark is an instant; one seen first, or after a long gap, is not drawn late.
            if (spark.serial != Integer.MIN_VALUE) count = Math.min(3, Math.max(0, serial - spark.serial));
            spark.serial = serial;
            spark.untilNext = Double.NaN;
        } else {
            spark.serial = Integer.MIN_VALUE;
            if (!spark.startOn) return;
            if (Double.isNaN(spark.untilNext)) spark.untilNext = 0.1 + RANDOM.nextDouble() * 1.5;
            spark.untilNext -= dt;
            if (spark.untilNext <= 0) {
                count = 1;
                spark.untilNext = 0.1 + RANDOM.nextDouble() * spark.maxDelay;
            }
        }
        if (count == 0) return;
        double dx = spark.map.space.worldX(spark.origin[0]) - camera.x, dy = spark.map.space.worldY(spark.origin[2]) - camera.y,
            dz = spark.map.space.worldZ(spark.origin[1]) - camera.z;
        if (dx * dx + dy * dy + dz * dz > range * range) return;
        ParticleTable table = spark.map.map.particles();
        var context = new CodeEffects.Context(table, spark.map.space, table.impacts().materials());
        for (int i = 0; i < count; i++) {
            for (Effect effect : CodeEffects.electricSpark(context, spark.origin, spark.magnitude, spark.trailLength, spark.direction,
                    seeds++ * 0x9E3779B97F4A7C15L)) {
                effect.sortX = dx + camera.x; effect.sortY = dy + camera.y; effect.sortZ = dz + camera.z;
                ONE_SHOTS.add(new ParticleRenderer.Draw(effect, spark.map.bundle, spark.map.map));
            }
            sparks++;
        }
    }

    private static final java.util.random.RandomGenerator RANDOM = java.util.random.RandomGenerator.of("L64X128MixRandom");

    private static Effect start(Source source) {
        Effect effect = new Effect(source.map.map.particles(), source.system, source.map.space, seeds++ * 0x9E3779B97F4A7C15L);
        effect.setOrigin(source.origin[0], source.origin[1], source.origin[2], source.angles[0], source.angles[1], source.angles[2]);
        for (int i = 0; i < source.controlPoints.size(); i++) {
            double[] point = source.controlPoints.get(i);
            effect.setControlPoint(source.controlPointNumbers.get(i), point[0], point[1], point[2]);
        }
        return effect;
    }

    /** Rebuilds the sources from the placements and bundles whenever either changes. */
    private static void refresh(ClientLevel current) {
        BundleGeneration generation = Src2mc.bundles().active();
        long epoch = dev.theredja.src2mc.world.PlacementIndex.epoch();
        if (current == level && generation.sequence() == generationSequence && epoch == placementEpoch) return;
        clear();
        level = current;
        generationSequence = generation.sequence();
        placementEpoch = epoch;
        for (MapPlacement placement : PlacementNetwork.clientIndex(current.dimension().location()).view()) {
            var located = generation.findLocatedMap(placement.campaignId(), placement.mapId()).orElse(null);
            if (located == null) continue;
            BundleMap map = located.map();
            ParticleTable table = map.particles();
            LogicTable logic = map.logic();
            if (table == null || logic == null) continue;
            double[] origin = logic.sourceOrigin();
            var translation = placement.translation();
            MapRef ref = new MapRef(placement, located.bundle(), map,
                new Space(translation.getX() + origin[0], translation.getY() + origin[1], translation.getZ() + origin[2]));
            Map<String, double[]> named = new HashMap<>();
            for (LogicTable.Entity entity : logic.entities()) {
                String name = entity.targetname();
                if (name != null && entity.origin() != null) named.putIfAbsent(name.toLowerCase(java.util.Locale.ROOT), toSource(ref, entity.origin()));
            }
            for (int i = 0; i < logic.entities().size(); i++) {
                LogicTable.Entity entity = logic.entities().get(i);
                if (entity.classname().equals("env_spark") && entity.origin() != null) {
                    int flags = integer(entity.value("spawnflags"), 0);
                    double[] direction = null;
                    if ((flags & 512) != 0) {
                        // SF_SPARK_DIRECTIONAL: AngleVectors of the entity's angles.
                        double[] a = angles(entity.value("angles"));
                        double pitch = Math.toRadians(a[0]), yaw = Math.toRadians(a[1]);
                        direction = new double[] {Math.cos(pitch) * Math.cos(yaw), Math.cos(pitch) * Math.sin(yaw), -Math.sin(pitch)};
                    }
                    SPARKS.add(new SparkSource(ref, i, toSource(ref, entity.origin()), direction, integer(entity.value("magnitude"), 1),
                        integer(entity.value("traillength"), 1), (flags & 64) != 0, Math.max(0, number(entity.value("maxdelay")))));
                    continue;
                }
                if (!entity.classname().equals("info_particle_system") || entity.origin() == null) continue;
                String effectName = entity.value("effect_name");
                if (effectName == null || effectName.isEmpty()) continue;
                int system = table.system(effectName);
                if (system < 0) continue;
                Source source = new Source(ref, i, system, toSource(ref, entity.origin()), angles(entity.value("angles")),
                    !"0".equals(entity.value("start_active")) && entity.value("start_active") != null);
                for (int cp = 1; cp <= 63; cp++) {
                    String target = entity.value("cpoint" + cp);
                    if (target == null || target.isEmpty()) continue;
                    double[] point = named.get(target.toLowerCase(java.util.Locale.ROOT));
                    if (point == null) continue;
                    source.controlPoints.add(point);
                    source.controlPointNumbers.add(cp);
                }
                SOURCES.add(source);
            }
        }
    }

    /** A map-local position as Source coordinates. */
    private static double[] toSource(MapRef ref, double[] local) {
        double[] out = new double[3];
        var translation = ref.placement.translation();
        ref.space.toSource(local[0] + translation.getX(), local[1] + translation.getY(), local[2] + translation.getZ(), out);
        return out;
    }

    private static int integer(String text, int fallback) {
        if (text == null) return fallback;
        try { return (int) Double.parseDouble(text.trim()); } catch (NumberFormatException e) { return fallback; }
    }

    private static double number(String text) {
        if (text == null) return 0;
        try { return Double.parseDouble(text.trim()); } catch (NumberFormatException e) { return 0; }
    }

    private static double[] angles(String text) {
        double[] out = new double[3];
        if (text == null) return out;
        String[] parts = text.trim().split("\\s+");
        for (int i = 0; i < 3 && i < parts.length; i++) {
            try { out[i] = Double.parseDouble(parts[i]); } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    /**
     * Starts a one-shot system of a placed map at a world point, its control point 0 facing
     * {@code forward} (Source axes). Returns the effect, or null when the map has no such system.
     */
    static Effect oneShot(MapPlacement placement, BundleManifest bundle, BundleMap map, int system, Vec3 world) {
        if (map.particles() == null || map.logic() == null || system < 0 || system >= map.particles().systems().size()) return null;
        double[] origin = map.logic().sourceOrigin();
        var translation = placement.translation();
        Space space = new Space(translation.getX() + origin[0], translation.getY() + origin[1], translation.getZ() + origin[2]);
        Effect effect = new Effect(map.particles(), system, space, seeds++ * 0x9E3779B97F4A7C15L);
        double[] at = new double[3];
        space.toSource(world.x, world.y, world.z, at);
        effect.setOrigin(at[0], at[1], at[2], 0, 0, 0);
        ONE_SHOTS.add(new ParticleRenderer.Draw(effect, bundle, map));
        return effect;
    }

    /** Adds a one-shot effect made elsewhere, the code impacts. */
    static void addOneShot(Effect effect, BundleManifest bundle, BundleMap map) {
        ONE_SHOTS.add(new ParticleRenderer.Draw(effect, bundle, map));
    }

    private static void clear() {
        SOURCES.clear();
        SPARKS.clear();
        ONE_SHOTS.clear();
        level = null;
        generationSequence = -1;
        placementEpoch = -1;
    }

    private static int particleCount() {
        int total = 0;
        for (Source source : SOURCES) {
            if (source.current != null) total += source.current.particles();
            for (Effect effect : source.fading) total += effect.particles();
        }
        for (ParticleRenderer.Draw draw : ONE_SHOTS) total += draw.effect().particles();
        return total;
    }

    @SubscribeEvent
    static void registerCommand(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(literal("src2mc_particles")
            .executes(context -> status(context.getSource()))
            .then(literal("on").executes(context -> { enabled = true; return status(context.getSource()); }))
            .then(literal("off").executes(context -> { enabled = false; clear(); return status(context.getSource()); }))
            .then(literal("range").then(net.minecraft.commands.Commands.argument("blocks", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(16, 1024))
                .executes(context -> { range = com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(context, "blocks"); return status(context.getSource()); })))
            .then(literal("decals")
                .then(literal("on").executes(context -> { DecalRenderer.setEnabled(true); return status(context.getSource()); }))
                .then(literal("off").executes(context -> { DecalRenderer.setEnabled(false); return status(context.getSource()); }))
                .then(literal("clear").executes(context -> { DecalRenderer.clearAll(); return status(context.getSource()); }))));
    }

    private static int status(CommandSourceStack source) {
        long running = SOURCES.stream().filter(s -> s.current != null).count();
        String text = "src2mc particles " + (enabled ? "on" : "off") + ", range " + (int) range + " blocks: " + SOURCES.size()
            + " particle entities, " + running + " running, " + ONE_SHOTS.size() + " one-shot effects, " + particleCount()
            + " particles; " + SPARKS.size() + " spark entities, " + sparks + " sparks; impacts " + Impacts.impacts + " heard, " + Impacts.drawn + " drawn; drawn " + ParticleRenderer.drawnParticles + " particles in " + ParticleRenderer.drawCalls + " draws since start";
        source.sendSuccess(() -> Component.literal(text), false);
        source.sendSuccess(() -> Component.literal(DecalRenderer.status()), false);
        return 1;
    }
}
