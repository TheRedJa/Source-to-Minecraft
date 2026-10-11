package dev.theredja.src2mc.logic;

import dev.theredja.src2mc.bundle.LogicTable;
import dev.theredja.src2mc.menu.MenuState;
import dev.theredja.src2mc.menu.ViewPath;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;

/**
 * The entities of INFRA's in-world main menu: the {@code point_viewcontrol} that flies the camera between
 * {@code point_viewcontrol_node}s, and the {@code vgui_screen}s it looks at. The viewcontrol follows INFRA's
 * {@code CINFRAViewControl} as reverse-engineered in {@code source_refs/data/infra_menu_re.md}; the screen
 * follows the SDK's {@code vguiscreen.cpp}. What the player sees of them is drawn by the client from
 * {@link MenuState}, which {@link MenuSync} sends.
 */
final class MenuEntities {
    private MenuEntities() {}

    /** A node chain longer than this is taken to loop. */
    private static final int MAX_NODES = 64;

    /** {@code pitch yaw roll} of an {@code angles} keyvalue; missing parts are 0. */
    static double[] angles(String value) {
        double[] out = new double[3];
        if (value == null) return out;
        String[] parts = value.trim().split("\\s+");
        for (int i = 0; i < 3 && i < parts.length; i++) out[i] = Variant.number(parts[i]);
        return out;
    }

    /**
     * {@code CINFRAViewControl}. "The local player" is every player of the map: Enable and Disable do not
     * look at their activator. The glide is timed here, a tick at a time, for {@code OnEndFollow}; the
     * clients draw it per frame from the start they are sent.
     */
    static final class ViewControl extends LogicEntity {
        private boolean enabled, controls, cursor = true, smooth, drawHud, disableAtEnd, startAtPlayer;
        private double seconds = 1, fov = 90;
        private ViewPath.Pose spawnPose, rest;
        private List<ViewPath.Pose> path = List.of();
        /** Glide time 0..1 as the server has simulated it; {@code baseT} is the part the clients are told, as of {@code startTick}. */
        private double t, baseT;
        private boolean reverse, moving;
        private long startTick = -1;

        ViewControl(MapLogic map, int index, LogicTable.Entity entity) { super(map, index, entity); }

        @Override void spawn() {
            fov = number("fov", 90);
            seconds = Math.max(1e-3, number("pathlengthinseconds", 1));
            smooth = flag("smoothspeed");
            drawHud = flag("drawhud");
            controls = flag("allowcontrols");
            disableAtEnd = flag("disableatend");
            startAtPlayer = flag("startatplayer");
            spawnPose = poseOf(this, fov);
            rest = spawnPose;
        }

        /** An entity's origin and angles as a pose with {@code fov}; the origin is the map's zero when it has none. */
        private static ViewPath.Pose poseOf(LogicEntity entity, double fov) {
            double[] at = entity.position();
            double[] angles = angles(entity.key("angles"));
            return at == null ? new ViewPath.Pose(0, 0, 0, angles[0], angles[1], angles[2], fov)
                : new ViewPath.Pose(at[0], at[1], at[2], angles[0], angles[1], angles[2], fov);
        }

        @Override boolean accept(String input, String value, Actor activator, LogicEntity caller) {
            switch (input) {
                case "enable" -> enable();
                case "disable" -> { enabled = false; moving = false; }
                case "enablecontrols" -> controls = true;
                case "disablecontrols" -> controls = false;
                case "enablecursor" -> cursor = true;
                case "disablecursor" -> cursor = false;
                case "settargetnode" -> setTargetNode(value, activator, caller);
                case "reverse" -> reverse();
                default -> { return false; }
            }
            return true;
        }

        /** Enable: the camera starts at the player's eye (startatplayer) or stays at its own origin and angles. */
        private void enable() {
            enabled = true;
            moving = false;
            path = List.of();
            rest = spawnPose;
            if (startAtPlayer && !map.inside().isEmpty()) {
                ServerPlayer player = map.inside().get(0);
                var translation = map.placement.translation();
                rest = new ViewPath.Pose(player.getEyePosition().x - translation.getX(), player.getEyePosition().y - translation.getY(),
                    player.getEyePosition().z - translation.getZ(), player.getXRot(), -90.0 - player.getYRot(), 0, fov);
            }
            t = 1;
            restart();
        }

        /** Where the camera is now: on the glide, or at rest. */
        private ViewPath.Pose current() { return moving && path.size() > 1 ? ViewPath.evaluate(path, t, smooth) : rest; }

        /** Path point 0 is the camera where it is, with this entity's own fov; then each node along {@code nextnode}. */
        private void setTargetNode(String name, Actor activator, LogicEntity caller) {
            ViewPath.Pose here = current();
            List<ViewPath.Pose> next = new ArrayList<>();
            next.add(new ViewPath.Pose(here.x(), here.y(), here.z(), here.pitch(), here.yaw(), here.roll(), fov));
            String target = name == null ? "" : name.trim();
            if (target.equalsIgnoreCase("!self")) {
                next.add(spawnPose);
            } else if (!target.isEmpty()) {
                Set<LogicEntity> seen = new HashSet<>();
                LogicEntity node = map.findFirst(target, this, activator, caller);
                while (node != null && node != this && seen.add(node) && seen.size() <= MAX_NODES) {
                    next.add(poseOf(node, node.number("fov", fov)));
                    String following = node.key("nextnode");
                    node = following == null || following.isBlank() ? null : map.findFirst(following.trim(), node, activator, caller);
                }
            }
            reverse = false;
            t = 0;
            if (next.size() > 1) {
                path = List.copyOf(next);
                moving = true;
                nextThink = map.time();
            } else {
                // A missing target: no path, the camera stays.
                path = List.of(here);
                rest = here;
                moving = false;
            }
            restart();
        }

