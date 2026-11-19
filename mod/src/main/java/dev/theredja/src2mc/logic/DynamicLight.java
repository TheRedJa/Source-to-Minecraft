package dev.theredja.src2mc.logic;

import dev.theredja.src2mc.bundle.LogicTable;
import dev.theredja.src2mc.view.ViewState;
import java.util.Locale;
import net.minecraft.nbt.CompoundTag;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * {@code CDynamicLight} ({@code game/server/dynamiclight.cpp}): {@code light_dynamic}. The server keeps what Source
 * networks (flags, radius, exponent, cone angles, spot radius, colour) and where the light is and points; the clients
 * light with it ({@code c_dynamiclight.cpp}, see {@code client/render/DynamicLights}). Light styles are not carried:
 * the light shines at its plain colour.
 */
final class DynamicLight extends LogicEntity {
    /** {@code dlight.h:22-23}. */
    static final int NO_WORLD = 0x1, NO_MODEL = 0x2;
    /** {@code NUM_DL_EXPONENT_BITS 8}: the exponent is clamped to -127..127 ({@code dynamiclight.cpp:15-17, 172}). */
    private static final int MAX_EXPONENT = 127;
    private static final double UNITS_PER_BLOCK = 32.0;

    private boolean on = true;
    private int flags, actualFlags, exponent, red = 255, green = 255, blue = 255;
    private double radius, innerAngle, outerAngle, spotRadius;
    private boolean changed;
    private LogicEntity target;

    DynamicLight(MapLogic map, int index, LogicTable.Entity entity) { super(map, index, entity); }

    @Override void spawn() {
        radius = number("distance", 0);
        exponent = clampExponent(number("brightness", 0));
        innerAngle = number("_inner_cone", 0);
        outerAngle = number("_cone", 0);
        spotRadius = number("spotlight_radius", 0);
        // KeyValue "spawnflags": m_ActualFlags = m_Flags = atoi (dynamiclight.cpp:106-108); Spawn turns it on.
        actualFlags = flags = spawnflags;
        on = true;
        // "_light" sets the render colour from its first three numbers (UTIL_StringToColor32, :90-94).
        String light = key("_light");
        if (light != null) {
            String[] parts = light.trim().split("\\s+");
            if (parts.length >= 3) {
                red = colorByte(parts[0]);
                green = colorByte(parts[1]);
                blue = colorByte(parts[2]);
            }
        } else {
            red = renderColor >> 16 & 0xFF;
            green = renderColor >> 8 & 0xFF;
            blue = renderColor & 0xFF;
        }
    }

    @Override void activate() {
        String name = key("target", "");
        target = name.isBlank() ? null : map.findFirst(name.trim(), this, null, this);
    }

    @Override boolean accept(String input, String value, Actor activator, LogicEntity caller) {
        switch (input) {
            case "turnon" -> turnOn();
            case "turnoff" -> turnOff();
            case "toggle" -> { if (on) turnOff(); else turnOn(); }
            // DEFINE_INPUT fields (:60-65): the input sets the field.
            case "distance" -> radius = Variant.number(value);
            case "brightness" -> exponent = clampExponent(Variant.number(value));
            case "_inner_cone" -> innerAngle = Variant.number(value);
            case "_cone" -> outerAngle = Variant.number(value);
            case "spotlight_radius" -> spotRadius = Variant.number(value);
            case "style" -> { }
            default -> { return false; }
        }
        changed = true;
        return true;
    }

    private void turnOn() {
        flags = actualFlags;
        on = true;
    }

    private void turnOff() {
        flags = NO_MODEL | NO_WORLD;
        on = false;
    }

    /**
     * What the clients light with, or null while the light lights nothing. With a {@code target} it points at the
     * target ({@code DynamicLightThink}, :188-203, every 0.1 s in Source, here every tick); otherwise along its
     * angles, the {@code pitch} key negated as {@code KeyValue} does (:96-103) when no {@code angles} key sets it.
     */
    ViewState.Light light() {
        if ((flags & (NO_WORLD | NO_MODEL)) == (NO_WORLD | NO_MODEL)) return null;
        ViewState.Pose pose = CameraEntities.viewPose(map, this);
        if (pose == null) return null;
        Vector3d direction = null;
        if (target != null && !target.removed) {
            ViewState.Pose aim = CameraEntities.viewPose(map, target);
            if (aim != null) {
                direction = new Vector3d(aim.x() - pose.x(), aim.y() - pose.y(), aim.z() - pose.z());
                if (direction.lengthSquared() < 1e-12) direction = null;
            }
        }
        if (direction == null) {
            Quaterniond rotation = new Quaterniond(pose.qx(), pose.qy(), pose.qz(), pose.qw());
            if (key("angles") == null && key("pitch") != null) {
                Rigid motion = worldMotion(map.time());
                rotation = MoverPose.angles(-number("pitch", 0), 0, 0);
                if (motion != null) rotation = motion.rotation().mul(rotation);
            }
            // Source's forward (+x) is Minecraft's +x in MoverPose's axes.
            direction = rotation.transform(new Vector3d(1, 0, 0));
        }
        direction.normalize();
        // TexLightToLinear (mathlib.h:975-981, color_conversion.cpp:38): c / 255 * 2^exponent.
        float scale = (float) (Math.pow(2, exponent) / 255.0);
        return new ViewState.Light(index, flags, pose.x(), pose.y(), pose.z(), (float) direction.x, (float) direction.y,
            (float) direction.z, red * scale, green * scale, blue * scale, (float) (radius / UNITS_PER_BLOCK),
            (float) innerAngle, (float) outerAngle, (float) (spotRadius / UNITS_PER_BLOCK));
    }

    private static int clampExponent(double value) { return (int) Math.max(-MAX_EXPONENT, Math.min(MAX_EXPONENT, value)); }

    private static int colorByte(String text) {
        try {
            return Math.max(0, Math.min(255, (int) Double.parseDouble(text)));
        } catch (NumberFormatException e) {
            return 255;
        }
    }

    @Override void save(CompoundTag tag) {
        super.save(tag);
        tag.putBoolean("on", on);
        tag.putInt("flags", flags);
        if (changed) {
            tag.putDouble("radius", radius); tag.putInt("exponent", exponent); tag.putDouble("inner", innerAngle);
            tag.putDouble("outer", outerAngle); tag.putDouble("spot", spotRadius);
        }
    }

    @Override void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("on")) on = tag.getBoolean("on");
        if (tag.contains("flags")) flags = tag.getInt("flags");
        if (tag.contains("radius")) {
            changed = true;
            radius = tag.getDouble("radius"); exponent = tag.getInt("exponent"); innerAngle = tag.getDouble("inner");
            outerAngle = tag.getDouble("outer"); spotRadius = tag.getDouble("spot");
        }
    }

    @Override String state() {
        return String.format(Locale.ROOT, "%s, flags %d, radius %.0f, exponent %d, cone %.0f/%.0f", on ? "on" : "off", flags, radius,
            exponent, innerAngle, outerAngle);
    }
}
