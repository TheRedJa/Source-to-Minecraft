package dev.theredja.src2mc.client.view;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.client.look.LookClient;
import dev.theredja.src2mc.client.render.MonitorScreens;
import dev.theredja.src2mc.view.ViewMath;
import dev.theredja.src2mc.view.ViewState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.lwjgl.opengl.GL11;

/**
 * Source's {@code _rt_Camera}: the one square target every {@code point_camera} draws into and every monitor
 * material samples (baseclientrendertargets.cpp:37-46; 256 a side, the SDK default, baseclientrendertargets.h:38).
 * Before each frame, as {@code DrawMonitors} runs before the main view's scene (viewrender.cpp:2074-2081), the
 * camera a visible monitor shows is drawn into it with {@link SecondaryView}: the camera's pose and FOV from the
 * networked view state, square ({@code m_flAspectRatio} 1, viewrender.cpp:3218, so the vertical FOV is the camera's
 * own: {@code CalcFovY(fov, 1)}, mathlib_base.cpp:3893), with the camera's fog when it has its own
 * (viewrender.cpp:3177-3207); no HUD and no view model, as {@code Push3DView} + {@code ViewDrawScene} draw neither.
 *
 * <p>Source draws every active, transmitted camera into the same target, so monitors show the last one drawn
 * (viewrender.cpp:3256-3282); only that one is drawn here. A camera is transmitted only while a monitor linked to it
 * is drawn and in the player's PVS ({@link MonitorScreens#visibleMonitors()}). The client's camera list is newest
 * first (cliententitylist.h:54-58), so the last drawn is the oldest; that is taken to be the lowest entity index.
 * With no such camera the target keeps what it last held (black at first).
 */
@EventBusSubscriber(modid = Src2mc.MOD_ID, value = Dist.CLIENT)
public final class MonitorCameras {
    private MonitorCameras() {}

    /** {@code iCameraTextureSize}'s default (baseclientrendertargets.h:38). */
    public static final int SIZE = 256;

    /** Draws the cameras monitors show; off freezes the target with what it last held. */
    public static boolean enabled = true;
    /** Debug: draws the lowest-index active camera even with no monitor in view. */
    public static boolean force;
    /** Camera views drawn in the last whole second. */
    public static int rendersPerSecond;

    private static RenderTarget target;
    private static int rendersThisSecond;
    private static long secondStart;
    /** The camera drawn last frame: its entity, -1 for none. */
    private static int lastCamera = -1;
    /** Where the camera drawn last frame looked from, for the status line. */
    private static String lastView = "";

    /** The target's colour texture, created (black) on first use. */
    public static int texture() {
        RenderSystem.assertOnRenderThread();
        if (target == null) {
            target = new TextureTarget(SIZE, SIZE, true, Minecraft.ON_OSX);
            target.setClearColor(0, 0, 0, 1);
            target.clear(Minecraft.ON_OSX);
            target.setFilterMode(GL11.GL_LINEAR);
            // Created without clamp flags (baseclientrendertargets.cpp:41-45), so the picture repeats.
            GlStateManager._bindTexture(target.getColorTextureId());
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
            GlStateManager._bindTexture(0);
            Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
        }
        return target.getColorTextureId();
    }

    public static String status() {
        return "monitor cameras " + (enabled ? "on" : "off") + (force ? " (forced)" : "") + ", " + rendersPerSecond + " camera renders/s, last camera "
            + (lastCamera < 0 ? "none" : "#" + lastCamera + " " + lastView) + ", exposure "
            + String.format(Locale.ROOT, "%.3f", dev.theredja.src2mc.client.render.BakedLighting.currentExposure()) + "; "
            + SecondaryView.Counts.line() + "; " + MonitorScreens.status();
    }

