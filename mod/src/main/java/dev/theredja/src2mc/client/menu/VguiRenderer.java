package dev.theredja.src2mc.client.menu;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.menu.MenuState;
import dev.theredja.src2mc.menu.PanelPosition;
import dev.theredja.src2mc.menu.ScreensData;
import dev.theredja.src2mc.menu.SourceSpace;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Draws the shown {@code vgui_screen}s in the world as Source's {@code DrawPanelIn3DSpace} does: the panel's pixels
 * mapped straight onto the screen's rectangle, unlit, depth-tested against the world, alpha-blended, and not at
 * all from behind. It draws at the particle stage, inside the level pass, so {@code SourcePost}'s exposure and
 * bloom treat it like the rest of the scene.
 *
 * <p>Controls are drawn in paint order, each a hair nearer the viewer than the one before, so those on the same
 * plane do not fight over depth. Text is Minecraft's own font renderer with the bundled Source Sans Pro fonts
 * ({@code assets/src2mc/font}), scaled so that a vgui font of height {@code tall} has a line {@code tall} panel
 * pixels high.
 */
@EventBusSubscriber(modid = Src2mc.MOD_ID, value = Dist.CLIENT)
final class VguiRenderer {
    private VguiRenderer() {}

    /** The em size, in font units, of the fonts in {@code assets/src2mc/font/*.json}. */
    static final float FONT_SIZE = 24f;
    /** Source Sans Pro: a line (winAscent 984 + winDescent 273 of 1000 units per em) is 1.257 em, of which 0.984 above the baseline. */
    private static final double LINE_PER_EM = 1.257, ASCENT_PER_EM = 0.984;
    /** Minecraft draws a glyph's baseline this many font units below the text origin ({@code GlyphProvider.BASELINE}). */
    private static final float BASELINE = 7f;
    private static final int FULL_BRIGHT = 15728880;
    /** How far in front of the previous control the next is drawn, in blocks. */
    private static final float LAYER_STEP = 2e-4f;
    /** Texture uploads per frame, so a layout's many large images do not arrive in one frame. */
    private static final int UPLOADS_PER_FRAME = 1;

    private static final Set<String> MISSING_FONTS = new HashSet<>();

