package dev.theredja.src2mc.logic;

import dev.theredja.src2mc.bundle.LogicTable;
import dev.theredja.src2mc.view.IntroMath;
import dev.theredja.src2mc.view.ViewState;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * Entities that look at the map from somewhere other than the player: Half-Life 2's {@code script_intro}, the
 * {@code point_camera}s and the {@code func_monitor}s showing them. The server keeps their state as Source's server
 * does and networks it ({@link ViewState}); the clients draw the views.
 */
final class CameraEntities {
    private CameraEntities() {}

    /** Source's default player FOV, {@code default_fov 75}: what {@code SetFOVBlendTime} starts from when no FOV is set. */
    static final int DEFAULT_PLAYER_FOV = 75;

    /**
     * Where {@code entity} is now, as Source's {@code GetAbsOrigin}/{@code GetAbsAngles}: its compiled origin and
     * {@code angles} carried by its own and its parents' moves. Null for an entity with no position.
     */
    static ViewState.Pose viewPose(MapLogic map, LogicEntity entity) {
        double[] origin = entity.position();
        if (origin == null) return null;
        double[] angles = MoverPose.parseAngles(entity.key("angles", "0 0 0"));
        Quaterniond rotation = MoverPose.angles(angles[0], angles[1], angles[2]);
        Vector3d at = new Vector3d(origin[0], origin[1], origin[2]);
        Rigid motion = entity.worldMotion(map.time());
        if (motion != null) {
            at = motion.apply(at);
            rotation = motion.rotation().mul(rotation);
        }
        rotation.normalize();
        return new ViewState.Pose(at.x, at.y, at.z, (float) rotation.x, (float) rotation.y, (float) rotation.z, (float) rotation.w);
    }

    /** The game tick at which map time {@code t} falls, as of game tick {@code now} (map time {@code map.time()}). */
    static double tick(MapLogic map, long now, double t) {
        return now + (t - map.time()) / MapLogic.TICK_SECONDS;
    }

    /**
     * {@code CScriptIntro} (game/server/hl2/script_intro.cpp): blends a camera entity's view with the player's own,
     * changes the camera view's FOV and fades the screen. The blend, FOV ramp and fade are kept as Source's server
     * and client keep them; the clients evaluate them per frame.
     */
    static final class ScriptIntro extends LogicEntity {
        private boolean active, spline;
        private LogicEntity camera;
        private int mode, nextMode = -1, queuedMode = -1, queuedNextMode = -1;
        private double blendStart, blendEnd;
        private boolean blendPending;
        private int fov, startFov, nextFov;
        /** When {@code fov} last changed, and the FOV ramp: map times; -1 for never. */
        private double fovSet = -1, fovStart = -1, fovEnd = -1;
        /** The fade as Source's client runs it: from {@code fadeFrom} at {@code fadeStart} to {@code fadeAlpha}. */
        private float fadeAlpha, fadeFrom, fadeDuration;
        private double fadeStart = -1;
        private float red, green, blue;

        ScriptIntro(MapLogic map, int index, LogicTable.Entity entity) { super(map, index, entity); }

        @Override void spawn() { spline = flag("alternatefovchange"); }

        @Override boolean accept(String input, String value, Actor activator, LogicEntity caller) {
            double now = map.time();
            switch (input) {
                case "setcameraviewentity" -> {
                    if (value == null || value.isBlank()) return true;
                    LogicEntity found = map.findFirst(value, this, activator, caller);
                    if (found != null) camera = found;
                }
                case "setblendmode" -> {
                    mode = nextMode = Variant.integer(value);
                    blendStart = blendEnd = now;
                    queuedMode = -1;
                    blendPending = false;
                }
                case "setnextblendmode" -> queuedNextMode = Variant.integer(value);
                case "setnextfov" -> nextFov = Variant.integer(value);
                case "setfovblendtime" -> {
                    if (fovEnd >= now) startFov = (int) calculateFov(now);
                    else startFov = fov != 0 ? fov : DEFAULT_PLAYER_FOV;
                    fovEnd = now + Variant.number(value);
                    fovStart = now;
                }
                case "setfov" -> {
                    fov = Variant.integer(value);
                    startFov = fov;
                    fovSet = now;
                }
                case "setnextblendtime" -> {
                    blendEnd = now + Variant.number(value);
                    blendStart = now;
                    if (queuedMode >= 0) mode = queuedMode;
                    if (queuedNextMode < 0) queuedNextMode = mode;
                    nextMode = queuedNextMode;
                    queuedNextMode = -1;
                    queuedMode = nextMode;
                    blendPending = true;
                    nextThink = blendEnd;
                }
                case "activate" -> active = true;
                case "deactivate" -> active = false;
                case "fadeto" -> fadeTo(value, now);
                case "setfadecolor" -> {
                    String[] parts = value == null ? new String[0] : value.trim().split("\\s+");
                    if (parts.length < 3) return true;
                    red = (float) Variant.number(parts[0]);
                    green = (float) Variant.number(parts[1]);
                    blue = (float) Variant.number(parts[2]);
                }
                default -> { return false; }
            }
            return true;
        }