    /** Saves what the target holds now as a PNG in the game's screenshots folder; the file's path, or why not. */
    public static String dump() {
        RenderSystem.assertOnRenderThread();
        if (target == null) return "no camera target yet: no monitor has been in view";
        Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("screenshots");
        Path file = dir.resolve("src2mc_camera_" + System.currentTimeMillis() + ".png");
        try (NativeImage image = new NativeImage(SIZE, SIZE, false)) {
            RenderSystem.bindTexture(target.getColorTextureId());
            image.downloadTexture(0, true);
            image.flipY();
            Files.createDirectories(dir);
            image.writeToFile(file);
        } catch (IOException e) {
            return "camera target not saved: " + e.getMessage();
        } finally {
            RenderSystem.bindTexture(0);
        }
        return "camera target saved to " + file + "; " + status();
    }

    @SubscribeEvent
    static void onFramePre(RenderFrameEvent.Pre event) {
        long now = System.nanoTime();
        if (now - secondStart >= 1_000_000_000L) {
            rendersPerSecond = rendersThisSecond;
            rendersThisSecond = 0;
            secondStart = now;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (!enabled || minecraft.level == null || minecraft.player == null) {
            SecondaryView.setMonitorPoints(List.of());
            lastCamera = -1;
            return;
        }
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Set<MonitorScreens.MonitorKey> visible = MonitorScreens.visibleMonitors();
        ViewClient.Placed chosenMap = null;
        ViewState.Camera chosen = null;
        if (!visible.isEmpty() || force) {
            for (ViewClient.Placed placed : ViewClient.all()) {
                long anchor = placed.placement().anchorWorld().asLong();
                for (ViewState.Camera camera : placed.state().cameras()) {
                    if (!camera.active() || (chosen != null && camera.entity() >= chosen.entity())) continue;
                    if (force) {
                        chosen = camera;
                        chosenMap = placed;
                        continue;
                    }
                    for (int monitor : camera.monitors()) {
                        if (!visible.contains(new MonitorScreens.MonitorKey(anchor, monitor))) continue;
                        chosen = camera;
                        chosenMap = placed;
                        break;
                    }
                }
            }
        }
        if (chosen == null) {
            SecondaryView.setMonitorPoints(List.of());
            lastCamera = -1;
            return;
        }
        ViewClient.WorldPose pose = ViewClient.cameraPose(chosenMap, chosen, partialTick);
        SecondaryView.setMonitorPoints(List.of(new Vec3(pose.position().x, pose.position().y, pose.position().z)));
        texture();
        LookClient.Frame frame = LookClient.frame();
        LookClient.Frame own = frame;
        if (chosen.fogEnable() && frame != null) {
            // fogStart/fogEnd/fogMaxDensity/fogColor replace the player's (viewrender.cpp:3186-3199); radial fog is not carried.
            int c = chosen.fogColor();
            own = new LookClient.Frame(frame.map(), frame.look(), true, chosen.fogStart() / 32f, chosen.fogEnd() / 32f,
                chosen.fogMaxDensity(), new float[] {(c >> 16 & 0xFF) / 255f, (c >> 8 & 0xFF) / 255f, (c & 0xFF) / 255f},
                frame.exposureMin(), frame.exposureMax(), frame.customBloom(), frame.bloomScale(), frame.rate(), frame.lookups(),
                frame.defaultWeight());
        }
        LookClient.Frame replaced = LookClient.swapFrame(own);
        try {
            // CalcFovY(fov, 1) is the FOV itself.
            SecondaryView.render(target, pose, chosen.fov());
        } finally {
            LookClient.swapFrame(replaced);
        }
        rendersThisSecond++;
        lastCamera = chosen.entity();
        float[] angles = ViewMath.minecraftAngles(pose.rotation());
        lastView = String.format(Locale.ROOT, "at %.1f %.1f %.1f yaw %.0f pitch %.0f fov %.0f", pose.position().x, pose.position().y,
            pose.position().z, angles[0], angles[1], chosen.fov());
    }

    @SubscribeEvent
    static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        SecondaryView.setMonitorPoints(List.of());
        lastCamera = -1;
    }
}