    @SubscribeEvent
    static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        if (dev.theredja.src2mc.client.render.IrisCompat.shadowPass()) return;
        MenuContext context = MenuClient.context();
        if (context == null) return;
        MenuState state = MenuClient.state(context);
        if (state == null) return;
        Vec3 eye = event.getCamera().getPosition();
        double[] eyeSource = {(eye.x - context.placement.translation().getX() - context.space.ox()) * 32,
            -(eye.z - context.placement.translation().getZ() - context.space.oz()) * 32,
            (eye.y - context.placement.translation().getY() - context.space.oy()) * 32};
        int unlocked = ChapterRule.unlocked(context.placement.campaignId());
        double now = System.nanoTime() / 1e9;
        int[] uploads = {UPLOADS_PER_FRAME};
        boolean drew = false;
        for (ScreenView screen : context.screens) {
            MenuState.Screen shown = state.screen(screen.entity);
            if (shown == null || !shown.active() || screen.basis.backfacing(eyeSource)) continue;
            if (!drew) {
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                RenderSystem.enableDepthTest();
                RenderSystem.depthMask(false);
                RenderSystem.disableCull();
                drew = true;
            }
            draw(context, screen, event, eye, unlocked, now, uploads);
        }
        if (drew) {
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
        }
    }

    /** The screen's panel-pixel space as a matrix to camera-relative world blocks. */
    private static Matrix4f panelMatrix(MenuContext context, ScreenView screen, Vec3 eye, Matrix4f pose) {
        double[] upperLeft = context.space.toLocal(screen.basis.upperLeft());
        var translation = context.placement.translation();
        float x = (float) (upperLeft[0] + translation.getX() - eye.x), y = (float) (upperLeft[1] + translation.getY() - eye.y),
            z = (float) (upperLeft[2] + translation.getZ() - eye.z);
        double[] xAxis = SourceSpace.direction(screen.basis.xAxis()), yAxis = SourceSpace.direction(screen.basis.yAxis()),
            normal = SourceSpace.direction(screen.basis.normal());
        float perX = (float) (screen.basis.width() / screen.pixelsWide / SourceSpace.UNITS_PER_BLOCK),
            perY = (float) (screen.basis.height() / screen.pixelsHigh / SourceSpace.UNITS_PER_BLOCK);
        // Panel x runs along the screen's x axis, panel y down the screen, panel z out of the front face (in blocks).
        Matrix4f panel = new Matrix4f(new Vector4f((float) xAxis[0] * perX, (float) xAxis[1] * perX, (float) xAxis[2] * perX, 0),
            new Vector4f((float) -yAxis[0] * perY, (float) -yAxis[1] * perY, (float) -yAxis[2] * perY, 0),
            new Vector4f((float) normal[0], (float) normal[1], (float) normal[2], 0), new Vector4f(x, y, z, 1));
        return new Matrix4f(pose).mul(panel);
    }

    private static void draw(MenuContext context, ScreenView screen, RenderLevelStageEvent event, Vec3 eye, int unlocked, double now, int[] uploads) {
        VguiLayout.Result layout = screen.layout(unlocked);
        Matrix4f matrix = panelMatrix(context, screen, eye, event.getPoseStack().last().pose());
        for (VguiLayout.Widget widget : layout.order()) {
            float layer = (widget.rank + 1) * LAYER_STEP;
            switch (widget.kind) {
                case PANEL -> background(matrix, widget, screen, layer, PanelPosition.flag(widget.props.get("paintbackground"), true), screen.scheme.setting("Panel.BgColor", VguiScheme.Color.BLANK));
                case LIST -> background(matrix, widget, screen, layer, PanelPosition.flag(widget.props.get("paintbackground"), false), VguiScheme.Color.BLANK);
                case IMAGE -> image(context, matrix, widget, screen, layer, now, uploads);
                case LABEL -> label(matrix, widget, screen, layer);
                case BUTTON, CHAPTER -> button(matrix, widget, screen, layer);
            }
        }
        // Images the layout will need later come up a few frames at a time.
        if (uploads[0] > 0) {
            for (VguiLayout.Widget widget : layout.order()) {
                if (widget.kind != VguiLayout.Kind.IMAGE || uploads[0] <= 0) continue;
                ScreensData.Image image = context.data.image(widget.props.get("material"));
                if (image != null && !VguiImages.loaded(context.bundle, image)) {
                    VguiImages.texture(context.bundle, image);
                    uploads[0]--;
                }
            }
        }
    }

    // ---- Controls.

    private static void background(Matrix4f matrix, VguiLayout.Widget widget, ScreenView screen, float layer, boolean paint, VguiScheme.Color fallback) {
        if (!paint) return;
        VguiScheme.Color color = screen.scheme.color(widget.props.get("bgcolor_override"));
        fill(matrix, widget.clip, widget.x, widget.y, widget.x + widget.w, widget.y + widget.h, color == null ? fallback : color, layer);
    }

    private static void image(MenuContext context, Matrix4f matrix, VguiLayout.Widget widget, ScreenView screen, float layer, double now, int[] uploads) {
        double alpha = screen.alpha(widget, now);
        ScreensData.Image image = context.data.image(widget.props.get("material"));
        if (image == null) return;
        // Images still hidden are uploaded too, a few per frame, so a chapter background is ready before its fade
        // starts instead of loading (and popping in) once it is already half way.
        if (!VguiImages.loaded(context.bundle, image)) {
            if (uploads[0] <= 0) return;
            uploads[0]--;
        }
        ResourceLocation texture = VguiImages.texture(context.bundle, image);
        if (texture == null || alpha <= 0.5) return;
        int x0 = Math.max(widget.x, widget.clip[0]), y0 = Math.max(widget.y, widget.clip[1]);
        int x1 = Math.min(widget.x + widget.w, widget.clip[2]), y1 = Math.min(widget.y + widget.h, widget.clip[3]);
        if (x1 <= x0 || y1 <= y0) return;
        float u0 = (x0 - widget.x) / (float) widget.w, u1 = (x1 - widget.x) / (float) widget.w;
        float v0 = (y0 - widget.y) / (float) widget.h, v1 = (y1 - widget.y) / (float) widget.h;
        int a = (int) Math.max(0, Math.min(255, Math.round(alpha)));
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, texture);
        if (image.additive()) RenderSystem.blendFunc(com.mojang.blaze3d.platform.GlStateManager.SourceFactor.SRC_ALPHA, com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE);
        else RenderSystem.defaultBlendFunc();
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        Vector3f p = new Vector3f();
        // Upper left, lower left, lower right, upper right: counter-clockwise from the front.
        matrix.transformPosition(x0, y0, layer, p); builder.addVertex(p.x, p.y, p.z).setUv(u0, v0).setColor(255, 255, 255, a);
        matrix.transformPosition(x0, y1, layer, p); builder.addVertex(p.x, p.y, p.z).setUv(u0, v1).setColor(255, 255, 255, a);
        matrix.transformPosition(x1, y1, layer, p); builder.addVertex(p.x, p.y, p.z).setUv(u1, v1).setColor(255, 255, 255, a);
        matrix.transformPosition(x1, y0, layer, p); builder.addVertex(p.x, p.y, p.z).setUv(u1, v0).setColor(255, 255, 255, a);
        BufferUploader.drawWithShader(builder.buildOrThrow());
        RenderSystem.defaultBlendFunc();
    }

    private static void label(Matrix4f matrix, VguiLayout.Widget widget, ScreenView screen, float layer) {
        boolean paint = PanelPosition.flag(widget.props.get("paintbackground"), false);
        background(matrix, widget, screen, layer, paint, screen.scheme.setting("Label.BgColor", VguiScheme.Color.BLANK));
        VguiScheme.Color color = screen.scheme.color(widget.props.get("fgcolor_override"));
        if (color == null) color = screen.scheme.setting("Label.TextColor", new VguiScheme.Color(216, 216, 216, 255));
        String alignment = widget.props.get("textalignment", "west").toLowerCase(Locale.ROOT);
        text(matrix, widget, screen, layer, widget.text, widget.props.get("font"), color, alignment,
            PanelPosition.flag(widget.props.get("centerwrap"), false), 0);
    }

    private static void button(Matrix4f matrix, VguiLayout.Widget widget, ScreenView screen, float layer) {
        if (widget.disabled) {
            // Stock Label disabled painting (Label.cpp Paint): the text in DisabledFgColor1 one pixel right and down, then
            // DisabledFgColor2 over it in place; Button paints its normal background (never armed).
            fill(matrix, widget.clip, widget.x, widget.y, widget.x + widget.w, widget.y + widget.h, screen.scheme.setting("Button.BgColor", VguiScheme.Color.BLANK), layer);
            text(matrix, widget, screen, layer, widget.text, "MenuLarge", screen.scheme.setting("Label.DisabledFgColor1", VguiScheme.Color.BLANK), "west", false, 6, 1);
            text(matrix, widget, screen, layer + LAYER_STEP / 2, widget.text, "MenuLarge", screen.scheme.setting("Label.DisabledFgColor2", VguiScheme.Color.BLANK), "west", false, 6, 0);
            return;
        }
        boolean armed = screen.hovered == widget;
        VguiScheme.Color bg = armed ? screen.scheme.setting("Button.ArmedBgColor", new VguiScheme.Color(255, 255, 255, 255))
            : screen.scheme.setting("Button.BgColor", VguiScheme.Color.BLANK);
        VguiScheme.Color fg = armed ? screen.scheme.setting("Button.ArmedTextColor", new VguiScheme.Color(0, 0, 0, 255))
            : screen.scheme.setting("Button.TextColor", new VguiScheme.Color(255, 255, 255, 255));
        fill(matrix, widget.clip, widget.x, widget.y, widget.x + widget.w, widget.y + widget.h, bg, layer);
        // Stock Button text: the Label default alignment (west, centred vertically) inset 6 pixels.
        text(matrix, widget, screen, layer, widget.text, "MenuLarge", fg, "west", false, 6);
    }

    // ---- Quads.

    private static void fill(Matrix4f matrix, int[] clip, int x0, int y0, int x1, int y1, VguiScheme.Color color, float layer) {
        if (color.a() <= 0) return;
        x0 = Math.max(x0, clip[0]); y0 = Math.max(y0, clip[1]); x1 = Math.min(x1, clip[2]); y1 = Math.min(y1, clip[3]);
        if (x1 <= x0 || y1 <= y0) return;
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.defaultBlendFunc();
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        Vector3f p = new Vector3f();
        matrix.transformPosition(x0, y0, layer, p); builder.addVertex(p.x, p.y, p.z).setColor(color.r(), color.g(), color.b(), color.a());
        matrix.transformPosition(x0, y1, layer, p); builder.addVertex(p.x, p.y, p.z).setColor(color.r(), color.g(), color.b(), color.a());
        matrix.transformPosition(x1, y1, layer, p); builder.addVertex(p.x, p.y, p.z).setColor(color.r(), color.g(), color.b(), color.a());
        matrix.transformPosition(x1, y0, layer, p); builder.addVertex(p.x, p.y, p.z).setColor(color.r(), color.g(), color.b(), color.a());
        BufferUploader.drawWithShader(builder.buildOrThrow());
    }

    // ---- Text.

    private static final ResourceLocation REGULAR = ResourceLocation.fromNamespaceAndPath(Src2mc.MOD_ID, "source_sans_regular");
    private static final ResourceLocation LIGHT = ResourceLocation.fromNamespaceAndPath(Src2mc.MOD_ID, "source_sans_light");

    private static ResourceLocation fontLocation(String family) {
        if (family.equals("sourcesanspro-light")) return LIGHT;
        if (!family.equals("sourcesanspro-regular") && MISSING_FONTS.add(family)) {
            Src2mc.LOGGER.info("src2mc menu: no bundled font for {}; using Source Sans Pro Regular", family);
        }
        return REGULAR;
    }

    /**
     * One control's text in the rectangle of the widget. The font is a scheme font name; {@code alignment} a vgui
     * {@code textAlignment}; {@code inset} pixels of room on the west side. Text is not cut at the clip rectangle,
     * only dropped when the rectangle is wholly outside it.
     */
    private static void text(Matrix4f matrix, VguiLayout.Widget widget, ScreenView screen, float layer, String text, String fontName,
                             VguiScheme.Color color, String alignment, boolean wrap, int inset) {
        text(matrix, widget, screen, layer, text, fontName, color, alignment, wrap, inset, 0);
    }

    /** As above, drawn {@code shift} panel pixels right and down. */
    private static void text(Matrix4f matrix, VguiLayout.Widget widget, ScreenView screen, float layer, String text, String fontName,
                             VguiScheme.Color color, String alignment, boolean wrap, int inset, int shift) {
        if (text.isEmpty() || color.a() <= 0) return;
        if (widget.x >= widget.clip[2] || widget.y >= widget.clip[3] || widget.x + widget.w <= widget.clip[0] || widget.y + widget.h <= widget.clip[1]) return;
        VguiScheme.Font font = screen.scheme.font(fontName == null ? "MenuLarge" : fontName);
        if (font == null) font = new VguiScheme.Font("sourcesanspro-regular", 28);
        int tall = font.tall() > 0 ? font.tall() : 28;
        double emPixels = tall / LINE_PER_EM;
        float scale = (float) (emPixels / FONT_SIZE);
        Style style = Style.EMPTY.withFont(fontLocation(font.family()));
        Minecraft minecraft = Minecraft.getInstance();
        Font renderer = minecraft.font;
        List<String> lines = wrap ? wrapLines(renderer, text, style, (widget.w - inset) / scale) : List.of(text);
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        boolean north = alignment.contains("north"), south = alignment.contains("south");
        boolean east = alignment.endsWith("east"), west = alignment.endsWith("west");
        double blockHeight = lines.size() * tall;
        double top = north ? widget.y : south ? widget.y + widget.h - blockHeight : widget.y + (widget.h - blockHeight) / 2;
        for (int i = 0; i < lines.size(); i++) {
            Component component = Component.literal(lines.get(i)).withStyle(style);
            double width = renderer.getSplitter().stringWidth(component) * scale;
            double left = wrap || !(east || west) ? widget.x + inset + (widget.w - inset - width) / 2 : east ? widget.x + widget.w - width : widget.x + inset;
            double lineTop = top + i * tall;
            double originY = lineTop + ASCENT_PER_EM * emPixels - BASELINE * scale;
            Matrix4f placed = new Matrix4f(matrix).translate((float) (left + shift), (float) (originY + shift), layer).scale(scale, scale, 1f);
            renderer.drawInBatch(component, 0f, 0f, color.argb(), false, placed, buffers, Font.DisplayMode.NORMAL, 0, FULL_BRIGHT);
        }
        buffers.endBatch();
    }

    /** Greedy word wrap to {@code limit} font units. */
    private static List<String> wrapLines(Font renderer, String text, Style style, double limit) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && renderer.getSplitter().stringWidth(Component.literal(candidate).withStyle(style)) > limit) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        lines.add(line.toString());
        return lines;
    }
}
