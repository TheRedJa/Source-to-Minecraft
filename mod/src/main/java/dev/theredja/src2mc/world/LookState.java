package dev.theredja.src2mc.world;

import dev.theredja.src2mc.bundle.LogicTable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;

/**
 * What a map's entities say about how its view looks (format section 24 holds the rest): its
 * {@code env_tonemap_controller}s, {@code env_fog_controller}s and {@code color_correction}s as
 * Source networks them to the client, and which controller each player follows. The server's
 * entities produce it as the logic runs; a map whose logic is not running looks as its
 * entities spawn ({@link #initial}). Positions are map-local blocks, distances Source units,
 * directions Minecraft axes, colours {@code 0xRRGGBB} as the keyvalues give them.
 */
public final class LookState {
    private LookState() {}

    /** {@code CEnvTonemapController}'s networked fields. */
    public record Tonemap(int entity, boolean useMin, float min, boolean useMax, float max, boolean useBloom, float bloom) {
        public static Tonemap spawned(int entity) { return new Tonemap(entity, false, 0, false, 0, false, 0); }
    }

    /**
     * {@code fogparams_t}: a transition runs from {@code start}/{@code end}/{@code color} to the
     * {@code LerpTo} values until {@code lerpTime}, a map time, over {@code duration} seconds.
     */
    public record Fog(int entity, boolean enable, boolean blend, float dirX, float dirY, float dirZ,
                      int color, int color2, int colorLerp, int color2Lerp, float start, float end,
                      float startLerp, float endLerp, float maxDensity, float duration, double lerpTime) {}

    /** {@code CColorCorrection}'s networked fields; {@code file} as the entity names its lookup. */
    public record Correction(int entity, String file, double x, double y, double z, float minFalloff, float maxFalloff,
                             float weight, boolean enabled) {}

    /** The controllers a player follows: entity indices, -1 for none. */
    public record Player(int fog, int tonemap) {}

    /**
     * A map's whole view state. {@code tonemapInUse} is the 2013 engine's last changed
     * controller (-1 for none yet), {@code rate} {@code mat_hdr_manual_tonemap_rate} as the map
     * last set it, {@code masterFog}/{@code masterTonemap} what a player follows until told otherwise.
     */
    public record View(List<Tonemap> tonemaps, int tonemapInUse, float rate, List<Fog> fogs, int masterFog,
                       int masterTonemap, List<Correction> corrections, Map<UUID, Player> players) {
        public Fog fog(int entity) {
            for (Fog fog : fogs) if (fog.entity() == entity) return fog;
            return null;
        }

        public Tonemap tonemap(int entity) {
            for (Tonemap tonemap : tonemaps) if (tonemap.entity() == entity) return tonemap;
            return null;
        }

        /** The controllers {@code player} follows: its own, or the master ones. */
        public Player of(UUID player) {
            Player own = players.get(player);
            return own != null ? own : new Player(masterFog, masterTonemap);
        }
    }

    /** {@code mat_hdr_manual_tonemap_rate}'s default. */
    public static final float DEFAULT_RATE = 1.0f;

    // ---- Spawning from keyvalues, as the server's entities and a map without running logic both do.

    /** A keyvalue as Source applies repeated ones: the last wins; null when absent. */
    public static String key(LogicTable.Entity entity, String key) {
        String found = null;
        for (String[] pair : entity.keyvalues()) if (pair[0].equalsIgnoreCase(key)) found = pair[1];
        return found;
    }

