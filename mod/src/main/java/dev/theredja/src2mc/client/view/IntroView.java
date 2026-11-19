package dev.theredja.src2mc.client.view;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.client.logic.ZoomClient;
import dev.theredja.src2mc.menu.ViewPath;
import dev.theredja.src2mc.view.IntroMath;
import dev.theredja.src2mc.view.ViewState;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * Half-Life 2's {@code script_intro} on the client ({@code CViewRender::ViewDrawScene_Intro}, viewrender.cpp:2978):
 * the camera entity's view is drawn into its own target before the frame, the player's view is drawn as always, and
 * once the world is drawn the two are blended by the intro's blend modes into the frame, before Source's
 * post-processing. The intro's fade is drawn over the result, under the HUD.
 */
@EventBusSubscriber(modid = Src2mc.MOD_ID, value = Dist.CLIENT)
public final class IntroView {
    private IntroView() {}

    private static RenderTarget sceneTarget;
    private static int program = -1, vao = -1, gmanTexture = -1, gmanFbo = -1, gmanWidth, gmanHeight;
    private static boolean failed;
    /** The intro this frame and whether its camera view was drawn into {@link #sceneTarget}. */
    private static ViewState.Intro frameIntro;
    private static boolean sceneDrawn;

    /** Before the frame: draws the camera view and tells the renderers where it looks from. */
    @SubscribeEvent
    static void onFramePre(RenderFrameEvent.Pre event) {
        frameIntro = null;
        sceneDrawn = false;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            SecondaryView.setInterestPoints(List.of());
            return;
        }
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        ViewClient.Placed placed = ViewClient.current();
        ViewState.Intro intro = placed == null ? null : placed.state().intro();
        List<Vec3> points = new ArrayList<>();
        ViewClient.WorldPose camera = null;
        if (intro != null && intro.active()) {
            frameIntro = intro;
            camera = intro.drawPrimary() ? ViewClient.introCamera(placed, partialTick) : null;
            if (camera != null) points.add(new Vec3(camera.position().x, camera.position().y, camera.position().z));
        }
        SecondaryView.setInterestPoints(points);
        dev.theredja.src2mc.client.render.DynamicLights.update(partialTick);
        if (camera == null) return;
        RenderTarget main = minecraft.getMainRenderTarget();
        if (sceneTarget == null) sceneTarget = new TextureTarget(main.width, main.height, true, Minecraft.ON_OSX);
        else if (sceneTarget.width != main.width || sceneTarget.height != main.height) sceneTarget.resize(main.width, main.height, Minecraft.ON_OSX);
        double now = ViewClient.now(partialTick);
        double viewFov = IntroMath.playerViewFov(intro, now);
        SecondaryView.render(sceneTarget, camera, viewFov != 0 ? ViewPath.verticalFov(viewFov) : playerFovY(partialTick));
        sceneDrawn = true;
    }

    /** The player's own vertical FOV: an env_zoom's, else the option's. */
    private static double playerFovY(float partialTick) {
        double zoom = ZoomClient.sourceFov(partialTick);
        return Double.isNaN(zoom) ? Minecraft.getInstance().options.fov().get() : ViewPath.verticalFov(zoom);
    }

    /** Once the world is drawn, before Source's post-processing: blends the two views into the frame. */
    public static void composite() {
        ViewState.Intro intro = frameIntro;
        if (intro == null || failed) return;
        RenderSystem.assertOnRenderThread();
        Minecraft minecraft = Minecraft.getInstance();
        RenderTarget main = minecraft.getMainRenderTarget();
        if (main.width <= 0 || main.height <= 0) return;
        double now = ViewClient.now(minecraft.getTimer().getGameTimeDeltaPartialTick(false));
        // C_ScriptIntro::ClientThink: a blend under way draws both modes, by its share; else the current mode alone.
        int mode0 = intro.mode(), mode1 = intro.nextMode();
        float alpha0 = 1, alpha1 = 0;
        if (intro.blendEnd() > now && intro.nextMode() >= 0) {
            double share = IntroMath.blend(now, intro.blendStart(), intro.blendEnd());
            alpha0 = (float) (1 - share);
            alpha1 = (float) share;
        } else if (intro.nextMode() >= 0 && intro.blendEnd() <= now && intro.blendEnd() > intro.blendStart()) {
            mode0 = intro.nextMode();
        }
        int[] saved = save();
        try {
            if (!ensure(main.width, main.height)) return;
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDepthMask(false);
            GL30.glBindVertexArray(vao);
            // The player's view, copied out to be read while the frame is written.
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, gmanFbo);
            GL30.glBlitFramebuffer(0, 0, main.width, main.height, 0, 0, main.width, main.height, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, main.frameBufferId);
            GL11.glViewport(0, 0, main.width, main.height);
            GL20.glUseProgram(program);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            // No camera view drawn: the primary view is Source's cleared black target.
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, sceneDrawn ? sceneTarget.getColorTextureId() : blackTexture());
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, gmanTexture);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "Scene"), 0);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "Gman"), 1);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "Mode0"), mode0);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "Mode1"), Math.max(0, mode1));
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "Alpha0"), alpha0);
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "Alpha1"), alpha1);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        } finally {
            restore(saved);
        }
    }

    private static int black = -1;

    private static int blackTexture() {
        if (black < 0) {
            black = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, black);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 1, 1, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, new int[]{0xFF000000});
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        }
        return black;
    }

    private static boolean ensure(int width, int height) {
        try {
            if (program < 0) {
                program = link();
                vao = GL30.glGenVertexArrays();
            }
        } catch (IOException | IllegalStateException e) {
            Src2mc.LOGGER.error("src2mc intro composite disabled", e);
            failed = true;
            return false;
        }
        if (gmanTexture < 0 || gmanWidth != width || gmanHeight != height) {
            if (gmanTexture >= 0) GL11.glDeleteTextures(gmanTexture);
            if (gmanFbo >= 0) GL30.glDeleteFramebuffers(gmanFbo);
            gmanTexture = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, gmanTexture);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            gmanFbo = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, gmanFbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, gmanTexture, 0);
            gmanWidth = width;
            gmanHeight = height;
        }
        return true;
    }

    private static int link() throws IOException {
        int vertex = shader(GL20.GL_VERTEX_SHADER, "fullscreen.vsh");
        int pixel = shader(GL20.GL_FRAGMENT_SHADER, "intro.fsh");
        int linked = GL20.glCreateProgram();
        GL20.glAttachShader(linked, vertex);
        GL20.glAttachShader(linked, pixel);
        GL20.glLinkProgram(linked);
        GL20.glDeleteShader(vertex);
        GL20.glDeleteShader(pixel);
        if (GL20.glGetProgrami(linked, GL20.GL_LINK_STATUS) == 0) throw new IllegalStateException("linking intro: " + GL20.glGetProgramInfoLog(linked));
        return linked;
    }

    private static int shader(int type, String name) throws IOException {
        String text;
        try (InputStream in = IntroView.class.getResourceAsStream("/assets/src2mc/shaders/post/" + name)) {
            if (in == null) throw new IOException("missing shader " + name);
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, text);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) throw new IllegalStateException("compiling " + name + ": " + GL20.glGetShaderInfoLog(shader));
        return shader;
    }

    /** The GL state the composite changes: program, vertex array, framebuffers, texture units 0-1, viewport, capabilities. */
    private static int[] save() {
        int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int t0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        int t1 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL13.glActiveTexture(active);
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        return new int[]{GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM), GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING),
            GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING), GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING), active, t0, t1,
            viewport[0], viewport[1], viewport[2], viewport[3], GL11.glIsEnabled(GL11.GL_DEPTH_TEST) ? 1 : 0,
            GL11.glIsEnabled(GL11.GL_BLEND) ? 1 : 0, GL11.glIsEnabled(GL11.GL_CULL_FACE) ? 1 : 0, GL11.glIsEnabled(GL11.GL_SCISSOR_TEST) ? 1 : 0,
            GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK) ? 1 : 0};
    }

    private static void restore(int[] s) {
        GL20.glUseProgram(s[0]);
        GL30.glBindVertexArray(s[1]);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, s[2]);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, s[3]);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, s[5]);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, s[6]);
        GL13.glActiveTexture(s[4]);
        GL11.glViewport(s[7], s[8], s[9], s[10]);
        set(GL11.GL_DEPTH_TEST, s[11]);
        set(GL11.GL_BLEND, s[12]);
        set(GL11.GL_CULL_FACE, s[13]);
        set(GL11.GL_SCISSOR_TEST, s[14]);
        GL11.glDepthMask(s[15] != 0);
    }

    private static void set(int capability, int on) {
        if (on != 0) GL11.glEnable(capability);
        else GL11.glDisable(capability);
    }

    /** The intro's fade (view_effects.cpp:616-628): its colour at its alpha over the whole screen, under the HUD. */
    @SubscribeEvent
    static void onGuiPre(RenderGuiEvent.Pre event) {
        ViewState.Intro intro = frameIntro;
        if (intro == null) return;
        double alpha = IntroMath.fade(intro, ViewClient.now(event.getPartialTick().getGameTimeDeltaPartialTick(false)));
        if (alpha <= 0) return;
        GuiGraphics graphics = event.getGuiGraphics();
        int a = (int) Math.round(Math.min(255, alpha));
        int color = a << 24 | channel(intro.red()) << 16 | channel(intro.green()) << 8 | channel(intro.blue());
        graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), color);
    }

    private static int channel(float value) { return Math.max(0, Math.min(255, Math.round(value))); }

    /** Half-Life 2 has no HUD before the suit: while the intro runs, the hotbar, crosshair and hand are hidden. */
    private static final List<ResourceLocation> HIDDEN_LAYERS = List.of(VanillaGuiLayers.CROSSHAIR, VanillaGuiLayers.HOTBAR,
        VanillaGuiLayers.JUMP_METER, VanillaGuiLayers.EXPERIENCE_BAR, VanillaGuiLayers.PLAYER_HEALTH, VanillaGuiLayers.ARMOR_LEVEL,
        VanillaGuiLayers.FOOD_LEVEL, VanillaGuiLayers.VEHICLE_HEALTH, VanillaGuiLayers.AIR_LEVEL, VanillaGuiLayers.SELECTED_ITEM_NAME,
        VanillaGuiLayers.EXPERIENCE_LEVEL, VanillaGuiLayers.EFFECTS);

    @SubscribeEvent
    static void onGuiLayer(RenderGuiLayerEvent.Pre event) {
        if (frameIntro != null && HIDDEN_LAYERS.contains(event.getName())) event.setCanceled(true);
    }

    @SubscribeEvent
    static void onHand(RenderHandEvent event) {
        if (frameIntro != null) event.setCanceled(true);
    }

    /**
     * While the intro runs the game drives the camera: no walking, jumping, sneaking or flying (a spectator's sneak
     * would sink the player out of the place the map set), as {@code MenuClient} holds a player behind a viewcontrol.
     * Looking around stays free.
     */
    @SubscribeEvent
    static void onMovement(net.neoforged.neoforge.client.event.MovementInputUpdateEvent event) {
        if (frameIntro == null) return;
        var input = event.getInput();
        input.leftImpulse = 0;
        input.forwardImpulse = 0;
        input.up = input.down = input.left = input.right = false;
        input.jumping = false;
        input.shiftKeyDown = false;
    }

    @SubscribeEvent
    static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ViewClient.clear();
        frameIntro = null;
        SecondaryView.setInterestPoints(List.of());
    }
}
