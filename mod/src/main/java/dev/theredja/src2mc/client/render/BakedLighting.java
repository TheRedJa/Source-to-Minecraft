package dev.theredja.src2mc.client.render;

import static net.minecraft.commands.Commands.literal;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.bundle.BundleMaterial;
import dev.theredja.src2mc.bundle.LightTable;
import dev.theredja.src2mc.bundle.SurfaceTable;
import java.io.IOException;
import java.nio.FloatBuffer;
import dev.theredja.src2mc.client.look.SourcePost;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import org.joml.Matrix3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

/**
 * Source's baked light for everything the map draws (format.md section 22), in place of
 * Minecraft's: a brush face or displacement samples its lightmap, a static prop wears the vertex
 * light vrad baked into its {@code .vhv}, and whatever else -- a prop the logic moves, an animated
 * one, a door -- the ambient cube of the leaf it stands in. Each vertex carries which in its
 * {@code Baked} attribute; Minecraft's block light (torches) is added on top, sky light never.
 *
 * <p>One core shader draws it all; a shader pack does not draw it, as Iris draws no core shader
 * it does not know while it renders the world.
 */
@EventBusSubscriber(modid = Src2mc.MOD_ID, value = Dist.CLIENT)
public final class BakedLighting {
    private BakedLighting() {}

    /** {@code Baked.w}: page coordinates on the map's lightmap texture. */
    static final float LIGHTMAP = 0f;
    /** {@code Baked.w}: linear light in {@code Baked.rgb}. */
    static final float VERTEX = 1f;
    /** {@code Baked.w}: the ambient cube of the draw, for the vertex normal. */
    static final float AMBIENT = 2f;

    /** Four floats: the light, as above. */
    static final VertexFormatElement BAKED = VertexFormatElement.register(VertexFormatElement.findNextId(), 0,
        VertexFormatElement.Type.FLOAT, VertexFormatElement.Usage.GENERIC, 4);
    /** Position, colour (tint), atlas UV, Minecraft light, normal, baked light: 48 bytes. */
    static final VertexFormat FORMAT = VertexFormat.builder()
        .add("Position", VertexFormatElement.POSITION)
        .add("Color", VertexFormatElement.COLOR)
        .add("UV0", VertexFormatElement.UV0)
        .add("UV2", VertexFormatElement.UV2)
        .add("Normal", VertexFormatElement.NORMAL)
        .padding(1)
        .add("Baked", BAKED)
        .build();

    private static ShaderInstance shader;
    /** The map's lightmap pages, one texture each map, stacked; keyed by identity. */
    private static final Map<LightTable, Integer> TEXTURES = new IdentityHashMap<>();
    private static int white = -1;

    /** Source's tone map scale, which its auto exposure moves ({@link SourcePost}); 1 outside a map. */
    static float exposure() { return SourcePost.scale(); }

    /** For the particle renderer, outside this package. */
    public static float currentExposure() { return SourcePost.scale(); }

    /** Holds the scale by hand, as {@code mat_force_tonemap_scale}; 0 gives it back to the auto exposure. */
    static void setExposure(float value) { SourcePost.force(value); }

    static ShaderInstance shader() { return shader; }