        /**
         * {@code InputFadeTo}: "alpha duration". The client starts a fade only when the alpha changes, from the alpha
         * it shows then, over the duration or 0.01 s for none (c_script_intro.cpp PostDataUpdate).
         */
        private void fadeTo(String value, double now) {
            String[] parts = value == null ? new String[0] : value.trim().split("\\s+");
            if (parts.length < 2 || parts[0].isEmpty()) return;
            float alpha = (float) Variant.number(parts[0]);
            float duration = (float) Variant.number(parts[1]);
            if (alpha != fadeAlpha) {
                fadeFrom = currentFade(now);
                fadeStart = now;
                fadeDuration = duration == 0 ? 0.01f : duration;
            }
            fadeAlpha = alpha;
        }

        /** {@code CalculateAlpha}: {@code RemapValClamped(curtime, start, start + duration, from, to)}. */
        private float currentFade(double now) {
            if (fadeStart < 0) return fadeAlpha;
            double t = fadeDuration <= 0 ? 1 : Math.min(1, Math.max(0, (now - fadeStart) / fadeDuration));
            return (float) (fadeFrom + (fadeAlpha - fadeFrom) * t);
        }

        /** {@code ScriptInfo_CalculateFOV} (game/shared/script_intro_shared.cpp). */
        private double calculateFov(double now) {
            return IntroMath.fov(now, fovStart, fovEnd, startFov, nextFov, spline);
        }

        /** {@code BlendComplete}. */
        @Override void think() {
            if (!blendPending) return;
            mode = nextMode;
            blendStart = blendEnd = map.time();
            queuedMode = -1;
            blendPending = false;
        }

        ViewState.Intro intro(long now) {
            ViewState.Pose view = camera == null || camera.removed ? null : viewPose(map, camera);
            boolean drawPrimary = view != null && !atSourceOrigin(camera);
            return new ViewState.Intro(index, active, view, drawPrimary, mode, nextMode, tick(map, now, blendStart), tick(map, now, blendEnd),
                fov, fovSet < 0 ? -1 : tick(map, now, fovSet), startFov, nextFov, fovStart < 0 ? -1 : tick(map, now, fovStart),
                fovEnd < 0 ? -1 : tick(map, now, fovEnd), spline, fadeFrom, fadeAlpha, fadeStart < 0 ? -1 : tick(map, now, fadeStart),
                fadeDuration, red, green, blue);
        }

        /** Whether Source would see the camera view at the world origin, its test for drawing no primary view. */
        private static boolean atSourceOrigin(LogicEntity entity) {
            if (entity.worldMotion(entity.map.time()) != null) return false;
            String origin = entity.key("origin", "0 0 0").trim();
            for (String part : origin.split("\\s+")) if (Variant.number(part) != 0) return false;
            return true;
        }

        @Override String state() {
            return (active ? "active" : "inactive") + ", camera " + (camera == null ? "none" : camera.describe()) + ", mode " + mode
                + (blendPending ? " -> " + nextMode : "") + ", fov " + fov + ", fade " + fadeAlpha;
        }

