package dev.theredja.src2mc.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.bundle.BundleManifest;
import dev.theredja.src2mc.bundle.BundleMap;
import dev.theredja.src2mc.bundle.BundleMaterial;
import dev.theredja.src2mc.bundle.ParticleTable;
import dev.theredja.src2mc.bundle.SurfaceTable;
import dev.theredja.src2mc.client.particles.ParticleRenderer;
import dev.theredja.src2mc.world.MapPlacement;
import dev.theredja.src2mc.world.PlacementIndex;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.LightLayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

/**
 * Bullet hole decals on the map's surfaces, as the engine shoots them ({@code R_DecalShoot}): the
 * decal is a square of its size centred on the hit, laid out in the surface's texture space
 * ({@code R_DecalComputeBasis}: on walls T points down, on floors and ceilings S along +X) and
 * clipped to each map fragment in the surface's plane it covers. A decal of a lit material takes
 * the surface's lightmap; a {@code DecalModulate} one multiplies what is behind it by twice its
 * colour. The oldest goes once {@code r_decals} (2048) are on the map.
 */
@EventBusSubscriber(modid = Src2mc.MOD_ID, value = Dist.CLIENT)
public final class DecalRenderer {
    private DecalRenderer() {}

    /** {@code r_decals}' default. */
    static final int MAX_DECALS = 2048;
    /** How far off the hit surface's plane a fragment may lie and still take the decal, in blocks. */
    private static final double PLANE_TOLERANCE = 1.0 / 64;
    /** The cosine a fragment's normal may differ from the surface's by. */
    private static final double NORMAL_TOLERANCE = 0.999;
    private static final double SIN_45_DEGREES = 0.70710678118654752440;

    /** One decal: its fan triangles, ready to write, and the cells that must still hold the surfaces it lies on. */
    private record Decal(MapPlacement placement, BundleManifest bundle, BundleMap map, int material, boolean modulate,
                         float[] vertices, int vertexCount, long[] owners, float[] cube) {}

    private static final Deque<Decal> DECALS = new ArrayDeque<>();
    private static final List<Batch> BATCHES = new ArrayList<>();
    private static boolean dirty, enabled = true;
    private static long generationSequence = -1, placementEpoch = -1, frames;
    static long shot, missed;

    /** Floats per decal vertex: map-local position, atlas UV, baked light (4), block light, normal. */
    private static final int FLOATS = 3 + 2 + 4 + 1 + 3;