    @EventBusSubscriber(modid = Src2mc.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Shaders {
        private Shaders() {}

        @SubscribeEvent
        public static void register(RegisterShadersEvent event) throws IOException {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                ResourceLocation.fromNamespaceAndPath(Src2mc.MOD_ID, "baked"), FORMAT), loaded -> shader = loaded);
        }
    }

    /**
     * Where page coordinates of a light region land on the map's stacked lightmap texture: the
     * pages are stacked top to bottom, each at the texture's full width or less.
     */
    static float[] textureCoordinates(LightTable table, int page, double s, double t) {
        var pages = table.pages();
        int width = 0, height = 0, top = 0;
        for (int i = 0; i < pages.size(); i++) {
            if (i == page) top = height;
            width = Math.max(width, pages.get(i).width());
            height += pages.get(i).height();
        }
        if (page < 0 || page >= pages.size() || width == 0) return new float[] {0, 0};
        LightTable.Page own = pages.get(page);
        return new float[] {(float) (s * own.width() / width), (float) ((t * own.height() + top) / height)};
    }

    /** The baked attribute of a fragment vertex at map-local {@code (x, y, z)}. */
    static void surfaceLight(LightTable table, SurfaceTable surfaces, SurfaceTable.Face face, double x, double y, double z,
                             double nx, double ny, double nz, float[] out) {
        if (table != null && face.lightRegionId() != SurfaceTable.NO_LIGHT && face.lightRegionId() < surfaces.lightRegions().size()) {
            SurfaceTable.LightRegion region = surfaces.lightRegions().get(face.lightRegionId());
            float[] uv = textureCoordinates(table, region.page(), region.s(x, y, z), region.t(x, y, z));
            out[0] = uv[0]; out[1] = uv[1]; out[2] = 0; out[3] = LIGHTMAP;
        } else if (face.provenance() == 2 || table == null) {
            // A BSP face vrad gave no lightmap is one Source draws unlit: full bright.
            out[0] = 1; out[1] = 1; out[2] = 1; out[3] = VERTEX;
        } else {
            ambient(table, x, y, z, nx, ny, nz, out);
        }
    }

    /**
     * The ambient cube at map-local {@code (x, y, z)} evaluated for a normal, as vertex light;
     * full bright when the map has no samples.
     */
    static void ambient(LightTable table, double x, double y, double z, double nx, double ny, double nz, float[] out) {
        float[] cube = new float[18];
        if (table == null || !table.ambientAt(x, y, z, cube)) {
            out[0] = 1; out[1] = 1; out[2] = 1; out[3] = VERTEX;
            return;
        }
        float[] rgb = new float[3];
        LightTable.evaluate(cube, nx, ny, nz, rgb);
        out[0] = rgb[0]; out[1] = rgb[1]; out[2] = rgb[2]; out[3] = VERTEX;
    }

    /** Each map's props by identity, to their placement-table index; keyed by the map's light table. */
    private static final Map<LightTable, Map<dev.theredja.src2mc.bundle.BundleProp, Integer>> PROP_INDEX =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * A static prop's vertex light, linear RGB per mesh vertex, from its .vhv; null when it has
     * none or the mesh does not match it, and it is lit by the ambient cube instead.
     */
    static float[] propVertexLight(dev.theredja.src2mc.bundle.BundleMap map, dev.theredja.src2mc.bundle.BundleProp prop, int meshVertices) {
        LightTable table = map.light();
        if (table == null) return null;
        Map<dev.theredja.src2mc.bundle.BundleProp, Integer> index = PROP_INDEX.computeIfAbsent(table, ignored -> {
            Map<dev.theredja.src2mc.bundle.BundleProp, Integer> built = new IdentityHashMap<>();
            var props = map.props();
            for (int i = 0; i < props.size(); i++) built.put(props.get(i), i);
            return built;
        });
        Integer at = index.get(prop);
        byte[] raw = at == null ? null : table.propLight(at);
        if (raw == null || raw.length != meshVertices * 3) return null;
        float[] light = new float[raw.length];
        for (int i = 0; i < raw.length; i++) light[i] = LightTable.vertexLight(raw[i]);
        return light;
    }

    /** The ambient cube at a map-local point, 18 floats; white when the map has none. */
    static float[] cube(LightTable table, double x, double y, double z) {
        float[] cube = new float[18];
        if (table == null || !table.ambientAt(x, y, z, cube)) java.util.Arrays.fill(cube, 1f);
        return cube;
    }

    /**
     * Sets the shader up for one draw: the map's lightmap on sampler 3, the cut-out threshold of
     * the render class, the exposure, and for ambient-lit vertices the cube and the rotation that
     * turns their normals into map axes. Call before {@code drawWithShader}.
     */
    static ShaderInstance prepare(LightTable table, BundleMaterial.RenderClass renderClass, float[] cube, Matrix3f normalTurn) {
        RenderSystem.setShaderTexture(3, table == null ? white() : texture(table));
        shader.safeGetUniform("Exposure").set(exposure());
        dev.theredja.src2mc.client.look.LookClient.applyFog(shader);
        // Vanilla's entity cut-out and translucent shaders cut below 0.1; solid cuts nothing.
        shader.safeGetUniform("Cutout").set(renderClass == BundleMaterial.RenderClass.SOLID ? -1f : 0.1f);
        shader.safeGetUniform("NormalTurn").set(normalTurn == null ? new Matrix3f() : normalTurn);
        if (cube != null) {
            String[] sides = {"AmbientPX", "AmbientNX", "AmbientPY", "AmbientNY", "AmbientPZ", "AmbientNZ"};
            for (int i = 0; i < 6; i++) shader.safeGetUniform(sides[i]).set(cube[i * 3], cube[i * 3 + 1], cube[i * 3 + 2]);
        }
        return shader;
    }

    /** Whether the shader loaded; nothing of the map draws without it. */
    static boolean ready() { return shader != null; }

    /** The map's lightmap pages as one texture, uploaded on first use; render thread only. */
    static int texture(LightTable table) {
        Integer existing = TEXTURES.get(table);
        if (existing != null) return existing;
        var pages = table.pages();
        int width = 0, height = 0;
        for (LightTable.Page page : pages) {
            width = Math.max(width, page.width());
            height += page.height();
        }
        if (width == 0 || height == 0) {
            TEXTURES.put(table, white());
            return white();
        }
        int max = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
        if (height > max) {
            Src2mc.LOGGER.warn("lightmap pages are {} luxels tall, over this GPU's {}; drawn unlit", height, max);
            TEXTURES.put(table, white());
            return white();
        }
        FloatBuffer data = MemoryUtil.memAllocFloat(width * height * 3);
        try {
            int top = 0;
            for (LightTable.Page page : pages) {
                byte[] luxels = page.luxels();
                for (int y = 0; y < page.height(); y++) {
                    for (int x = 0; x < width; x++) {
                        int at = ((top + y) * width + x) * 3;
                        if (x >= page.width()) {
                            data.put(at, 0f).put(at + 1, 0f).put(at + 2, 0f);
                            continue;
                        }
                        int source = (y * page.width() + x) * 4;
                        // ColorRGBExp32: value * 2^exponent / 255, the exponent signed.
                        float scale = (float) Math.scalb(1.0, luxels[source + 3]) / 255f;
                        data.put(at, (luxels[source] & 255) * scale)
                            .put(at + 1, (luxels[source + 1] & 255) * scale)
                            .put(at + 2, (luxels[source + 2] & 255) * scale);
                    }
                }
                top += page.height();
            }
            int id = GL11.glGenTextures();
            int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            // Shared exponent: HDR light in four bytes a luxel, and filterable.
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGB9_E5, width, height, 0, GL11.GL_RGB, GL11.GL_FLOAT, data);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
            TEXTURES.put(table, id);
            return id;
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    private static int white() {
        if (white < 0) {
            white = GL11.glGenTextures();
            int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, white);
            FloatBuffer one = MemoryUtil.memAllocFloat(3).put(0, 1f).put(1, 1f).put(2, 1f);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGB9_E5, 1, 1, 0, GL11.GL_RGB, GL11.GL_FLOAT, one);
            MemoryUtil.memFree(one);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
        }
        return white;
    }

    /** Drops every map's lightmap texture; a new bundle generation brings new tables. */
    static void clear() {
        for (int id : TEXTURES.values()) if (id != white) GL11.glDeleteTextures(id);
        TEXTURES.clear();
    }

    @SubscribeEvent
    public static void registerCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(literal("src2mc_light")
            .then(literal("exposure").then(net.minecraft.commands.Commands.argument("scale",
                    com.mojang.brigadier.arguments.FloatArgumentType.floatArg(0.01f, 64f))
                .executes(context -> setExposure(context.getSource(),
                    com.mojang.brigadier.arguments.FloatArgumentType.getFloat(context, "scale")))))
            .then(literal("status").executes(context -> status(context.getSource()))));
    }

    private static int setExposure(CommandSourceStack source, float value) {
        setExposure(value);
        source.sendSuccess(() -> Component.literal("src2mc baked light exposure held at " + value + " (/src2mc_look scale auto releases it)"), false);
        return 1;
    }

    private static int status(CommandSourceStack source) {
        String text = "src2mc baked light: " + (shader == null ? "shader not loaded" : "shader loaded") + ", exposure " + exposure()
            + ", " + TEXTURES.size() + " lightmap texture(s)"
            + (IrisCompat.shaderPackInUse() ? "; a shader pack is in use, which does not draw it" : "");
        source.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }
}