        @Override void save(CompoundTag tag) {
            super.save(tag);
            tag.putBoolean("active", active);
            if (camera != null && camera.index >= 0) tag.putInt("camera", camera.index);
            tag.putIntArray("modes", new int[]{mode, nextMode, queuedMode, queuedNextMode, fov, startFov, nextFov});
            tag.putDouble("blend_start", blendStart);
            tag.putDouble("blend_end", blendEnd);
            tag.putBoolean("blend_pending", blendPending);
            tag.putDouble("fov_set", fovSet);
            tag.putDouble("fov_start", fovStart);
            tag.putDouble("fov_end", fovEnd);
            tag.putFloat("fade_alpha", fadeAlpha);
            tag.putFloat("fade_from", fadeFrom);
            tag.putFloat("fade_duration", fadeDuration);
            tag.putDouble("fade_start", fadeStart);
            tag.putFloat("red", red);
            tag.putFloat("green", green);
            tag.putFloat("blue", blue);
        }

        @Override void load(CompoundTag tag) {
            super.load(tag);
            if (!tag.contains("modes")) return;
            active = tag.getBoolean("active");
            camera = tag.contains("camera") ? map.entity(tag.getInt("camera")) : null;
            int[] modes = tag.getIntArray("modes");
            if (modes.length == 7) {
                mode = modes[0]; nextMode = modes[1]; queuedMode = modes[2]; queuedNextMode = modes[3];
                fov = modes[4]; startFov = modes[5]; nextFov = modes[6];
            }
            blendStart = tag.getDouble("blend_start");
            blendEnd = tag.getDouble("blend_end");
            blendPending = tag.getBoolean("blend_pending");
            fovSet = tag.getDouble("fov_set");
            fovStart = tag.getDouble("fov_start");
            fovEnd = tag.getDouble("fov_end");
            fadeAlpha = tag.getFloat("fade_alpha");
            fadeFrom = tag.getFloat("fade_from");
            fadeDuration = tag.getFloat("fade_duration");
            fadeStart = tag.getDouble("fade_start");
            red = tag.getFloat("red");
            green = tag.getFloat("green");
            blue = tag.getFloat("blue");
        }
    }

    /**
     * {@code CPointCamera} (game/server/point_camera.cpp): a view rendered for the {@code func_monitor}s linked to it.
     * {@code ChangeFOV} ramps linearly in 0.05 s steps on the server, as Source's does.
     */
    static final class PointCamera extends LogicEntity {
        private static final int START_OFF = 1;
        private static final double THINK_INTERVAL = 0.05;
        private boolean on;
        private float fov, targetFov, degreesPerSecond;
        private boolean fogEnable, screenAspect;
        private int fogColor;
        private float fogStart, fogEnd, fogMaxDensity;

        PointCamera(MapLogic map, int index, LogicTable.Entity entity) { super(map, index, entity); }

        @Override void spawn() {
            // Source leaves FOV unset when the keyvalue is missing; every camera in the shipped maps sets it.
            fov = (float) number("fov", 90);
            fogEnable = flag("fogenable");
            String[] color = key("fogcolor", "0 0 0").trim().split("\\s+");
            int r = color.length > 0 ? Variant.integer(color[0]) : 0, g = color.length > 1 ? Variant.integer(color[1]) : 0;
            int b = color.length > 2 ? Variant.integer(color[2]) : 0;
            fogColor = (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
            fogStart = (float) number("fogstart", 0);
            fogEnd = (float) number("fogend", 0);
            fogMaxDensity = (float) number("fogmaxdensity", 0);
            screenAspect = flag("usescreenaspectratio");
            on = !hasSpawnFlags(START_OFF);
        }

        private static int clamp(int value) { return Math.max(0, Math.min(255, value)); }

        @Override boolean accept(String input, String value, Actor activator, LogicEntity caller) {
            switch (input) {
                case "changefov" -> changeFov(value);
                case "setonandturnothersoff" -> {
                    for (LogicEntity other : map.entities()) if (other instanceof PointCamera camera && !other.removed) camera.on = false;
                    on = true;
                }
                case "seton" -> on = true;
                case "setoff" -> on = false;
                default -> { return false; }
            }
            return true;
        }

        /** {@code InputChangeFOV}: "fov [time]"; a missing FOV keeps the current one, a missing time is 1 s. */
        private void changeFov(String value) {
            String[] parts = value == null ? new String[0] : value.trim().split("\\s+");
            targetFov = parts.length > 0 && !parts[0].isEmpty() ? (float) Variant.number(parts[0]) : fov;
            double time = parts.length > 1 ? Variant.number(parts[1]) : 1.0;
            degreesPerSecond = (float) ((targetFov - fov) / time);
            nextThink = map.time();
        }

        /** {@code ChangeFOVThink}. */
        @Override void think() {
            float next = fov + degreesPerSecond * (float) THINK_INTERVAL;
            boolean done = degreesPerSecond >= 0 ? next >= targetFov : next <= targetFov;
            if (Float.isNaN(next) || done) {
                fov = targetFov;
                return;
            }
            fov = next;
            nextThink = map.time() + THINK_INTERVAL;
        }

        ViewState.Camera camera(int[] monitors) {
            return new ViewState.Camera(index, on, viewPose(map, this), fov, fogEnable, fogColor, fogStart, fogEnd, fogMaxDensity, screenAspect,
                monitors);
        }

        @Override String state() { return (on ? "on" : "off") + ", fov " + fov; }

        @Override void save(CompoundTag tag) {
            super.save(tag);
            tag.putBoolean("on", on);
            tag.putFloat("fov", fov);
            tag.putFloat("target_fov", targetFov);
            tag.putFloat("degrees_per_second", degreesPerSecond);
        }

        @Override void load(CompoundTag tag) {
            super.load(tag);
            if (!tag.contains("on")) return;
            on = tag.getBoolean("on");
            fov = tag.getFloat("fov");
            targetFov = tag.getFloat("target_fov");
            degreesPerSecond = tag.getFloat("degrees_per_second");
        }
    }

