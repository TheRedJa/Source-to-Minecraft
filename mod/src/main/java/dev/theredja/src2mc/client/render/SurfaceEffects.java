package dev.theredja.src2mc.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.theredja.src2mc.bundle.BundleMap;
import dev.theredja.src2mc.bundle.BundleMaterial;
import dev.theredja.src2mc.bundle.CubemapTable;
import dev.theredja.src2mc.bundle.LightTable;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

/**
 * A map's surface effects on the GPU (D31): every cubemap's six faces on one half-float texture
 * (sampler 5); per material its settings, {@link #TEXELS} texels in a row (sampler 6) -- the
 * reflection, self-illumination, detail texture and displacement blend -- which a surface vertex
 * names by its material in UV2's second half; and every detail and blend modulation texture on
 * a layer of one array texture (unit {@link #DETAIL_UNIT}). The baked shader picks cubemap faces
 * and layers itself, so one draw serves any number of them.
 */
public final class SurfaceEffects {
    private SurfaceEffects() {}

    /** Materials per row of the settings texture. */
    static final int MATERIALS_PER_ROW = 128;
    /** Settings texels per material. */
    static final int TEXELS = 8;
    /**
     * The texture unit of the detail array: past every sampler the baked shader lists, since the
     * shader binds those on the plain 2D target of units 0 up and a unit's samplers must agree.
     */
    static final int DETAIL_UNIT = 15;
    /** UV2's second short is signed; a row is written negated, which sky light never is. */
    private static final int MAX_MATERIAL = -Short.MIN_VALUE - 1;

    /** {@code /src2mc_look reflections off}: no draw reflects; meshes keep their rows. */
    public static volatile boolean enabled = true;
    /** {@code /src2mc_look detail|blend|selfillum off}: switch one effect off, read per draw. */
    public static volatile boolean detailOn = true, blendOn = true, selfillumOn = true;

    private record Textures(int cubes, int settings, int details) {}

    /** Each map's light table to its map, for draws that only know the table; identity keys. */
    private static final Map<LightTable, BundleMap> MAPS = java.util.Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Map<BundleMap, Textures> TEXTURES = new IdentityHashMap<>();

    /** Records which map a light table belongs to; any thread. */
    static void register(BundleMap map) {
        if (map.light() != null) MAPS.put(map.light(), map);
    }

    /**
     * The packed light of a vertex of {@code material}: block light as it is, and for a material
     * that reflects, its row negated ({@code -(materialId + 1)}) in place of sky light, which the
     * baked shader does not read and which is never negative, so props keep theirs.
     */
    static int withEnvmap(int packedLight, int materialId, BundleMaterial material) {
        int row = row(materialId, material);
        return row == 0 ? packedLight : (packedLight & 0xFFFF) | row << 16;
    }

    /** The material's row as packed light's upper short, {@code -(materialId + 1)}; 0 for a material without effects. */
    static int row(int materialId, BundleMaterial material) {
        if (!material.surfaceEffects() || materialId < 0 || materialId > MAX_MATERIAL) return 0;
        return -(materialId + 1) & 0xFFFF;
    }

