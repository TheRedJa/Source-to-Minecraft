package dev.theredja.src2mc.view;

import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;

/**
 * What a map's camera entities network to the players of its dimension: its {@code script_intro} (Half-Life 2's
 * two-view intro director) and its {@code point_camera}s with the {@code func_monitor}s showing them. Times are game
 * ticks as doubles, so a client works blends and fades out from its own game time, as Source's client does from
 * {@code curtime}.
 */
public record ViewState(Intro intro, List<Camera> cameras, List<Light> lights) {
    public static final ViewState EMPTY = new ViewState(null, List.of(), List.of());
    private static final int MAX_CAMERAS = 256, MAX_MONITORS = 1024, MAX_LIGHTS = 1024;

    public ViewState {
        cameras = List.copyOf(cameras);
        lights = List.copyOf(lights);
    }

    public boolean isEmpty() { return intro == null && cameras.isEmpty() && lights.isEmpty(); }

    /**
     * A {@code light_dynamic} that lights something now ({@code c_dynamiclight.cpp} {@code ClientThink}): map-local
     * position in blocks, the unit direction it points along in Minecraft's axes, its linear colour
     * ({@code _light} / 255 times 2 to the {@code brightness}), {@code distance} and {@code spotlight_radius} in
     * blocks, the cone half-angles in degrees ({@code outer} 0 for a point light) and the dlight flags
     * ({@code 1} no world, {@code 2} no model illumination).
     */
    public record Light(int entity, int flags, double x, double y, double z, float dirX, float dirY, float dirZ,
                        float red, float green, float blue, float radius, float inner, float outer, float spotRadius) {}

    /**
     * Where a camera entity is: map-local block coordinates and the rotation, in Minecraft's axes, that takes
     * Source's forward (+x), left and up axes to the entity's ({@code MoverPose.angles} of its absolute angles).
     */
    public record Pose(double x, double y, double z, float qx, float qy, float qz, float qw) {}

    /**
     * {@code C_ScriptIntro}'s networked state. {@code camera} is null while no view entity is set; {@code drawPrimary}
     * is Source's test that the camera view is not the world origin. The blend runs from {@code blendStart} to
     * {@code blendEnd} out of {@code mode} into {@code nextMode}. The primary view's FOV is {@code fov} from
     * {@code fovSet} on, or the ramp from {@code startFov} to {@code nextFov} over {@code fovStart..fovEnd}
     * ({@code spline}: Source's SimpleSpline). The fade goes from alpha {@code fadeFrom} to {@code fadeTo} (0..255)
     * over {@code fadeDuration} seconds from {@code fadeStart}, in colour {@code red, green, blue} (0..255).
     */
    public record Intro(int entity, boolean active, Pose camera, boolean drawPrimary, int mode, int nextMode, double blendStart,
                        double blendEnd, int fov, double fovSet, int startFov, int nextFov, double fovStart, double fovEnd, boolean spline,
                        float fadeFrom, float fadeTo, double fadeStart, float fadeDuration, float red, float green, float blue) {}

    /**
     * A {@code point_camera}: its place, its horizontal FOV in Source degrees, its fog override ({@code fogColor}
     * 0xRRGGBB) and whether it keeps the screen's aspect ratio; {@code monitors} are the entity indices of the
     * {@code func_monitor}s linked to it.
     */
    public record Camera(int entity, boolean active, Pose pose, float fov, boolean fogEnable, int fogColor, float fogStart,
                         float fogEnd, float fogMaxDensity, boolean screenAspect, int[] monitors) {
        public Camera {
            monitors = monitors.clone();
        }

        @Override public int[] monitors() { return monitors.clone(); }

        @Override public boolean equals(Object other) {
            return other instanceof Camera c && entity == c.entity && active == c.active && java.util.Objects.equals(pose, c.pose)
                && Float.compare(fov, c.fov) == 0 && fogEnable == c.fogEnable && fogColor == c.fogColor
                && Float.compare(fogStart, c.fogStart) == 0 && Float.compare(fogEnd, c.fogEnd) == 0
                && Float.compare(fogMaxDensity, c.fogMaxDensity) == 0 && screenAspect == c.screenAspect
                && java.util.Arrays.equals(monitors, c.monitors);
        }

        @Override public int hashCode() { return entity * 31 + java.util.Arrays.hashCode(monitors); }
    }