    /**
     * {@code CFuncMonitor} (game/server/hl2/Func_Monitor.cpp): a {@code func_brush} whose {@code _rt_Camera} faces
     * show the camera its {@code target} names; {@code SetCamera} links another.
     */
    static final class Monitor extends Movers.Brush {
        private LogicEntity camera;
        private boolean cameraChanged;

        Monitor(MapLogic map, int index, LogicTable.Entity entity) { super(map, index, entity); }

        @Override void activate() {
            super.activate();
            if (!cameraChanged) link(key("target", ""));
        }

        private void link(String name) {
            camera = null;
            if (name == null || name.isBlank()) return;
            LogicEntity found = map.findFirst(name, this, null, null);
            if (found instanceof PointCamera) camera = found;
        }

        @Override boolean accept(String input, String value, Actor activator, LogicEntity caller) {
            if (input.equals("setcamera")) {
                cameraChanged = true;
                link(value);
                return true;
            }
            return super.accept(input, value, activator, caller);
        }

        LogicEntity camera() { return camera == null || camera.removed || removed ? null : camera; }

        @Override String state() { return super.state() + ", camera " + (camera == null ? "none" : camera.describe()); }

        @Override void save(CompoundTag tag) {
            super.save(tag);
            if (cameraChanged) tag.putInt("camera", camera == null ? -1 : camera.index);
        }

        @Override void load(CompoundTag tag) {
            super.load(tag);
            if (!tag.contains("camera")) return;
            cameraChanged = true;
            int linked = tag.getInt("camera");
            camera = linked >= 0 ? map.entity(linked) : null;
        }
    }

    /** The map's view state: its first {@code script_intro}, every camera with the monitors linked to it, and the lit dynamic lights. */
    static ViewState state(MapLogic map, long now) {
        ViewState.Intro intro = null;
        List<PointCamera> cameras = new ArrayList<>();
        List<Monitor> monitors = new ArrayList<>();
        List<ViewState.Light> lights = new ArrayList<>();
        for (LogicEntity entity : map.entities()) {
            if (entity.removed) continue;
            if (entity instanceof DynamicLight light) {
                ViewState.Light state = light.light();
                if (state != null) lights.add(state);
                continue;
            }
            if (entity instanceof ScriptIntro script && intro == null) intro = script.intro(now);
            else if (entity instanceof PointCamera camera) cameras.add(camera);
            else if (entity instanceof Monitor monitor) monitors.add(monitor);
        }
        List<ViewState.Camera> out = new ArrayList<>();
        for (PointCamera camera : cameras) {
            int[] linked = monitors.stream().filter(m -> m.camera() == camera).mapToInt(m -> m.index).toArray();
            out.add(camera.camera(linked));
        }
        return new ViewState(intro, out, lights);
    }
}