    /** {@code atof}: the leading number, 0 for none. */
    public static double number(String value, double fallback) {
        if (value == null) return fallback;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\s*[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?").matcher(value);
        return m.find() ? Double.parseDouble(m.group().trim()) : 0;
    }

    /** {@code UTIL_StringToColor32} without the alpha: {@code "r g b"} to {@code 0xRRGGBB}. */
    public static int color(String value) {
        String[] parts = value == null ? new String[0] : value.trim().split("\\s+");
        int out = 0;
        for (int i = 0; i < 3; i++) out = out << 8 | (i < parts.length ? (int) number(parts[i], 0) & 0xFF : 0);
        return out;
    }

    /** {@code UTIL_StringToVector} of three. */
    public static double[] vector(String value) {
        String[] parts = value == null ? new String[0] : value.trim().split("\\s+");
        double[] out = new double[3];
        for (int i = 0; i < 3 && i < parts.length; i++) out[i] = number(parts[i], 0);
        return out;
    }

    /** {@code AngleVectors}' forward of Source angles {@code pitch yaw roll}, in Minecraft axes. */
    public static float[] forward(double[] angles) {
        double pitch = Math.toRadians(angles[0]), yaw = Math.toRadians(angles[1]);
        double sx = Math.cos(pitch) * Math.cos(yaw), sy = Math.cos(pitch) * Math.sin(yaw), sz = -Math.sin(pitch);
        return new float[]{(float) sx, (float) sz, (float) -sy};
    }

    /** A Source direction in Minecraft axes. */
    public static float[] axes(double[] source) {
        return new float[]{(float) source[0], (float) source[2], (float) -source[1]};
    }

    /** {@code CFogController} as it spawns and activates. */
    public static Fog spawnedFog(int index, LogicTable.Entity entity) {
        float[] dir;
        if (number(key(entity, "use_angles"), 0) != 0) {
            float[] f = forward(vector(key(entity, "angles")));
            dir = new float[]{-f[0], -f[1], -f[2]};
        } else {
            dir = axes(vector(key(entity, "fogdir")));
        }
        int color = color(key(entity, "fogcolor")), color2 = color(key(entity, "fogcolor2"));
        float start = (float) number(key(entity, "fogstart"), 0), end = (float) number(key(entity, "fogend"), 0);
        return new Fog(index, number(key(entity, "fogenable"), 0) != 0, number(key(entity, "fogblend"), 0) != 0,
            dir[0], dir[1], dir[2], color, color2, color, color2, start, end, start, end,
            (float) number(key(entity, "fogmaxdensity"), 1), (float) number(key(entity, "foglerptime"), 0), 0);
    }

    /** {@code CColorCorrection::Spawn}: weight 1 unless it starts disabled. */
    public static Correction spawnedCorrection(int index, LogicTable.Entity entity) {
        boolean enabled = number(key(entity, "startdisabled"), 0) == 0;
        double[] origin = entity.origin() == null ? new double[3] : entity.origin();
        return new Correction(index, key(entity, "filename") == null ? "" : key(entity, "filename"), origin[0], origin[1], origin[2],
            (float) number(key(entity, "minfalloff"), 0), (float) number(key(entity, "maxfalloff"), 0), enabled ? 1 : 0, enabled);
    }

    /** {@code spawnflags} bit 1 of a fog or tonemap controller: the master. */
    public static boolean master(LogicTable.Entity entity) {
        return ((int) number(key(entity, "spawnflags"), 0) & 1) != 0;
    }

    /** The view of a map as its entities spawn, for a map whose logic is not running. */
    public static View initial(LogicTable table) {
        List<Tonemap> tonemaps = new ArrayList<>();
        List<Fog> fogs = new ArrayList<>();
        List<Correction> corrections = new ArrayList<>();
        int masterFog = -1, masterTonemap = -1;
        for (int i = 0; i < table.entities().size(); i++) {
            LogicTable.Entity entity = table.entities().get(i);
            switch (entity.classname().toLowerCase(Locale.ROOT)) {
                case "env_tonemap_controller" -> {
                    tonemaps.add(Tonemap.spawned(i));
                    if (masterTonemap < 0 || master(entity)) masterTonemap = i;
                }
                case "env_fog_controller" -> {
                    fogs.add(spawnedFog(i, entity));
                    if (masterFog < 0 || master(entity)) masterFog = i;
                }
                case "color_correction" -> corrections.add(spawnedCorrection(i, entity));
                default -> {}
            }
        }
        return new View(tonemaps, -1, DEFAULT_RATE, fogs, masterFog, masterTonemap, corrections, Map.of());
    }

    // ---- Wire format.

    public static void write(FriendlyByteBuf buf, View view) {
        buf.writeVarInt(view.tonemaps().size());
        for (Tonemap t : view.tonemaps()) {
            buf.writeVarInt(t.entity());
            buf.writeBoolean(t.useMin()); buf.writeFloat(t.min());
            buf.writeBoolean(t.useMax()); buf.writeFloat(t.max());
            buf.writeBoolean(t.useBloom()); buf.writeFloat(t.bloom());
        }
        buf.writeVarInt(view.tonemapInUse());
        buf.writeFloat(view.rate());
        buf.writeVarInt(view.fogs().size());
        for (Fog f : view.fogs()) {
            buf.writeVarInt(f.entity());
            buf.writeBoolean(f.enable()); buf.writeBoolean(f.blend());
            buf.writeFloat(f.dirX()); buf.writeFloat(f.dirY()); buf.writeFloat(f.dirZ());
            buf.writeInt(f.color()); buf.writeInt(f.color2()); buf.writeInt(f.colorLerp()); buf.writeInt(f.color2Lerp());
            buf.writeFloat(f.start()); buf.writeFloat(f.end()); buf.writeFloat(f.startLerp()); buf.writeFloat(f.endLerp());
            buf.writeFloat(f.maxDensity()); buf.writeFloat(f.duration()); buf.writeDouble(f.lerpTime());
        }
        buf.writeVarInt(view.masterFog());
        buf.writeVarInt(view.masterTonemap());
        buf.writeVarInt(view.corrections().size());
        for (Correction c : view.corrections()) {
            buf.writeVarInt(c.entity());
            buf.writeUtf(c.file());
            buf.writeDouble(c.x()); buf.writeDouble(c.y()); buf.writeDouble(c.z());
            buf.writeFloat(c.minFalloff()); buf.writeFloat(c.maxFalloff());
            buf.writeFloat(c.weight()); buf.writeBoolean(c.enabled());
        }
        buf.writeVarInt(view.players().size());
        for (Map.Entry<UUID, Player> p : view.players().entrySet()) {
            buf.writeUUID(p.getKey());
            buf.writeVarInt(p.getValue().fog());
            buf.writeVarInt(p.getValue().tonemap());
        }
    }

    public static View read(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<Tonemap> tonemaps = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            tonemaps.add(new Tonemap(buf.readVarInt(), buf.readBoolean(), buf.readFloat(), buf.readBoolean(), buf.readFloat(),
                buf.readBoolean(), buf.readFloat()));
        }
        int inUse = buf.readVarInt();
        float rate = buf.readFloat();
        n = buf.readVarInt();
        List<Fog> fogs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            fogs.add(new Fog(buf.readVarInt(), buf.readBoolean(), buf.readBoolean(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readDouble()));
        }
        int masterFog = buf.readVarInt(), masterTonemap = buf.readVarInt();
        n = buf.readVarInt();
        List<Correction> corrections = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            corrections.add(new Correction(buf.readVarInt(), buf.readUtf(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readBoolean()));
        }
        n = buf.readVarInt();
        Map<UUID, Player> players = new HashMap<>();
        for (int i = 0; i < n; i++) players.put(buf.readUUID(), new Player(buf.readVarInt(), buf.readVarInt()));
        return new View(List.copyOf(tonemaps), inUse, rate, List.copyOf(fogs), masterFog, masterTonemap, List.copyOf(corrections), Map.copyOf(players));
    }
}
