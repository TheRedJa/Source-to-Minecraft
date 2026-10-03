package dev.theredja.src2mc.client.logic;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.bundle.BundleMap;
import dev.theredja.src2mc.bundle.LogicTable;
import dev.theredja.src2mc.logic.Variant;
import dev.theredja.src2mc.network.PlacementNetwork;
import dev.theredja.src2mc.world.MapPlacement;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.debug.DebugRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Draws the map's brush entities in the world, for checking that the logic's volumes sit where
 * the map has them: each brush as the wireframe of its exact shape, coloured by what it is, and
 * labelled with class, name and entity index when close.
 *
 * <p>{@code /src2mc_logic_show triggers|usable|all|off [filter]}: triggers are the volumes
 * players touch (orange; level changes red), usable are buttons and doors (green), all adds
 * every other brush entity (grey). A filter keeps only entities whose name or class contains it.
 */
@EventBusSubscriber(modid = Src2mc.MOD_ID, value = Dist.CLIENT)
public final class LogicOverlay {
    private enum Mode { OFF, TRIGGERS, USABLE, ALL }

    private static final double RANGE = 64, LABEL_RANGE = 16;
    private static Mode mode = Mode.OFF;
    private static String filter = "";

    /** One brush entity's outline: its edges as segments, and where its label goes. */
    private record Shape(int entity, String classname, String label, int colour, float[] edges, double[] centre, double[] bounds) {}

    /** Outlines per logic table; identity keys, as tables are never rebuilt within a generation and their hash walks them. */
    private static final Map<LogicTable, List<Shape>> SHAPES = new IdentityHashMap<>();
    private static long shapesGeneration = -1;

    private LogicOverlay() {}

    @SubscribeEvent
    static void registerCommand(RegisterClientCommandsEvent event) {
        var root = literal("src2mc_logic_show");
        for (Mode each : Mode.values()) {
            String name = each.name().toLowerCase(Locale.ROOT);
            root.then(literal(name)
                .executes(context -> set(context.getSource(), each, ""))
                .then(argument("filter", com.mojang.brigadier.arguments.StringArgumentType.word())
                    .executes(context -> set(context.getSource(), each,
                        com.mojang.brigadier.arguments.StringArgumentType.getString(context, "filter")))));
        }
        event.getDispatcher().register(root);
    }

    private static int set(net.minecraft.commands.CommandSourceStack source, Mode next, String text) {
        mode = next;
        filter = text.toLowerCase(Locale.ROOT);
        source.sendSuccess(() -> Component.literal("src2mc logic overlay: " + next.name().toLowerCase(Locale.ROOT)
            + (filter.isEmpty() ? "" : " matching " + filter)), false);
        return 1;
    }

