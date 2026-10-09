package dev.theredja.src2mc.logic;

import dev.theredja.src2mc.bundle.LogicTable;
import dev.theredja.src2mc.world.LookState;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;

/**
 * The entities that set how the view looks (format section 24, D30): {@code env_tonemap_controller},
 * {@code env_fog_controller} and {@code color_correction}, by the 2013 SDK's and Alien Swarm's
 * {@code env_tonemap_controller.cpp}, {@code fogcontroller.cpp} and {@code colorcorrection.cpp}.
 * Each keeps what Source networks to the client; {@link LookSync} sends it on.
 */
final class LookEntities {
    private LookEntities() {}

    /** {@code COLOR_CORRECTION_ENT_THINK_RATE}: {@code TICK_INTERVAL}, Source's 66 ticks a second. */
    private static final double THINK_RATE = 0.015;

    /** {@code CEnvTonemapController}. */
    static final class Tonemap extends LogicEntity {
        boolean useMin, useMax, useBloom;
        float min, max, bloom;

        Tonemap(MapLogic map, int index, LogicTable.Entity entity) { super(map, index, entity); }

        @Override boolean accept(String input, String value, Actor activator, LogicEntity caller) {
            switch (input) {
                case "setautoexposuremin" -> { min = (float) Variant.number(value); useMin = true; }
                case "setautoexposuremax" -> { max = (float) Variant.number(value); useMax = true; }
                case "usedefaultautoexposure" -> { useMin = false; useMax = false; }
                case "setbloomscale" -> { bloom = (float) Variant.number(value); useBloom = true; }
                case "usedefaultbloomscale" -> useBloom = false;
                // Sets mat_hdr_manual_tonemap_rate, the client's convar, for good.
                case "settonemaprate" -> map.tonemapRate = (float) Variant.number(value);
                // Both branches parse their argument with sscanf's arguments swapped, so it never applies.
                case "setbloomscalerange" -> { return true; }
                // mat_hdr_tonemapscale, which the auto exposure overwrites every frame; and the
                // exponent and saturation, which only the unused $bloomtype 1 reads.
                case "settonemapscale", "blendtonemapscale", "setbloomexponent", "setbloomsaturation" -> { return true; }
                default -> { return false; }
            }
            // The 2013 client takes the settings of whichever controller changed last.
            map.tonemapInUse = index;
            return true;
        }

        LookState.Tonemap lookState() { return new LookState.Tonemap(index, useMin, min, useMax, max, useBloom, bloom); }

        @Override void save(CompoundTag tag) {
            super.save(tag);
            tag.putBoolean("use_min", useMin); tag.putFloat("min", min);
            tag.putBoolean("use_max", useMax); tag.putFloat("max", max);
            tag.putBoolean("use_bloom", useBloom); tag.putFloat("bloom", bloom);
        }

        @Override void load(CompoundTag tag) {
            super.load(tag);
            useMin = tag.getBoolean("use_min"); min = tag.getFloat("min");
            useMax = tag.getBoolean("use_max"); max = tag.getFloat("max");
            useBloom = tag.getBoolean("use_bloom"); bloom = tag.getFloat("bloom");
        }
    }

    /** {@code CFogController}. */
    static final class Fog extends LogicEntity {
        private static final int LERP_COLOR = 1, LERP_COLOR2 = 2, LERP_START = 4, LERP_END = 8;
        private LookState.Fog fog;
        private int changed;

        Fog(MapLogic map, int index, LogicTable.Entity entity) {
            super(map, index, entity);
            fog = entity == null ? null : LookState.spawnedFog(index, entity);
        }

        @Override boolean accept(String input, String value, Actor activator, LogicEntity caller) {
            LookState.Fog f = fog;
            switch (input) {
                case "setstartdist" -> fog = with(f, f.enable(), f.color(), f.color2(), (float) Variant.number(value), f.end(), f.maxDensity());
                case "setenddist" -> fog = with(f, f.enable(), f.color(), f.color2(), f.start(), (float) Variant.number(value), f.maxDensity());
                case "setmaxdensity" -> fog = with(f, f.enable(), f.color(), f.color2(), f.start(), f.end(), (float) Variant.number(value));
                case "turnon" -> fog = with(f, true, f.color(), f.color2(), f.start(), f.end(), f.maxDensity());
                case "turnoff" -> fog = with(f, false, f.color(), f.color2(), f.start(), f.end(), f.maxDensity());
                case "setcolor" -> fog = with(f, f.enable(), LookState.color(value), f.color2(), f.start(), f.end(), f.maxDensity());
                case "setcolorsecondary" -> fog = with(f, f.enable(), f.color(), LookState.color(value), f.start(), f.end(), f.maxDensity());
                case "setangles" -> {
                    float[] forward = LookState.forward(LookState.vector(value));
                    fog = new LookState.Fog(index, f.enable(), f.blend(), -forward[0], -forward[1], -forward[2], f.color(), f.color2(),
                        f.colorLerp(), f.color2Lerp(), f.start(), f.end(), f.startLerp(), f.endLerp(), f.maxDensity(), f.duration(), f.lerpTime());
                }
                case "setcolorlerpto" -> { changed |= LERP_COLOR; fog = lerpTo(f, LookState.color(value), f.color2Lerp(), f.startLerp(), f.endLerp()); }
                case "setcolorsecondarylerpto" -> { changed |= LERP_COLOR2; fog = lerpTo(f, f.colorLerp(), LookState.color(value), f.startLerp(), f.endLerp()); }
                case "setstartdistlerpto" -> { changed |= LERP_START; fog = lerpTo(f, f.colorLerp(), f.color2Lerp(), (float) Variant.number(value), f.endLerp()); }
                case "setenddistlerpto" -> { changed |= LERP_END; fog = lerpTo(f, f.colorLerp(), f.color2Lerp(), f.startLerp(), (float) Variant.number(value)); }
                case "startfogtransition" -> {
                    fog = at(f, map.time() + f.duration() + 0.1);
                    nextThink = map.time() + f.duration();
                }
                // Far clipping and radial fog are not drawn yet; the inputs exist.
                case "setfarz", "setradial" -> {}
                default -> { return false; }
            }
            return true;
        }

