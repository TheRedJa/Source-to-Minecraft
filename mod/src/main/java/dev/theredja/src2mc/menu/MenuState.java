package dev.theredja.src2mc.menu;

import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;

/**
 * What a map's menu entities network to the players inside it: its {@code point_viewcontrol}s and
 * {@code vgui_screen}s. Only changes are sent, so a glide is described by where it started, not by where it is:
 * a client works out the progress from the game time the server sent.
 */
public record MenuState(List<View> views, List<Screen> screens) {
    public static final MenuState EMPTY = new MenuState(List.of(), List.of());
    private static final int MAX_RECORDS = 256, MAX_PATH = 256;

    public MenuState {
        views = List.copyOf(views);
        screens = List.copyOf(screens);
    }

    /**
     * One viewcontrol. While {@code moving}, the camera is at progress {@code t + (reverse ? -1 : 1) * elapsed / seconds}
     * along {@code path}, {@code elapsed} counted from game time {@code startTick}; otherwise it rests at {@code rest}.
     */
    public record View(int entity, boolean enabled, boolean controls, boolean cursor, boolean drawHud, boolean smooth, double seconds,
                       List<ViewPath.Pose> path, double t, boolean reverse, boolean moving, ViewPath.Pose rest, long startTick) {
        public View { path = List.copyOf(path); }

        /** The pose at {@code ticks} (game time with its partial tick), unclamped by anything but the path's ends. */
        public ViewPath.Pose pose(double ticks) {
            if (!moving || path.size() < 2) return rest;
            double elapsed = Math.max(0, ticks - startTick) / 20.0;
            double progress = t + (reverse ? -1 : 1) * elapsed / Math.max(seconds, 1e-6);
            return ViewPath.evaluate(path, progress, smooth);
        }
    }

    /** A {@code vgui_screen}: its entity slot and whether it is shown. */
    public record Screen(int entity, boolean active) {}

    public View view(int entity) {
        for (View view : views) if (view.entity() == entity) return view;
        return null;
    }

    public Screen screen(int entity) {
        for (Screen screen : screens) if (screen.entity() == entity) return screen;
        return null;
    }

    public static void write(FriendlyByteBuf buffer, MenuState state) {
        buffer.writeVarInt(state.views.size());
        for (View view : state.views) {
            buffer.writeVarInt(view.entity);
            int flags = (view.enabled ? 1 : 0) | (view.controls ? 2 : 0) | (view.cursor ? 4 : 0) | (view.drawHud ? 8 : 0)
                | (view.smooth ? 16 : 0) | (view.reverse ? 32 : 0) | (view.moving ? 64 : 0);
            buffer.writeByte(flags);
            buffer.writeDouble(view.seconds);
            buffer.writeDouble(view.t);
            buffer.writeLong(view.startTick);
            writePose(buffer, view.rest);
            buffer.writeVarInt(view.path.size());
            for (ViewPath.Pose pose : view.path) writePose(buffer, pose);
        }
        buffer.writeVarInt(state.screens.size());
        for (Screen screen : state.screens) {
            buffer.writeVarInt(screen.entity);
            buffer.writeBoolean(screen.active);
        }
    }

    public static MenuState read(FriendlyByteBuf buffer) {
        int viewCount = count(buffer, MAX_RECORDS);
        List<View> views = new ArrayList<>(viewCount);
        for (int i = 0; i < viewCount; i++) {
            int entity = buffer.readVarInt();
            int flags = buffer.readUnsignedByte();
            double seconds = buffer.readDouble(), t = buffer.readDouble();
            long startTick = buffer.readLong();
            ViewPath.Pose rest = readPose(buffer);
            int pathSize = count(buffer, MAX_PATH);
            List<ViewPath.Pose> path = new ArrayList<>(pathSize);
            for (int k = 0; k < pathSize; k++) path.add(readPose(buffer));
            views.add(new View(entity, (flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0, (flags & 8) != 0, (flags & 16) != 0, seconds, path, t,
                (flags & 32) != 0, (flags & 64) != 0, rest, startTick));
        }
        int screenCount = count(buffer, MAX_RECORDS);
        List<Screen> screens = new ArrayList<>(screenCount);
        for (int i = 0; i < screenCount; i++) screens.add(new Screen(buffer.readVarInt(), buffer.readBoolean()));
        return new MenuState(views, screens);
    }

    private static void writePose(FriendlyByteBuf buffer, ViewPath.Pose pose) {
        buffer.writeDouble(pose.x()); buffer.writeDouble(pose.y()); buffer.writeDouble(pose.z());
        buffer.writeDouble(pose.pitch()); buffer.writeDouble(pose.yaw()); buffer.writeDouble(pose.roll()); buffer.writeDouble(pose.fov());
    }

    private static ViewPath.Pose readPose(FriendlyByteBuf buffer) {
        return new ViewPath.Pose(buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
            buffer.readDouble(), buffer.readDouble());
    }

    private static int count(FriendlyByteBuf buffer, int max) {
        int count = buffer.readVarInt();
        if (count < 0 || count > max) throw new DecoderException("invalid src2mc menu record count " + count);
        return count;
    }
}
