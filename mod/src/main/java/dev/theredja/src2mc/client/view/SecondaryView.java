package dev.theredja.src2mc.client.view;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.theredja.src2mc.client.render.AnimatedPropRenderer;
import dev.theredja.src2mc.client.render.DecalRenderer;
import dev.theredja.src2mc.client.render.LogicPropRenderer;
import dev.theredja.src2mc.client.render.MapSurfaceRenderer;
import dev.theredja.src2mc.client.render.MoverRenderer;
import dev.theredja.src2mc.client.render.PropRenderer;
import dev.theredja.src2mc.client.render.SkyRenderer;
import dev.theredja.src2mc.view.ViewMath;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * Draws the placed maps from a camera other than the player's into an offscreen target: Source's
 * {@code point_camera} views and {@code script_intro}'s camera view. Only this mod's own renderers draw, called
 * directly with synthetic stage events in the order the frame calls them; while {@link #active()} each renderer
 * draws what it has without its per-frame bookkeeping (builds, residency, occlusion queries, simulation), which only
 * the player's view runs. The player's view keeps the geometry near every camera that is drawn
 * ({@link #interestPoints()}) built, so a camera far from the player still sees the map.
 */
public final class SecondaryView {
    private SecondaryView() {}

    private static boolean active;
    private static RenderTarget target;
    private static Vec3 cameraPosition;
    private static List<Vec3> interestPoints = List.of();
    /** The intro's camera and the monitors' camera, set apart by their own frame handlers. */
    private static List<Vec3> introPoints = List.of(), monitorPoints = List.of();

    /**
     * What the last camera view drew and had to draw with, by renderer: for {@code /src2mcc status monitors}, to tell a view
     * that has nothing resident (surfaceFlagged 0, regionsBuilt below regionsWanted) from one that is drawn but shows
     * something else. Written by the renderers while {@link #active()}; render thread only.
     */
    public static final class Counts {
        private Counts() {}
        /** Surface meshes kept for the camera by the player's pass, and how many of them passed the camera's frustum. */
        public static int surfaceFlagged, surfaceDrawn;
        /** Surface regions within range of a camera, and how many of them hold built meshes, as of the player's last pass. */
        public static int regionsWanted, regionsBuilt;
        /** Static prop aggregates, logic props, animated props' buffers and mover buffers drawn (opaque pass). */
        public static int aggregates, logicProps, animatedBuffers, moverBuffers;

        static void reset() { surfaceFlagged = surfaceDrawn = aggregates = logicProps = animatedBuffers = moverBuffers = 0; }

        public static String line() {
            return "camera view drew " + surfaceDrawn + "/" + surfaceFlagged + " surface meshes (regions in range " + regionsBuilt + "/" + regionsWanted
                + " built), " + aggregates + " prop aggregates, " + logicProps + " logic prop and " + animatedBuffers + " animated prop and "
                + moverBuffers + " mover buffers";
        }
    }

    /** Whether a camera's view is being drawn now. */
    public static boolean active() { return active; }

    /** The target the camera's view draws into; only while {@link #active()}. */
    public static RenderTarget target() { return target; }

    /** Where the camera whose view is being drawn is, in world blocks; only while {@link #active()}. */
    public static Vec3 cameraPosition() { return cameraPosition; }

    /** World positions of the cameras drawn this frame besides the player's. */
    public static List<Vec3> interestPoints() { return interestPoints; }

    static void setInterestPoints(List<Vec3> points) { introPoints = List.copyOf(points); combinePoints(); }

    /** The camera a monitor shows this frame ({@link MonitorCameras}), none when empty. */
    static void setMonitorPoints(List<Vec3> points) { monitorPoints = List.copyOf(points); combinePoints(); }

    private static void combinePoints() {
        if (monitorPoints.isEmpty()) interestPoints = introPoints;
        else if (introPoints.isEmpty()) interestPoints = monitorPoints;
        else {
            List<Vec3> both = new ArrayList<>(introPoints);
            both.addAll(monitorPoints);
            interestPoints = List.copyOf(both);
        }
    }

    /** Whether a camera drawn this frame is within {@code sections} sections of world column {@code x, z}. */
    public static boolean near(double x, double z, int sections) {
        if (interestPoints.isEmpty()) return false;
        int sx = SectionPos.blockToSectionCoord(x), sz = SectionPos.blockToSectionCoord(z);
        for (Vec3 point : interestPoints) {
            if (Math.abs(SectionPos.blockToSectionCoord(point.x) - sx) <= sections
                && Math.abs(SectionPos.blockToSectionCoord(point.z) - sz) <= sections) return true;
        }
        return false;
    }

    /** Whether a camera drawn this frame is within {@code reach} blocks of world column {@code x, z}, horizontally. */
    public static boolean within(double x, double z, double reach) {
        for (Vec3 point : interestPoints) {
            double dx = point.x - x, dz = point.z - z;
            if (dx * dx + dz * dz <= reach * reach) return true;
        }
        return false;
    }

    /** A camera placed by hand: no entity, no setup. */
    private static final class ViewCamera extends Camera {
        void place(ViewClient.WorldPose pose) {
            float[] angles = ViewMath.minecraftAngles(pose.rotation());
            setPosition(new Vec3(pose.position().x, pose.position().y, pose.position().z));
            setRotation(angles[0], angles[1], angles[2]);
        }
    }

    /**
     * Draws the maps as seen from {@code pose} with vertical field of view {@code fovY} degrees into {@code into},
     * cleared to black first. Leaves the main target bound.
     */
    static void render(RenderTarget into, ViewClient.WorldPose pose, double fovY) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || active) return;
        ViewCamera camera = new ViewCamera();
        camera.place(pose);
        Matrix4f modelView = new Matrix4f().rotation(camera.rotation().conjugate(new Quaternionf()));
        Matrix4f projection = new Matrix4f().setPerspective((float) Math.toRadians(fovY), (float) into.width / into.height, 0.05f,
            minecraft.gameRenderer.getDepthFar());
        Frustum frustum = new Frustum(modelView, projection);
        Vec3 at = camera.getPosition();
        frustum.prepare(at.x, at.y, at.z);
        Counts.reset();
        into.setClearColor(0, 0, 0, 1);
        into.clear(Minecraft.ON_OSX);
        into.bindWrite(true);
        active = true;
        target = into;
        cameraPosition = at;
        try {
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            RenderLevelStageEvent opaque = new RenderLevelStageEvent(MapSurfaceRenderer.opaqueStage(), minecraft.levelRenderer, null,
                modelView, projection, 0, minecraft.getTimer(), camera, frustum);
            SkyRenderer.beforeMap(opaque);
            MapSurfaceRenderer.render(opaque);
            LogicPropRenderer.render(opaque);
            AnimatedPropRenderer.render(opaque);
            MoverRenderer.render(opaque);
            DecalRenderer.render(opaque);
            PropRenderer.render(opaque);
            SkyRenderer.afterMap(opaque);
            RenderLevelStageEvent translucent = new RenderLevelStageEvent(RenderLevelStageEvent.Stage.AFTER_PARTICLES,
                minecraft.levelRenderer, null, modelView, projection, 0, minecraft.getTimer(), camera, frustum);
            SkyRenderer.beforeGlass(translucent);
            MapSurfaceRenderer.render(translucent);
            LogicPropRenderer.render(translucent);
            AnimatedPropRenderer.render(translucent);
            MoverRenderer.render(translucent);
            PropRenderer.render(translucent);
        } finally {
            active = false;
            target = null;
            cameraPosition = null;
            minecraft.getMainRenderTarget().bindWrite(true);
        }
    }

    /** Positions of {@code poses}, as world vectors. */
    static List<Vec3> positions(List<ViewClient.WorldPose> poses) {
        List<Vec3> out = new ArrayList<>(poses.size());
        for (ViewClient.WorldPose pose : poses) out.add(new Vec3(pose.position().x, pose.position().y, pose.position().z));
        return out;
    }
}