    public static void write(FriendlyByteBuf buffer, ViewState state) {
        buffer.writeBoolean(state.intro != null);
        if (state.intro != null) {
            Intro i = state.intro;
            buffer.writeVarInt(i.entity);
            buffer.writeByte((i.active ? 1 : 0) | (i.drawPrimary ? 2 : 0) | (i.spline ? 4 : 0) | (i.camera != null ? 8 : 0));
            if (i.camera != null) writePose(buffer, i.camera);
            buffer.writeVarInt(i.mode); buffer.writeVarInt(i.nextMode + 1);
            buffer.writeDouble(i.blendStart); buffer.writeDouble(i.blendEnd);
            buffer.writeVarInt(i.fov); buffer.writeDouble(i.fovSet);
            buffer.writeVarInt(i.startFov); buffer.writeVarInt(i.nextFov);
            buffer.writeDouble(i.fovStart); buffer.writeDouble(i.fovEnd);
            buffer.writeFloat(i.fadeFrom); buffer.writeFloat(i.fadeTo); buffer.writeDouble(i.fadeStart); buffer.writeFloat(i.fadeDuration);
            buffer.writeFloat(i.red); buffer.writeFloat(i.green); buffer.writeFloat(i.blue);
        }
        buffer.writeVarInt(state.cameras.size());
        for (Camera c : state.cameras) {
            buffer.writeVarInt(c.entity);
            buffer.writeByte((c.active ? 1 : 0) | (c.fogEnable ? 2 : 0) | (c.screenAspect ? 4 : 0));
            writePose(buffer, c.pose);
            buffer.writeFloat(c.fov);
            buffer.writeInt(c.fogColor); buffer.writeFloat(c.fogStart); buffer.writeFloat(c.fogEnd); buffer.writeFloat(c.fogMaxDensity);
            buffer.writeVarIntArray(c.monitors);
        }
        buffer.writeVarInt(state.lights.size());
        for (Light l : state.lights) {
            buffer.writeVarInt(l.entity);
            buffer.writeByte(l.flags & 0xFF);
            buffer.writeDouble(l.x); buffer.writeDouble(l.y); buffer.writeDouble(l.z);
            buffer.writeFloat(l.dirX); buffer.writeFloat(l.dirY); buffer.writeFloat(l.dirZ);
            buffer.writeFloat(l.red); buffer.writeFloat(l.green); buffer.writeFloat(l.blue);
            buffer.writeFloat(l.radius); buffer.writeFloat(l.inner); buffer.writeFloat(l.outer); buffer.writeFloat(l.spotRadius);
        }
    }

    public static ViewState read(FriendlyByteBuf buffer) {
        Intro intro = null;
        if (buffer.readBoolean()) {
            int entity = buffer.readVarInt();
            int flags = buffer.readUnsignedByte();
            Pose camera = (flags & 8) != 0 ? readPose(buffer) : null;
            int mode = buffer.readVarInt(), nextMode = buffer.readVarInt() - 1;
            double blendStart = buffer.readDouble(), blendEnd = buffer.readDouble();
            int fov = buffer.readVarInt();
            double fovSet = buffer.readDouble();
            int startFov = buffer.readVarInt(), nextFov = buffer.readVarInt();
            double fovStart = buffer.readDouble(), fovEnd = buffer.readDouble();
            float fadeFrom = buffer.readFloat(), fadeTo = buffer.readFloat();
            double fadeStart = buffer.readDouble();
            float fadeDuration = buffer.readFloat();
            float red = buffer.readFloat(), green = buffer.readFloat(), blue = buffer.readFloat();
            intro = new Intro(entity, (flags & 1) != 0, camera, (flags & 2) != 0, mode, nextMode, blendStart, blendEnd, fov, fovSet,
                startFov, nextFov, fovStart, fovEnd, (flags & 4) != 0, fadeFrom, fadeTo, fadeStart, fadeDuration, red, green, blue);
        }
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_CAMERAS) throw new DecoderException("view state with " + count + " cameras");
        List<Camera> cameras = new ArrayList<>(count);
        for (int n = 0; n < count; n++) {
            int entity = buffer.readVarInt();
            int flags = buffer.readUnsignedByte();
            Pose pose = readPose(buffer);
            float fov = buffer.readFloat();
            int fogColor = buffer.readInt();
            float fogStart = buffer.readFloat(), fogEnd = buffer.readFloat(), fogMaxDensity = buffer.readFloat();
            int[] monitors = buffer.readVarIntArray(MAX_MONITORS);
            cameras.add(new Camera(entity, (flags & 1) != 0, pose, fov, (flags & 2) != 0, fogColor, fogStart, fogEnd, fogMaxDensity,
                (flags & 4) != 0, monitors));
        }
        int lightCount = buffer.readVarInt();
        if (lightCount < 0 || lightCount > MAX_LIGHTS) throw new DecoderException("view state with " + lightCount + " lights");
        List<Light> lights = new ArrayList<>(lightCount);
        for (int n = 0; n < lightCount; n++) {
            lights.add(new Light(buffer.readVarInt(), buffer.readUnsignedByte(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                buffer.readFloat(), buffer.readFloat(), buffer.readFloat(), buffer.readFloat(), buffer.readFloat(), buffer.readFloat(),
                buffer.readFloat(), buffer.readFloat(), buffer.readFloat(), buffer.readFloat()));
        }
        return new ViewState(intro, cameras, lights);
    }

    private static void writePose(FriendlyByteBuf buffer, Pose pose) {
        buffer.writeDouble(pose.x()); buffer.writeDouble(pose.y()); buffer.writeDouble(pose.z());
        buffer.writeFloat(pose.qx()); buffer.writeFloat(pose.qy()); buffer.writeFloat(pose.qz()); buffer.writeFloat(pose.qw());
    }

    private static Pose readPose(FriendlyByteBuf buffer) {
        return new Pose(buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readFloat(), buffer.readFloat(),
            buffer.readFloat(), buffer.readFloat());
    }
}