        /** Reverse toggles the direction only; with nothing to glide along it arrives at once. */
        private void reverse() {
            reverse = !reverse;
            moving = true;
            nextThink = map.time();
            restart();
        }

        /** The clients' reference point moves to now. */
        private void restart() {
            baseT = t;
            startTick = map.level() == null ? 0 : map.level().getGameTime();
        }

        /**
         * One tick of the glide: {@code t} moves by the frame time over the path length. Arrival fires
         * {@code OnEndFollow} once; INFRA fires it every tick while the camera rests, which a port that
         * only sends changes need not repeat (Enable/DisableControls are idempotent).
         */
        @Override void think() {
            if (!enabled || !moving) return;
            if (path.size() > 1) t += (reverse ? -1 : 1) * MapLogic.TICK_SECONDS / seconds;
            else t = reverse ? 0 : 1;
            if (!reverse && t >= 1 || reverse && t <= 0) {
                arrive();
                return;
            }
            nextThink = map.time();
        }

        private void arrive() {
            ViewPath.Pose end = reverse ? path.get(0) : path.get(path.size() - 1);
            rest = end;
            moving = false;
            // A glide back ends where it began: that point is all the path there is, and t is 1 as INFRA leaves it.
            if (reverse) path = List.of(end);
            reverse = false;
            t = 1;
            restart();
            fire("onendfollow", null, null);
            if (disableAtEnd) enabled = false;
        }

        /** The state the clients are told; {@code now} is the server's game time, taken as the start of a state loaded from a save. */
        MenuState.View view(long now) {
            if (startTick < 0) startTick = now;
            return new MenuState.View(index, enabled, controls, cursor, drawHud, smooth, seconds, path, baseT, reverse, moving, rest, startTick);
        }

        @Override String state() {
            return (enabled ? "enabled" : "disabled") + ", " + (moving ? (reverse ? "gliding back" : "gliding") : "resting") + " t=" + String.format(java.util.Locale.ROOT, "%.2f", t)
                + ", controls " + (controls ? "on" : "off") + ", path of " + path.size();
        }

        @Override void save(CompoundTag tag) {
            super.save(tag);
            tag.putBoolean("enabled", enabled);
            tag.putBoolean("controls", controls);
            tag.putBoolean("cursor", cursor);
            tag.putDouble("t", t);
            tag.putBoolean("reverse", reverse);
            tag.putBoolean("moving", moving);
            tag.put("rest", pose(rest));
            ListTag list = new ListTag();
            for (ViewPath.Pose pose : path) list.add(pose(pose));
            tag.put("path", list);
        }

        @Override void load(CompoundTag tag) {
            super.load(tag);
            if (!tag.contains("enabled")) return;
            enabled = tag.getBoolean("enabled");
            controls = tag.getBoolean("controls");
            cursor = tag.getBoolean("cursor");
            t = tag.getDouble("t");
            baseT = t;
            reverse = tag.getBoolean("reverse");
            moving = tag.getBoolean("moving");
            rest = pose(tag.getList("rest", Tag.TAG_DOUBLE));
            List<ViewPath.Pose> loaded = new ArrayList<>();
            for (Tag item : tag.getList("path", Tag.TAG_LIST)) loaded.add(pose((ListTag) item));
            path = List.copyOf(loaded);
            if (moving && path.size() < 2) moving = false;
            startTick = -1;
            if (moving) nextThink = map.time();
        }

        private static ListTag pose(ViewPath.Pose p) {
            ListTag list = new ListTag();
            for (double value : new double[]{p.x(), p.y(), p.z(), p.pitch(), p.yaw(), p.roll(), p.fov()}) list.add(net.minecraft.nbt.DoubleTag.valueOf(value));
            return list;
        }

        private static ViewPath.Pose pose(ListTag list) {
            if (list.size() != 7) return new ViewPath.Pose(0, 0, 0, 0, 0, 0, 90);
            return new ViewPath.Pose(list.getDouble(0), list.getDouble(1), list.getDouble(2), list.getDouble(3), list.getDouble(4), list.getDouble(5), list.getDouble(6));
        }
    }

    /** {@code CVGuiScreen}: shown from spawn, and by {@code SetActive}/{@code SetInactive}. It has no spawnflags. */
    static final class VguiScreen extends LogicEntity {
        private boolean active = true, changed;

        VguiScreen(MapLogic map, int index, LogicTable.Entity entity) { super(map, index, entity); }

        @Override boolean accept(String input, String value, Actor activator, LogicEntity caller) {
            switch (input) {
                case "setactive" -> active = true;
                case "setinactive" -> active = false;
                default -> { return false; }
            }
            changed = true;
            return true;
        }

        boolean active() { return active; }

        MenuState.Screen screen() { return new MenuState.Screen(index, active); }

        @Override String state() { return active ? "active" : "inactive"; }

        @Override void save(CompoundTag tag) {
            super.save(tag);
            if (changed) tag.putBoolean("active", active);
        }

        @Override void load(CompoundTag tag) {
            super.load(tag);
            if (tag.contains("active")) { active = tag.getBoolean("active"); changed = true; }
        }
    }
}
