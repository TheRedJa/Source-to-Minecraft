package dev.theredja.src2mc.client.look;

import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.client.render.IrisCompat;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/**
 * Handlers for {@code /src2mcc look} and parts of {@code /src2mcc light}: switches for each part of Source's post-processing, a held tone map
 * scale, and what the camera's map makes of its view this frame.
 */
@EventBusSubscriber(modid = Src2mc.MOD_ID, value = Dist.CLIENT)
public final class LookCommands {
    private LookCommands() {}

    @SubscribeEvent
    static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        LookClient.clear();
        SourcePost.resetState();
        dev.theredja.src2mc.network.PlacementNetwork.clearClient();
    }

    /** The switches {@link #toggle} knows, for {@code /src2mcc look}. */
    public static final java.util.List<String> LOOK_SWITCHES = java.util.List.of(
        "bloom", "exposure", "correction", "vignette", "grain", "fog", "reflections", "detail", "blend", "selfillum", "srgb", "dither");
    /** The switches {@link #toggle} knows, for {@code /src2mcc light}. */
    public static final java.util.List<String> LIGHT_SWITCHES = java.util.List.of("phong", "rimlight", "proplights", "sun", "lightvis");

    /** Sets the named switch; {@code post} is the whole of Source's post-processing. */
    public static int toggle(CommandSourceStack source, String name, boolean value) {
        String what;
        switch (name) {
            case "post" -> { SourcePost.enabled = value; what = "post-processing"; }
            case "bloom" -> { SourcePost.bloomOn = value; what = "bloom"; }
            case "exposure" -> { SourcePost.exposureOn = value; what = "auto exposure"; }
            case "correction" -> { SourcePost.correctionOn = value; what = "colour correction"; }
            case "vignette" -> { SourcePost.vignetteOn = value; what = "vignette"; }
            case "grain" -> { SourcePost.grainOn = value; what = "film grain (on follows the map's data)"; }
            case "fog" -> { LookClient.fogOn = value; what = "Source fog"; }
            case "reflections" -> { dev.theredja.src2mc.client.render.SurfaceEffects.enabled = value; what = "cubemap reflections"; }
            case "detail" -> { dev.theredja.src2mc.client.render.SurfaceEffects.detailOn = value; what = "detail textures"; }
            case "blend" -> { dev.theredja.src2mc.client.render.SurfaceEffects.blendOn = value; what = "displacement blending"; }
            case "selfillum" -> { dev.theredja.src2mc.client.render.SurfaceEffects.selfillumOn = value; what = "self-illumination"; }
            case "lightonly" -> { dev.theredja.src2mc.client.render.SurfaceEffects.lightOnly = value; what = "light-only grey albedo"; }
            case "phong" -> { dev.theredja.src2mc.client.render.PropLights.phongOn = value; what = "phong on props (off: baked light only)"; }
            case "rimlight" -> { dev.theredja.src2mc.client.render.PropLights.rimOn = value; what = "phong rim light"; }
            case "proplights" -> { dev.theredja.src2mc.client.render.PropLights.propLightsOn = value; what = "world lights on non-phong moving props (phong always uses them)"; }
            case "sun" -> { dev.theredja.src2mc.client.render.PropLights.setSunOn(value); what = "skylight on props (off: excluded everywhere)"; }
            case "lightvis" -> { dev.theredja.src2mc.client.render.PropLights.setLightVisOn(value); what = "hide lights behind walls from props (visibility lists)"; }
            case "srgb" -> { dev.theredja.src2mc.client.render.SurfaceEffects.srgbCurve = value; what = "exact sRGB curve"; }
            case "dither" -> { dev.theredja.src2mc.client.render.SurfaceEffects.ditherAll = value; what = "dither on every path"; }
            default -> throw new IllegalArgumentException("unknown look switch " + name);
        }
        return reply(source, "src2mc " + what + (value ? " on" : " off"));
    }

    /** {@code light atten}: Source's phong attenuation per vertex, or per pixel. */
    public static int lightAtten(CommandSourceStack source, boolean vertex) {
        dev.theredja.src2mc.client.render.PropLights.lightAttenVertex = vertex;
        return reply(source, vertex ? "src2mc phong light attenuation per vertex (Source)" : "src2mc phong light attenuation per pixel");
    }

    /** {@code look measure}: whether auto exposure measures stored gamma values or linear light. */
    public static int measure(CommandSourceStack source, boolean linear) {
        SourcePost.measureLinear = linear;
        return reply(source, linear ? "src2mc exposure measures linear light" : "src2mc exposure measures the stored gamma values");
    }

    /** The falloff of {@code light_dynamic}'s lights (the engine's is not public), or none. */
    public static int dlight(CommandSourceStack source, String name, int mode) {
        dev.theredja.src2mc.client.render.DynamicLights.mode = mode;
        return reply(source, "src2mc dynamic lights " + name + " (" + dev.theredja.src2mc.client.render.DynamicLights.lightsLastFrame()
            + " lit last frame)");
    }

    public static int phongDebug(CommandSourceStack source, String name, int mode) {
        dev.theredja.src2mc.client.render.PropLights.phongDebug = mode;
        return reply(source, "src2mc phong debug " + name);
    }

    /** Bump-mapped faces through their bump lightmaps, or flat; surface meshes rebuild to show it. */
    public static int bump(CommandSourceStack source, boolean value) {
        dev.theredja.src2mc.client.render.BakedLighting.bumpEnabled = value;
        reply(source, "src2mc bump mapping " + (value ? "on" : "off") + " (moving parts keep theirs until they rebuild)");
        return dev.theredja.src2mc.client.render.MapSurfaceRenderer.rebuildMeshes(source);
    }

    /** Holds the tone map scale at {@code value}, or back to auto exposure with 0. */
    public static int held(CommandSourceStack source, float value) {
        SourcePost.force(value);
        return reply(source, value > 0 ? "src2mc tone map scale held at " + value : "src2mc tone map scale back to auto exposure");
    }

    public static int grainScale(CommandSourceStack source, float value) {
        SourcePost.grainScale = value;
        return reply(source, value == -1 ? "src2mc film grain scale back to the map's own" : "src2mc film grain scale held at " + value);
    }

    public static String statusText() {
        LookClient.Frame frame = LookClient.frame();
        StringBuilder text = new StringBuilder("src2mc look: post ").append(SourcePost.enabled ? "on" : "off")
            .append(", bloom ").append(SourcePost.bloomOn ? "on" : "off")
            .append(", exposure ").append(SourcePost.exposureOn ? "on" : "off")
            .append(", correction ").append(SourcePost.correctionOn ? "on" : "off")
            .append(", vignette ").append(SourcePost.vignetteOn ? "on" : "off")
            .append(", grain ").append(SourcePost.grainOn ? "on" : "off")
            .append(", fog ").append(LookClient.fogOn ? "on" : "off")
            .append(", bump ").append(dev.theredja.src2mc.client.render.BakedLighting.bumpEnabled ? "on" : "off")
            .append(", reflections ").append(dev.theredja.src2mc.client.render.SurfaceEffects.enabled ? "on" : "off")
            .append(", detail ").append(dev.theredja.src2mc.client.render.SurfaceEffects.detailOn ? "on" : "off")
            .append(", blend ").append(dev.theredja.src2mc.client.render.SurfaceEffects.blendOn ? "on" : "off")
            .append(", selfillum ").append(dev.theredja.src2mc.client.render.SurfaceEffects.selfillumOn ? "on" : "off")
            .append(", lightonly ").append(dev.theredja.src2mc.client.render.SurfaceEffects.lightOnly ? "on" : "off")
            .append(", srgb ").append(dev.theredja.src2mc.client.render.SurfaceEffects.srgbCurve ? "on" : "off")
            .append(", dither ").append(dev.theredja.src2mc.client.render.SurfaceEffects.ditherAll ? "on" : "off")
            .append(", phong ").append(dev.theredja.src2mc.client.render.PropLights.phongOn ? "on" : "off")
            .append(", rimlight ").append(dev.theredja.src2mc.client.render.PropLights.rimOn ? "on" : "off")
            .append(", proplights (non-phong) ").append(dev.theredja.src2mc.client.render.PropLights.propLightsOn ? "on" : "off")
            .append(", lightatten ").append(dev.theredja.src2mc.client.render.PropLights.lightAttenVertex ? "vertex" : "pixel")
            .append(", phongdebug ").append(new String[] {"off", "diffuse", "spec", "lights"}[dev.theredja.src2mc.client.render.PropLights.phongDebug])
            .append("\n").append(SourcePost.status());
        if (IrisCompat.shaderPackInUse()) text.append("\na shader pack is in use, which skips Source's post-processing");
        if (frame == null) {
            text.append("\ncamera in no map");
        } else {
            var look = frame.look();
            text.append(String.format(Locale.ROOT, "\nmap %s: %s, bloom %s, exposure %.2f..%.2f, bloom scale %.2f%s, rate %.2f",
                frame.map().mapId(),
                look == null ? "no look table" : look.hdr() ? "HDR light" : "LDR light",
                look == null || look.bloomType() < 0 ? "SDK 2013" : "later branch, kernel " + look.kernelX() + "/" + look.kernelY(),
                frame.exposureMin(), frame.exposureMax(), frame.bloomScale(), frame.customBloom() ? " (map)" : "", frame.rate()));
            text.append(String.format(Locale.ROOT, "\nfog %s, %.1f..%.1f blocks, max density %.2f, colour %.2f %.2f %.2f",
                frame.fog() ? "on" : "off", frame.fogStart(), frame.fogEnd(), frame.fogMaxDensity(),
                frame.fogColor()[0], frame.fogColor()[1], frame.fogColor()[2]));
            var cubemaps = frame.map().cubemaps();
            long reflecting = frame.map().materials().stream().filter(m -> m.envmap() != null).count();
            var materials = frame.map().materials();
            text.append(String.format(Locale.ROOT, "\n%d cubemap(s), %d reflecting, %d detail, %d blended, %d self-illuminated material(s)",
                cubemaps == null ? 0 : cubemaps.cubes().size(), reflecting,
                materials.stream().filter(m -> m.detail() != null).count(), materials.stream().filter(m -> m.blend()).count(),
                materials.stream().filter(m -> m.selfillum() != null).count()));
            text.append(String.format(Locale.ROOT, "\n%d phong material(s) (%d with rim light), %d world light(s) in the map; %s",
                materials.stream().filter(m -> m.phong() != null).count(), materials.stream().filter(m -> m.phong() != null && m.phong().rim() != null).count(),
                frame.map().worldLights() == null ? 0 : frame.map().worldLights().lights().size(),
                dev.theredja.src2mc.client.render.PropLights.status()));
            text.append(String.format(Locale.ROOT, "\n%d lookup(s) of %d in the map, uncorrected share %.2f",
                frame.lookups().size(), look == null ? 0 : look.lookups().size(), frame.defaultWeight()));
            for (LookClient.Lookup lookup : frame.lookups()) text.append(String.format(Locale.ROOT, " %.2f", lookup.weight()));
            text.append(String.format(Locale.ROOT, "\ngrain: map %s, scale in use %.2f (override %s), drawn %s",
                look != null && look.grain() != null ? String.format(Locale.ROOT, "carries it at %.2f", look.grain().scale()) : "has none",
                SourcePost.grainScaleFor(look), SourcePost.grainScale == -1 ? "none" : String.valueOf(SourcePost.grainScale),
                SourcePost.grainUsed() > 0 ? "yes" : "no (off, no data, or framebuffer under 720 high)"));
            if (look != null && look.vignette() != null) text.append(", vignette ").append(look.vignette().width()).append('x').append(look.vignette().height());
        }
        return text.toString();
    }

    private static int reply(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }
}