        /** {@code SetLerpValues}: the transition's targets become the values. */
        @Override void think() {
            LookState.Fog f = fog;
            fog = new LookState.Fog(index, f.enable(), f.blend(), f.dirX(), f.dirY(), f.dirZ(),
                (changed & LERP_COLOR) != 0 ? f.colorLerp() : f.color(), (changed & LERP_COLOR2) != 0 ? f.color2Lerp() : f.color2(),
                f.colorLerp(), f.color2Lerp(), (changed & LERP_START) != 0 ? f.startLerp() : f.start(),
                (changed & LERP_END) != 0 ? f.endLerp() : f.end(), f.startLerp(), f.endLerp(), f.maxDensity(), f.duration(), map.time());
            changed = 0;
        }

        private static LookState.Fog with(LookState.Fog f, boolean enable, int color, int color2, float start, float end, float maxDensity) {
            return new LookState.Fog(f.entity(), enable, f.blend(), f.dirX(), f.dirY(), f.dirZ(), color, color2, f.colorLerp(), f.color2Lerp(),
                start, end, f.startLerp(), f.endLerp(), maxDensity, f.duration(), f.lerpTime());
        }

        private static LookState.Fog lerpTo(LookState.Fog f, int color, int color2, float start, float end) {
            return new LookState.Fog(f.entity(), f.enable(), f.blend(), f.dirX(), f.dirY(), f.dirZ(), f.color(), f.color2(), color, color2,
                f.start(), f.end(), start, end, f.maxDensity(), f.duration(), f.lerpTime());
        }

        private static LookState.Fog at(LookState.Fog f, double lerpTime) {
            return new LookState.Fog(f.entity(), f.enable(), f.blend(), f.dirX(), f.dirY(), f.dirZ(), f.color(), f.color2(), f.colorLerp(),
                f.color2Lerp(), f.start(), f.end(), f.startLerp(), f.endLerp(), f.maxDensity(), f.duration(), lerpTime);
        }

        LookState.Fog lookState() { return fog; }

        @Override void save(CompoundTag tag) {
            super.save(tag);
            LookState.Fog f = fog;
            tag.putBoolean("fog_enable", f.enable());
            putFloats(tag, "fog_dir", f.dirX(), f.dirY(), f.dirZ());
            tag.putIntArray("fog_colors", new int[]{f.color(), f.color2(), f.colorLerp(), f.color2Lerp(), changed});
            putFloats(tag, "fog_range", f.start(), f.end(), f.startLerp(), f.endLerp(), f.maxDensity());
            tag.putDouble("fog_lerp_time", f.lerpTime());
        }

        @Override void load(CompoundTag tag) {
            super.load(tag);
            if (!tag.contains("fog_colors")) return;
            float[] dir = floats(tag, "fog_dir", 3), range = floats(tag, "fog_range", 5);
            int[] colors = tag.getIntArray("fog_colors");
            if (colors.length < 5) return;
            changed = colors[4];
            fog = new LookState.Fog(index, tag.getBoolean("fog_enable"), fog.blend(), dir[0], dir[1], dir[2], colors[0], colors[1],
                colors[2], colors[3], range[0], range[1], range[2], range[3], range[4], fog.duration(), tag.getDouble("fog_lerp_time"));
        }
    }

    /** {@code CColorCorrection}: its weight fades in and out on the map's clock. */
    static final class ColorCorrection extends LogicEntity {
        private LookState.Correction spawned;
        private float weight, maxWeight, fadeIn, fadeOut, startFadeIn, startFadeOut;
        private double timeFadeIn, timeFadeOut;
        private boolean enabled, fadingIn, fadingOut;

        ColorCorrection(MapLogic map, int index, LogicTable.Entity entity) {
            super(map, index, entity);
            if (entity == null) return;
            spawned = LookState.spawnedCorrection(index, entity);
            enabled = spawned.enabled();
            weight = spawned.weight();
            maxWeight = (float) number("maxweight", 0);
            fadeIn = (float) number("fadeinduration", 0);
            fadeOut = (float) number("fadeoutduration", 0);
        }