    @SubscribeEvent
    static void render(RenderLevelStageEvent event) {
        if (mode == Mode.OFF || event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        long generation = Src2mc.bundles().active().sequence();
        if (generation != shapesGeneration) { SHAPES.clear(); shapesGeneration = generation; }
        Camera camera = event.getCamera();
        Vec3 eye = camera.getPosition();
        var dimension = minecraft.level.dimension().location();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        PoseStack pose = event.getPoseStack();
        List<Runnable> labels = new ArrayList<>();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        pose.pushPose();
        pose.translate(-eye.x, -eye.y, -eye.z);
        PoseStack.Pose matrix = pose.last();
        for (MapPlacement placement : PlacementNetwork.clientIndex(dimension).view()) {
            if (eye.x < placement.worldMin().getX() - RANGE || eye.x > placement.worldMax().getX() + RANGE
                || eye.z < placement.worldMin().getZ() - RANGE || eye.z > placement.worldMax().getZ() + RANGE) continue;
            BundleMap map = Src2mc.bundles().active().findMap(placement.campaignId(), placement.mapId()).orElse(null);
            if (map == null || map.logic() == null) continue;
            List<Shape> shapes = SHAPES.computeIfAbsent(map.logic(), LogicOverlay::shapes);
            double tx = placement.translation().getX(), ty = placement.translation().getY(), tz = placement.translation().getZ();
            var movers = Carriers.movers(placement.anchorWorld().asLong());
            float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
            for (Shape shape : shapes) {
                if (!shown(shape)) continue;
                // A volume riding a mover is drawn where the mover has it now.
                var carrier = Carriers.carrier(map.logic(), movers, shape.entity);
                Carriers.Frame frame = carrier == null ? null : Carriers.frame(minecraft.level, carrier, placement, partialTick);
                double[] b = shape.bounds;
                double dx, dy, dz;
                if (frame == null) {
                    dx = Math.max(0, Math.max(b[0] + tx - eye.x, eye.x - b[3] - tx));
                    dy = Math.max(0, Math.max(b[1] + ty - eye.y, eye.y - b[4] - ty));
                    dz = Math.max(0, Math.max(b[2] + tz - eye.z, eye.z - b[5] - tz));
                } else {
                    Vec3 centre = frame.toWorld(new Vec3(shape.centre[0] + tx, shape.centre[1] + ty, shape.centre[2] + tz));
                    dx = centre.x - eye.x; dy = centre.y - eye.y; dz = centre.z - eye.z;
                }
                double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (distance > RANGE) continue;
                int r = shape.colour >> 16 & 255, g = shape.colour >> 8 & 255, bl = shape.colour & 255;
                float[] e = shape.edges;
                for (int i = 0; i + 5 < e.length; i += 6) {
                    float x0 = (float) (e[i] + tx), y0 = (float) (e[i + 1] + ty), z0 = (float) (e[i + 2] + tz);
                    float x1 = (float) (e[i + 3] + tx), y1 = (float) (e[i + 4] + ty), z1 = (float) (e[i + 5] + tz);
                    if (frame != null) {
                        Vec3 a = frame.toWorld(new Vec3(x0, y0, z0)), c = frame.toWorld(new Vec3(x1, y1, z1));
                        x0 = (float) a.x; y0 = (float) a.y; z0 = (float) a.z; x1 = (float) c.x; y1 = (float) c.y; z1 = (float) c.z;
                    }
                    float nx = x1 - x0, ny = y1 - y0, nz = z1 - z0;
                    float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                    if (length == 0) continue;
                    nx /= length; ny /= length; nz /= length;
                    lines.addVertex(matrix, x0, y0, z0).setColor(r, g, bl, 255).setNormal(matrix, nx, ny, nz);
                    lines.addVertex(matrix, x1, y1, z1).setColor(r, g, bl, 255).setNormal(matrix, nx, ny, nz);
                }
                if (distance <= LABEL_RANGE) {
                    Vec3 c = new Vec3(shape.centre[0] + tx, shape.centre[1] + ty, shape.centre[2] + tz);
                    Vec3 at = frame == null ? c : frame.toWorld(c);
                    labels.add(() -> DebugRenderer.renderFloatingText(event.getPoseStack(), buffers, shape.label,
                        at.x, at.y, at.z, shape.colour | 0xFF000000, 0.02F, true, 0, true));
                }
            }
        }
        pose.popPose();
        buffers.endBatch(RenderType.lines());
        for (Runnable label : labels) label.run();
        buffers.endBatch();
    }

    private static boolean shown(Shape shape) {
        boolean kind = switch (mode) {
            case TRIGGERS -> shape.classname.startsWith("trigger_");
            case USABLE -> shape.colour == USABLE;
            case ALL -> true;
            case OFF -> false;
        };
        return kind && (filter.isEmpty() || shape.label.toLowerCase(Locale.ROOT).contains(filter));
    }

    private static final int TRIGGER = 0xFF9933, CHANGELEVEL = 0xFF3333, USABLE = 0x33FF55, OTHER = 0xAAAAAA;

    private static List<Shape> shapes(LogicTable table) {
        List<Shape> shapes = new ArrayList<>();
        for (int i = 0; i < table.entities().size(); i++) {
            LogicTable.Entity entity = table.entities().get(i);
            if (entity.volume() < 0) continue;
            LogicTable.Volume volume = table.volumes().get(entity.volume());
            List<Float> edges = new ArrayList<>();
            for (double[][] brush : volume.brushes()) edges(brush, edges);
            float[] array = new float[edges.size()];
            for (int k = 0; k < array.length; k++) array[k] = edges.get(k);
            double[] b = volume.bounds();
            String name = entity.targetname();
            String label = entity.classname() + (name == null ? "" : " \"" + name + "\"") + " #" + i
                + (Variant.bool(entity.value("startdisabled")) ? " (starts disabled)" : "");
            shapes.add(new Shape(i, entity.classname(), label, colour(entity), array,
                new double[]{(b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2}, b));
        }
        return List.copyOf(shapes);
    }

    private static int colour(LogicTable.Entity entity) {
        String classname = entity.classname();
        if (classname.equals("trigger_changelevel")) return CHANGELEVEL;
        if (classname.startsWith("trigger_")) return TRIGGER;
        int flags = Variant.integer(entity.value("spawnflags"));
        boolean usable = switch (classname) {
            case "func_button", "func_rot_button", "momentary_rot_button" -> (flags & 1024) != 0;
            case "func_door", "func_door_rotating", "infra_button" -> (flags & 256) != 0;
            default -> false;
        };
        return usable ? USABLE : OTHER;
    }

    /**
     * The edges of a convex brush given as planes: its corners are where three planes meet inside
     * all the others, and an edge joins two corners that share two planes.
     */
    static void edges(double[][] planes, List<Float> out) {
        int n = planes.length;
        List<double[]> corners = new ArrayList<>();
        List<long[]> onPlanes = new ArrayList<>();
        for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++) for (int k = j + 1; k < n; k++) {
            double[] p = meet(planes[i], planes[j], planes[k]);
            if (p == null || !inside(planes, p)) continue;
            int found = -1;
            for (int c = 0; c < corners.size(); c++) if (close(corners.get(c), p)) { found = c; break; }
            if (found < 0) { corners.add(p); onPlanes.add(new long[]{0}); found = corners.size() - 1; }
            if (n <= 64) onPlanes.get(found)[0] |= (1L << i) | (1L << j) | (1L << k);
        }
        for (int a = 0; a < corners.size(); a++) for (int b = a + 1; b < corners.size(); b++) {
            if (Long.bitCount(onPlanes.get(a)[0] & onPlanes.get(b)[0]) < 2) continue;
            double[] p = corners.get(a), q = corners.get(b);
            out.add((float) p[0]); out.add((float) p[1]); out.add((float) p[2]);
            out.add((float) q[0]); out.add((float) q[1]); out.add((float) q[2]);
        }
    }

    private static double[] meet(double[] a, double[] b, double[] c) {
        double[] bc = cross(b, c), ca = cross(c, a), ab = cross(a, b);
        double det = a[0] * bc[0] + a[1] * bc[1] + a[2] * bc[2];
        if (Math.abs(det) < 1e-9) return null;
        return new double[]{(a[3] * bc[0] + b[3] * ca[0] + c[3] * ab[0]) / det,
            (a[3] * bc[1] + b[3] * ca[1] + c[3] * ab[1]) / det, (a[3] * bc[2] + b[3] * ca[2] + c[3] * ab[2]) / det};
    }

    private static double[] cross(double[] u, double[] v) {
        return new double[]{u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0]};
    }

    private static boolean inside(double[][] planes, double[] p) {
        for (double[] plane : planes) if (plane[0] * p[0] + plane[1] * p[1] + plane[2] * p[2] > plane[3] + 1e-4) return false;
        return true;
    }

    private static boolean close(double[] a, double[] b) {
        return Math.abs(a[0] - b[0]) < 1e-4 && Math.abs(a[1] - b[1]) < 1e-4 && Math.abs(a[2] - b[2]) < 1e-4;
    }
}