    /** Binds the map's cubemaps, settings and detail atlas for a draw lit by {@code table}; render thread. */
    static void bind(LightTable table, net.minecraft.client.renderer.ShaderInstance shader) {
        BundleMap map = table == null ? null : MAPS.get(table);
        Textures textures = map == null ? null : TEXTURES.computeIfAbsent(map, SurfaceEffects::upload);
        RenderSystem.setShaderTexture(5, textures == null ? 0 : textures.cubes());
        RenderSystem.setShaderTexture(6, textures == null ? 0 : textures.settings());
        int active = GlStateManager._getActiveTexture();
        GlStateManager._activeTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0 + DETAIL_UNIT);
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, textures == null ? 0 : textures.details());
        GlStateManager._activeTexture(active);
        shader.safeGetUniform("DetailArray").set(DETAIL_UNIT);
        shader.safeGetUniform("SurfaceToggles").set(enabled ? 1f : 0f, detailOn ? 1f : 0f, blendOn ? 1f : 0f, selfillumOn ? 1f : 0f);
    }

    /** Drops every map's textures; a new bundle generation brings new tables. */
    static void clear() {
        for (Textures textures : TEXTURES.values()) {
            GlStateManager._deleteTexture(textures.cubes());
            GlStateManager._deleteTexture(textures.settings());
            if (textures.details() != 0) GlStateManager._deleteTexture(textures.details());
        }
        TEXTURES.clear();
        MAPS.clear();
    }

    /** Where each cubemap's strip of six faces lands: shelves of strips, tallest first. */
    static int[][] layout(List<CubemapTable.Cube> cubes, int[] size) {
        int width = 2048;
        for (CubemapTable.Cube cube : cubes) width = Math.max(width, 6 * cube.size());
        Integer[] order = new Integer[cubes.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Integer.compare(cubes.get(b).size(), cubes.get(a).size()));
        int[][] at = new int[cubes.size()][];
        int x = 0, y = 0, shelf = 0;
        for (int index : order) {
            int strip = 6 * cubes.get(index).size(), height = cubes.get(index).size();
            if (x + strip > width) { y += shelf; x = 0; shelf = 0; }
            at[index] = new int[] {x, y};
            x += strip;
            shelf = Math.max(shelf, height);
        }
        size[0] = width;
        size[1] = Math.max(1, y + shelf);
        return at;
    }

    private static Textures upload(BundleMap map) {
        List<CubemapTable.Cube> cubes = map.cubemaps() == null ? List.of() : map.cubemaps().cubes();
        int[][] at = new int[0][];
        int cubeTexture = 0;
        if (!cubes.isEmpty()) {
            int[] size = new int[2];
            at = layout(cubes, size);
            cubeTexture = GlStateManager._genTexture();
            GlStateManager._bindTexture(cubeTexture);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGB16F, size[0], size[1], 0, GL11.GL_RGB, GL30.GL_HALF_FLOAT, (ByteBuffer) null);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 2);
            for (int i = 0; i < cubes.size(); i++) {
                CubemapTable.Cube cube = cubes.get(i);
                int side = cube.size();
                ByteBuffer row = MemoryUtil.memAlloc(side * 6);
                try {
                    for (int face = 0; face < 6; face++) {
                        for (int y = 0; y < side; y++) {
                            row.clear();
                            row.put(cube.faces(), (face * side * side + y * side) * 6, side * 6).flip();
                            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, at[i][0] + face * side, at[i][1] + y, side, 1,
                                GL11.GL_RGB, GL30.GL_HALF_FLOAT, row);
                        }
                    }
                } finally {
                    MemoryUtil.memFree(row);
                }
            }
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            parameters(GL11.GL_LINEAR);
        }

        Map<String, float[]> tiles = new java.util.HashMap<>();
        int detailTexture = uploadDetails(map, tiles);

        List<BundleMaterial> materials = map.materials();
        int rows = Math.max(1, (materials.size() + MATERIALS_PER_ROW - 1) / MATERIALS_PER_ROW);
        FloatBuffer settings = MemoryUtil.memAllocFloat(MATERIALS_PER_ROW * TEXELS * rows * 4);
        try {
            for (int m = 0; m < MATERIALS_PER_ROW * rows; m++) {
                BundleMaterial material = m < materials.size() ? materials.get(m) : null;
                float[] row = new float[TEXELS * 4];
                if (material != null) {
                    BundleMaterial.Envmap envmap = material.envmap();
                    if (envmap != null && envmap.cubemap() < cubes.size()) {
                        int[] place = at[envmap.cubemap()];
                        put(row, 0, place[0], place[1], cubes.get(envmap.cubemap()).size(), 1);
                        put(row, 1, envmap.tint()[0], envmap.tint()[1], envmap.tint()[2], envmap.fresnel());
                        put(row, 2, envmap.saturation()[0], envmap.saturation()[1], envmap.saturation()[2], envmap.contrast());
                    }
                    if (material.selfillum() != null) {
                        put(row, 3, material.selfillum()[0], material.selfillum()[1], material.selfillum()[2], 1);
                    }
                    BundleMaterial.Detail detail = material.detail();
                    float[] detailTile = detail == null ? null : tiles.get(detail.texture());
                    put(row, 5, 0, 0, 0, -1);
                    if (detailTile != null) {
                        put(row, 4, detailTile[0], detailTile[1], detailTile[2], detailTile[3]);
                        put(row, 5, detail.scaleS(), detail.scaleT(), detail.blendFactor(), detail.blendMode());
                        put(row, 6, detail.tint()[0], detail.tint()[1], detail.tint()[2], 0);
                    }
                    row[6 * 4 + 3] = material.blend() ? 1 : 0;
                    float[] modulate = material.blendModulate() == null ? null : tiles.get(material.blendModulate());
                    if (modulate != null) put(row, 7, modulate[0], modulate[1], modulate[2], modulate[3]);
                }
                settings.put(row);
            }
            settings.flip();
            int settingsTexture = GlStateManager._genTexture();
            GlStateManager._bindTexture(settingsTexture);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, MATERIALS_PER_ROW * TEXELS, rows, 0, GL11.GL_RGBA, GL11.GL_FLOAT, settings);
            parameters(GL11.GL_NEAREST);
            GlStateManager._bindTexture(0);
            return new Textures(cubeTexture, settingsTexture, detailTexture);
        } finally {
            MemoryUtil.memFree(settings);
        }
    }

    private static void put(float[] row, int texel, float a, float b, float c, float d) {
        row[texel * 4] = a; row[texel * 4 + 1] = b; row[texel * 4 + 2] = c; row[texel * 4 + 3] = d;
    }

    /**
     * Puts the map's detail and blend modulation textures on the layers of one array texture, each
     * layer a square as wide as the largest of them, a smaller texture repeated to fill its layer,
     * so every layer repeats and mipmaps down to one texel as the texture itself would; {@code
     * tiles} gets each one's layer and its own share of the layer's sides. 0 when the map has none.
     */
    private static int uploadDetails(BundleMap map, Map<String, float[]> tiles) {
        if (map.details() == null || map.details().images().isEmpty()) return 0;
        List<Map.Entry<String, dev.theredja.src2mc.bundle.DetailImages.Image>> images = new java.util.ArrayList<>(map.details().images().entrySet());
        images.sort(Map.Entry.comparingByKey());
        int side = 1;
        for (var image : images) side = Math.max(side, Math.max(image.getValue().width(), image.getValue().height()));
        ByteBuffer pixels = MemoryUtil.memAlloc(side * side * 4 * images.size());
        try {
            for (int layer = 0; layer < images.size(); layer++) {
                var img = images.get(layer).getValue();
                for (int y = 0; y < side; y++) {
                    for (int x = 0; x < side; x++) {
                        int src = ((y % img.height()) * img.width() + x % img.width()) * 4;
                        pixels.put(((layer * side + y) * side + x) * 4, img.rgba(), src, 4);
                    }
                }
                tiles.put(images.get(layer).getKey(), new float[] {layer, (float) img.width() / side, (float) img.height() / side, 1});
            }
            int texture = GlStateManager._genTexture();
            GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, texture);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            GL12.glTexImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, GL11.GL_RGBA8, side, side, images.size(), 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            GL30.glGenerateMipmap(GL30.GL_TEXTURE_2D_ARRAY);
            GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
            GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
            GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
            GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, 0);
            return texture;
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }

    private static void parameters(int filter) {
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, filter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
    }
}