        @Override boolean accept(String input, String value, Actor activator, LogicEntity caller) {
            switch (input) {
                case "enable" -> {
                    enabled = true;
                    if (fadeIn > 0) {
                        timeFadeIn = map.time();
                        startFadeIn = weight;
                        fadingIn = true;
                        nextThink = map.time() + THINK_RATE;
                    } else {
                        weight = maxWeight;
                    }
                }
                case "disable" -> {
                    enabled = false;
                    if (fadeOut > 0) {
                        timeFadeOut = map.time();
                        startFadeOut = weight;
                        fadingOut = true;
                        nextThink = map.time() + THINK_RATE;
                    } else {
                        weight = 0;
                    }
                }
                case "setfadeinduration" -> fadeIn = (float) Variant.number(value);
                case "setfadeoutduration" -> fadeOut = (float) Variant.number(value);
                default -> { return false; }
            }
            return true;
        }

        /** {@code FadeInThink} and {@code FadeOutThink}, each on its own context as Source runs them. */
        @Override void think() {
            boolean again = false;
            if (fadingIn) {
                if (fadeIn <= 0 || weight >= maxWeight || !enabled || maxWeight == 0 || startFadeIn >= maxWeight) {
                    fadingIn = false;
                } else {
                    float time = fadeIn;
                    if (startFadeIn > 0) time = fadeIn * (1 - Math.max(0, Math.min(0.99f, startFadeIn / maxWeight)));
                    float ratio = (float) Math.max(0, Math.min(1, (map.time() - timeFadeIn) / time));
                    startFadeIn = Math.max(0, Math.min(1, startFadeIn));
                    weight = startFadeIn + (maxWeight - startFadeIn) * ratio;
                    again = true;
                }
            }
            if (fadingOut) {
                if (fadeOut <= 0 || weight <= 0 || enabled || maxWeight == 0 || startFadeOut <= 0) {
                    fadingOut = false;
                } else {
                    float time = fadeOut;
                    if (startFadeOut < maxWeight) time = fadeOut * Math.max(0.01f, Math.min(1, startFadeOut / maxWeight));
                    float ratio = (float) Math.max(0, Math.min(1, (map.time() - timeFadeOut) / time));
                    startFadeOut = Math.max(0, Math.min(1, startFadeOut));
                    weight = startFadeOut * (1 - ratio);
                    again = true;
                }
            }
            if (again) nextThink = map.time() + THINK_RATE;
        }

        LookState.Correction lookState() {
            return new LookState.Correction(index, spawned.file(), spawned.x(), spawned.y(), spawned.z(), spawned.minFalloff(),
                spawned.maxFalloff(), weight, enabled);
        }

        @Override void save(CompoundTag tag) {
            super.save(tag);
            putFloats(tag, "cc", weight, fadeIn, fadeOut, startFadeIn, startFadeOut);
            tag.putDouble("cc_fade_in", timeFadeIn);
            tag.putDouble("cc_fade_out", timeFadeOut);
            tag.putBoolean("cc_enabled", enabled);
            tag.putBoolean("cc_fading_in", fadingIn);
            tag.putBoolean("cc_fading_out", fadingOut);
        }

        @Override void load(CompoundTag tag) {
            super.load(tag);
            if (!tag.contains("cc")) return;
            float[] v = floats(tag, "cc", 5);
            weight = v[0]; fadeIn = v[1]; fadeOut = v[2]; startFadeIn = v[3]; startFadeOut = v[4];
            timeFadeIn = tag.getDouble("cc_fade_in");
            timeFadeOut = tag.getDouble("cc_fade_out");
            enabled = tag.getBoolean("cc_enabled");
            fadingIn = tag.getBoolean("cc_fading_in");
            fadingOut = tag.getBoolean("cc_fading_out");
        }
    }

    /**
     * The stand-in for a player that inputs reach: {@code CBasePlayer::InputSetFogController}
     * makes the player follow the named fog controller.
     */
    static final class Player extends LogicEntity {
        private final UUID id;

        Player(MapLogic map, UUID id) {
            super(map, -1, null);
            this.id = id;
        }

        @Override boolean accept(String input, String value, Actor activator, LogicEntity caller) {
            if (!input.equals("setfogcontroller")) return false;
            LogicEntity found = map.findFirst(value, this, activator, caller);
            if (found instanceof Fog) {
                LookState.Player now = map.lookPlayer(id);
                map.setLookPlayer(id, new LookState.Player(found.index, now.tonemap()));
            }
            return true;
        }
    }

    private static void putFloats(CompoundTag tag, String key, float... values) {
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        for (float value : values) list.add(net.minecraft.nbt.FloatTag.valueOf(value));
        tag.put(key, list);
    }

    private static float[] floats(CompoundTag tag, String key, int length) {
        float[] out = new float[length];
        if (tag.get(key) instanceof net.minecraft.nbt.ListTag list) {
            for (int i = 0; i < length && i < list.size(); i++) out[i] = list.getFloat(i);
        }
        return out;
    }
}