    /**
     * Shoots a decal of game material {@code letter} at the world point {@code hit}, travelling
     * along the world direction {@code shot}. Runs on the client thread.
     */
    public static void shoot(MapPlacement placement, BundleManifest bundle, BundleMap map, char letter, double hitX, double hitY, double hitZ,
                             double shotX, double shotY, double shotZ, RandomGenerator random) {
        ParticleTable table = map.particles();
        if (!enabled || table == null || map.surfaces() == null) return;
        List<ParticleTable.Decal> options = table.decals().get(letter);
        if (options == null || options.isEmpty()) return;
        ParticleTable.Decal decal = pick(options, random);
        if (decal == null) return;
        ParticleTable.Material material = table.materials().get(decal.material());
        ParticleRenderer.Placement atlas = ParticleRenderer.placement(map, material);
        if (atlas == null) return;
        shot++;
        var translation = placement.translation();
        double x = hitX - translation.getX(), y = hitY - translation.getY(), z = hitZ - translation.getZ();
        SurfaceTable surfaces = map.surfaces();
        // The surface hit: the fragment nearest the point, facing the shot.
        double[] plane = nearestPlane(surfaces, x, y, z, shotX, shotY, shotZ);
        if (plane == null) { missed++; return; }
        double nx = plane[0], ny = plane[1], nz = plane[2], distance = plane[3];
        double[][] basis = basis(nx, ny, nz);
        double width = decal.width() / 32.0, height = decal.height() / 32.0;
        // Centred on the hit, moved onto the plane.
        double offset = nx * x + ny * y + nz * z - distance;
        double cx = x - nx * offset, cy = y - ny * offset, cz = z - nz * offset;
        boolean modulate = "decalmodulate".equals(material.shader());
        var level = Minecraft.getInstance().level;
        List<Float> out = new ArrayList<>();
        List<Long> owners = new ArrayList<>();
        int bx = (int) Math.floor(cx), by = (int) Math.floor(cy), bz = (int) Math.floor(cz);
        float[] baked = new float[4];
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            int cellX = bx + dx, cellY = by + dy, cellZ = bz + dz;
            for (SurfaceTable.Face face : surfaces.facesAt(cellX, cellY, cellZ)) {
                double[] normal = face.normal();
                if (normal[0] * nx + normal[1] * ny + normal[2] * nz < NORMAL_TOLERANCE) continue;
                int count = face.vertexCount();
                double first = (cellX + face.coordinate(0, 0)) * nx + (cellY + face.coordinate(0, 1)) * ny + (cellZ + face.coordinate(0, 2)) * nz;
                if (Math.abs(first - distance) > PLANE_TOLERANCE) continue;
                // Texture-space coordinates of the polygon, then clipped to the decal's square.
                List<double[]> polygon = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    double px = cellX + face.coordinate(i, 0), py = cellY + face.coordinate(i, 1), pz = cellZ + face.coordinate(i, 2);
                    double s = ((px - cx) * basis[0][0] + (py - cy) * basis[0][1] + (pz - cz) * basis[0][2]) / width + 0.5;
                    double t = ((px - cx) * basis[1][0] + (py - cy) * basis[1][1] + (pz - cz) * basis[1][2]) / height + 0.5;
                    polygon.add(new double[] {px, py, pz, s, t});
                }
                polygon = clip(polygon, 3, 1, 0);
                polygon = clip(polygon, 3, -1, -1);
                polygon = clip(polygon, 4, 1, 0);
                polygon = clip(polygon, 4, -1, -1);
                if (polygon.size() < 3) continue;
                if (face.owned()) owners.add(BlockPos.asLong(cellX + face.ownerDx(), cellY + face.ownerDy(), cellZ + face.ownerDz()));
                int blockLight = 0;
                if (!modulate && level != null) {
                    blockLight = level.getBrightness(LightLayer.BLOCK, BlockPos.containing(
                        cx + nx * 0.5 + translation.getX(), cy + ny * 0.5 + translation.getY(), cz + nz * 0.5 + translation.getZ()));
                }
                for (int i = 1; i + 1 < polygon.size(); i++) {
                    for (double[] v : new double[][] {polygon.getFirst(), polygon.get(i), polygon.get(i + 1)}) {
                        out.add((float) v[0]); out.add((float) v[1]); out.add((float) v[2]);
                        float u = (float) (decal.u0() + (decal.u1() - decal.u0()) * v[3]);
                        float w = (float) (decal.v0() + (decal.v1() - decal.v0()) * v[4]);
                        out.add(atlas.u0() + atlas.du() * u);
                        out.add(atlas.v0() + atlas.dv() * w);
                        if (modulate) {
                            baked[0] = 1; baked[1] = 1; baked[2] = 1; baked[3] = BakedLighting.VERTEX;
                        } else {
                            BakedLighting.surfaceLight(map.light(), surfaces, face, v[0], v[1], v[2], nx, ny, nz, baked);
                        }
                        for (float b : baked) out.add(b);
                        out.add((float) blockLight);
                        out.add((float) nx); out.add((float) ny); out.add((float) nz);
                    }
                }
            }
        }
        if (out.isEmpty()) { missed++; return; }
        float[] vertices = new float[out.size()];
        for (int i = 0; i < vertices.length; i++) vertices[i] = out.get(i);
        long[] ownerCells = owners.stream().mapToLong(Long::longValue).distinct().toArray();
        float[] cube = modulate ? null : BakedLighting.cube(map.light(), cx + nx * 0.1, cy + ny * 0.1, cz + nz * 0.1);
        DECALS.addLast(new Decal(placement, bundle, map, decal.material(), modulate, vertices, vertices.length / FLOATS, ownerCells, cube));
        while (DECALS.size() > MAX_DECALS) DECALS.removeFirst();
        dirty = true;
    }

    /** A weighted pick, as the decal list's {@code Impact.*} groups are drawn from. */
    private static ParticleTable.Decal pick(List<ParticleTable.Decal> options, RandomGenerator random) {
        double total = 0;
        for (ParticleTable.Decal option : options) total += option.weight();
        if (total <= 0) return options.get(random.nextInt(options.size()));
        double at = random.nextDouble() * total;
        for (ParticleTable.Decal option : options) {
            at -= option.weight();
            if (at < 0) return option;
        }
        return options.getLast();
    }

    /**
     * The plane of the map fragment nearest the map-local point that faces against the shot:
     * normal and distance, or null for none within half a block.
     */
    private static double[] nearestPlane(SurfaceTable surfaces, double x, double y, double z, double shotX, double shotY, double shotZ) {
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        double[] best = null;
        double bestDistance = 0.5;
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            int cellX = bx + dx, cellY = by + dy, cellZ = bz + dz;
            for (SurfaceTable.Face face : surfaces.facesAt(cellX, cellY, cellZ)) {
                double[] n = face.normal();
                if (n[0] * shotX + n[1] * shotY + n[2] * shotZ >= 0 && (shotX != 0 || shotY != 0 || shotZ != 0)) continue;
                double d = (cellX + face.coordinate(0, 0)) * n[0] + (cellY + face.coordinate(0, 1)) * n[1] + (cellZ + face.coordinate(0, 2)) * n[2];
                double off = n[0] * x + n[1] * y + n[2] * z - d;
                if (Math.abs(off) >= bestDistance) continue;
                if (!inside(face, cellX, cellY, cellZ, n, x - n[0] * off, y - n[1] * off, z - n[2] * off)) continue;
                bestDistance = Math.abs(off);
                best = new double[] {n[0], n[1], n[2], d};
            }
        }
        return best;
    }

    /** Whether a point on the fragment's plane lies within the fragment, with a small margin. */
    private static boolean inside(SurfaceTable.Face face, int cellX, int cellY, int cellZ, double[] n, double x, double y, double z) {
        int count = face.vertexCount();
        for (int i = 0; i < count; i++) {
            int j = (i + 1) % count;
            double ax = cellX + face.coordinate(i, 0), ay = cellY + face.coordinate(i, 1), az = cellZ + face.coordinate(i, 2);
            double ex = cellX + face.coordinate(j, 0) - ax, ey = cellY + face.coordinate(j, 1) - ay, ez = cellZ + face.coordinate(j, 2) - az;
            // Edge cross normal points out of a counter-clockwise polygon.
            double ox = ey * n[2] - ez * n[1], oy = ez * n[0] - ex * n[2], oz = ex * n[1] - ey * n[0];
            double length = Math.sqrt(ox * ox + oy * oy + oz * oz);
            if (length == 0) continue;
            if (((x - ax) * ox + (y - ay) * oy + (z - az) * oz) / length > 1e-3) return false;
        }
        return true;
    }

    /**
     * {@code R_DecalComputeBasis} without an S axis, in Minecraft axes: S and T for a surface of
     * normal {@code n}. Source's Z (up) is Minecraft's Y; Source's Y is Minecraft's -Z.
     */
    static double[][] basis(double nx, double ny, double nz) {
        // To Source axes.
        double[] n = {nx, -nz, ny};
        double[] s, t;
        if (Math.abs(n[2]) > SIN_45_DEGREES) {
            // Floor or ceiling: S along X; T = S x N; S = N x T.
            s = new double[] {1, 0, 0};
            t = cross(s, n);
            s = cross(n, t);
        } else {
            // Wall: T straight down; S = N x T; T = S x N.
            t = new double[] {0, 0, -1};
            s = cross(n, t);
            t = cross(s, n);
        }
        normalize(s);
        normalize(t);
        return new double[][] {{s[0], s[2], -s[1]}, {t[0], t[2], -t[1]}};
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static void normalize(double[] v) {
        double length = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (length > 0) for (int i = 0; i < 3; i++) v[i] /= length;
    }

    /** Keeps the part of the polygon where {@code sign * v[index] + bias >= 0} (Sutherland-Hodgman). */
    private static List<double[]> clip(List<double[]> polygon, int index, double sign, double bias) {
        List<double[]> result = new ArrayList<>(polygon.size() + 2);
        int count = polygon.size();
        for (int i = 0; i < count; i++) {
            double[] a = polygon.get(i), b = polygon.get((i + 1) % count);
            double da = sign * a[index] + bias, db = sign * b[index] + bias;
            if (da >= 0) result.add(a);
            if ((da >= 0) != (db >= 0)) {
                double f = da / (da - db);
                double[] v = new double[a.length];
                for (int k = 0; k < v.length; k++) v[k] = a[k] + (b[k] - a[k]) * f;
                result.add(v);
            }
        }
        return result;
    }

    /** What one draw of the decals takes: the decals of one map and material. */
    private static final class Batch {
        final BundleManifest bundle;
        final BundleMap map;
        final int page;
        final boolean modulate;
        final BlockPos origin;
        final float[] cube;
        VertexBuffer buffer;
        int vertexCount;

        Batch(BundleManifest bundle, BundleMap map, int page, boolean modulate, BlockPos origin, float[] cube) {
            this.bundle = bundle; this.map = map; this.page = page; this.modulate = modulate; this.origin = origin; this.cube = cube;
        }
    }

    private static void rebuild() {
        for (Batch batch : BATCHES) if (batch.buffer != null) batch.buffer.close();
        BATCHES.clear();
        // Decals by placement, then material, in order shot.
        Map<MapPlacement, Map<Integer, List<Decal>>> groups = new IdentityHashMap<>();
        for (Decal decal : DECALS) {
            groups.computeIfAbsent(decal.placement, ignored -> new java.util.LinkedHashMap<>())
                .computeIfAbsent(decal.material, ignored -> new ArrayList<>()).add(decal);
        }
        VertexFormat format = BakedLighting.FORMAT;
        int stride = format.getVertexSize();
        int position = format.getOffset(VertexFormatElement.POSITION), color = format.getOffset(VertexFormatElement.COLOR),
            uv0 = format.getOffset(VertexFormatElement.UV0), uv2 = format.getOffset(VertexFormatElement.UV2),
            normal = format.getOffset(VertexFormatElement.NORMAL), bakedOffset = format.getOffset(BakedLighting.BAKED);
        for (var byPlacement : groups.entrySet()) {
            MapPlacement placement = byPlacement.getKey();
            for (var byMaterial : byPlacement.getValue().entrySet()) {
                List<Decal> decals = byMaterial.getValue();
                Decal first = decals.getFirst();
                ParticleRenderer.Placement atlas = ParticleRenderer.placement(first.map, first.map.particles().materials().get(first.material));
                if (atlas == null) continue;
                // Lit decals share the ambient cube of the newest for the few fragments Source lights by it.
                Batch batch = new Batch(first.bundle, first.map, atlas.page(), first.modulate, placement.translation(), decals.getLast().cube);
                int vertices = 0;
                for (Decal decal : decals) vertices += decal.vertexCount;
                try (ByteBufferBuilder bytes = new ByteBufferBuilder(vertices * stride)) {
                    long base = bytes.reserve(vertices * stride);
                    long at = base;
                    for (Decal decal : decals) {
                        float[] v = decal.vertices;
                        for (int i = 0; i < decal.vertexCount; i++, at += stride) {
                            int o = i * FLOATS;
                            MemoryUtil.memPutFloat(at + position, v[o]);
                            MemoryUtil.memPutFloat(at + position + 4, v[o + 1]);
                            MemoryUtil.memPutFloat(at + position + 8, v[o + 2]);
                            MemoryUtil.memPutInt(at + color, 0xFFFFFFFF);
                            MemoryUtil.memPutFloat(at + uv0, v[o + 3]);
                            MemoryUtil.memPutFloat(at + uv0 + 4, v[o + 4]);
                            MemoryUtil.memPutShort(at + uv2, (short) (v[o + 9] * 16));
                            MemoryUtil.memPutShort(at + uv2 + 2, (short) 0);
                            for (int k = 0; k < 3; k++) MemoryUtil.memPutByte(at + normal + k, (byte) Math.round(v[o + 10 + k] * 127));
                            for (int k = 0; k < 4; k++) MemoryUtil.memPutFloat(at + bakedOffset + k * 4L, v[o + 5 + k]);
                        }
                    }
                    MeshData mesh = new MeshData(bytes.build(), new MeshData.DrawState(format, vertices, vertices,
                        VertexFormat.Mode.TRIANGLES, VertexFormat.IndexType.least(vertices)));
                    batch.buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                    batch.buffer.bind();
                    batch.buffer.upload(mesh);
                    VertexBuffer.unbind();
                    batch.vertexCount = vertices;
                }
                BATCHES.add(batch);
            }
        }
        dirty = false;
    }

    /** Drops every decal: on a new bundle generation or placement change, and by command. */
    static void clear() {
        DECALS.clear();
        dirty = true;
    }

    /** Drops the decals whose surface went with its block. */
    private static void dropOrphans() {
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        boolean removed = DECALS.removeIf(decal -> {
            var translation = decal.placement.translation();
            for (long owner : decal.owners) {
                if (level.getBlockState(BlockPos.of(owner).offset(translation)).isAir()) return true;
            }
            return false;
        });
        if (removed) dirty = true;
    }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != MapSurfaceRenderer.opaqueStage() || IrisCompat.renderingShadowPass()) return;
        long sequence = Src2mc.bundles().active().sequence();
        long epoch = PlacementIndex.epoch();
        if (sequence != generationSequence || epoch != placementEpoch) {
            generationSequence = sequence;
            placementEpoch = epoch;
            clear();
        }
        if (++frames % 10 == 0 && !DECALS.isEmpty()) dropOrphans();
        if (dirty) rebuild();
        if (!enabled || BATCHES.isEmpty() || !BakedLighting.ready()) return;
        var camera = event.getCamera().getPosition();
        long frame = MapSurfaceRenderer.currentFrame();
        RenderSystem.enableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableCull();
        // Decals lie on their surface; pull them towards the eye in depth as Source's decal depth bias does.
        RenderSystem.enablePolygonOffset();
        RenderSystem.polygonOffset(-1.0f, -10.0f);
        Minecraft.getInstance().gameRenderer.lightTexture().turnOnLightLayer();
        int previous = RenderSystem.getShaderTexture(0);
        try {
            for (Batch batch : BATCHES) {
                ResourceLocation texture = MapSurfaceRenderer.atlasPages().request(sequence, batch.bundle, batch.map.atlas(), batch.page, frame)
                    .orElseGet(MapSurfaceRenderer.atlasPages()::placeholderTexture);
                RenderSystem.setShaderTexture(0, texture);
                MapSurfaceRenderer.applyAtlasFilter(texture);
                var shader = BakedLighting.prepare(batch.map.light(), BundleMaterial.RenderClass.SOLID, batch.cube, null);
                if (batch.modulate) {
                    // DecalModulate: twice the decal's colour times what is behind it, unlit.
                    shader.safeGetUniform("Exposure").set(1f);
                    RenderSystem.blendFunc(GlStateManager.SourceFactor.DST_COLOR, GlStateManager.DestFactor.SRC_COLOR);
                } else {
                    shader.safeGetUniform("Cutout").set(0.1f);
                    RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
                }
                Matrix4f modelView = new Matrix4f(event.getModelViewMatrix()).translate(
                    (float) (batch.origin.getX() - camera.x), (float) (batch.origin.getY() - camera.y), (float) (batch.origin.getZ() - camera.z));
                RenderSystem.setShader(BakedLighting::shader);
                batch.buffer.bind();
                batch.buffer.drawWithShader(modelView, event.getProjectionMatrix(), shader);
            }
        } finally {
            VertexBuffer.unbind();
            Minecraft.getInstance().gameRenderer.lightTexture().turnOffLightLayer();
            RenderSystem.setShaderTexture(0, previous);
            RenderSystem.polygonOffset(0.0f, 0.0f);
            RenderSystem.disablePolygonOffset();
            RenderSystem.depthMask(true);
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
        }
    }

    /** For {@code /src2mc_particles} status. */
    public static String status() {
        int vertices = 0;
        for (Batch batch : BATCHES) vertices += batch.vertexCount;
        return "decals " + (enabled ? "on" : "off") + ": " + DECALS.size() + "/" + MAX_DECALS + " (" + BATCHES.size() + " draws, " + vertices / 3
            + " triangles), shot " + shot + ", no surface " + missed;
    }

    /** For {@code /src2mc_particles decals on|off}: off hides them, and shoots none. */
    public static void setEnabled(boolean value) { enabled = value; }

    /** For {@code /src2mc_particles decals clear}. */
    public static void clearAll() {
        clear();
    }
}
