package dev.theredja.src2mc.client;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.client.audio.SourceAudio;
import dev.theredja.src2mc.client.logic.CaptionOverlay;
import dev.theredja.src2mc.client.logic.LogicOverlay;
import dev.theredja.src2mc.client.logic.ScreenOverlay;
import dev.theredja.src2mc.client.look.LookCommands;
import dev.theredja.src2mc.client.menu.MenuClientCommands;
import dev.theredja.src2mc.client.particles.ParticleEffects;
import dev.theredja.src2mc.client.render.AnimatedPropRenderer;
import dev.theredja.src2mc.client.render.BakedLighting;
import dev.theredja.src2mc.client.render.DecalRenderer;
import dev.theredja.src2mc.client.render.FrameTimeline;
import dev.theredja.src2mc.client.render.LogicPropRenderer;
import dev.theredja.src2mc.client.render.MapSurfaceRenderer;
import dev.theredja.src2mc.client.render.MonitorScreens;
import dev.theredja.src2mc.client.render.MoverRenderer;
import dev.theredja.src2mc.client.render.PropRenderer;
import dev.theredja.src2mc.client.render.SkyRenderer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * The one client command root, {@code /src2mcc}: look, light, drawing, culling, audio, logic overlays, the menu and
 * debugging switches of this mod's renderers. It only wires; every handler lives in the class that owns the state.
 * The server side is {@code /src2mc}.
 */
@EventBusSubscriber(modid = Src2mc.MOD_ID, value = Dist.CLIENT)
public final class ClientCommands {
    private ClientCommands() {}

    private static final String HELP = String.join("\n",
        "src2mcc: client-side switches and status of this mod",
        "  status [render|props|anim|logicprops|movers|particles|audio|sky|light|look|screen|menu|monitors] - one page of numbers, or one subsystem in full",
        "  look - Source post-processing and surface effects: on|off, <effect> on|off, grainscale, bump, measure, scale",
        "  light - baked light and prop lighting: <switch> on|off, exposure, atten, dlight, movers, movershading",
        "  draw - turn a renderer on or off: props|logicprops|anim|movers|sky|skyroom|monitors|monitorcamera|monitorexposure|particles|decals, particlerange, decals clear",
        "  cull - culling: pvs|frustum|shadow|occlusion on|off",
        "  audio - Source sound: soundscapes|ambient|surfaces|captions on|off, steps <0..5>",
        "  logic - map logic overlays: show off|triggers|usable|all [filter], screen clear",
        "  menu - campaign menu: progress, chapters <n>",
        "  debug - probes and experiments: face, region, lightprobe, rebuild, shadowdistance, gpu, overlay, lightonly, phong, monitorshot, monitorforce");

    @FunctionalInterface
    private interface Toggle {
        int apply(CommandSourceStack source, boolean value);
    }

    @SubscribeEvent
    static void register(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(literal("src2mcc")
            .executes(context -> reply(context.getSource(), HELP))
            .then(status())
            .then(look())
            .then(light())
            .then(draw())
            .then(cull())
            .then(audio())
            .then(logic())
            .then(menu())
            .then(debug()));
    }

    // ---- status

    private static LiteralArgumentBuilder<CommandSourceStack> status() {
        return literal("status")
            .executes(context -> reply(context.getSource(), summary()))
            .then(text("render", MapSurfaceRenderer::statusText))
            .then(literal("props").executes(context -> {
                for (Component line : PropRenderer.statusLines()) context.getSource().sendSuccess(() -> line, false);
                return 1;
            }))
            .then(text("anim", AnimatedPropRenderer::status))
            .then(text("logicprops", LogicPropRenderer::status))
            .then(text("movers", MoverRenderer::status))
            .then(text("particles", ParticleEffects::statusText))
            .then(text("audio", SourceAudio::statusText))
            .then(text("sky", SkyRenderer::statusText))
            .then(text("light", BakedLighting::statusText))
            .then(text("look", LookCommands::statusText))
            .then(text("screen", ScreenOverlay::status))
            .then(text("menu", MenuClientCommands::statusText))
            .then(text("monitors", dev.theredja.src2mc.client.view.MonitorCameras::status));
    }

    /** {@code name} printing {@code text}. */
    private static LiteralArgumentBuilder<CommandSourceStack> text(String name, Supplier<String> text) {
        return literal(name).executes(context -> reply(context.getSource(), text.get()));
    }

    /** The compact page: frame rate, then one line per subsystem. */
    private static String summary() {
        StringBuilder page = new StringBuilder("fps ").append(Minecraft.getInstance().getFps());
        double frameMs = FrameTimeline.enabled() ? FrameTimeline.averageFrameMs() : -1;
        if (frameMs >= 0) page.append(String.format(Locale.ROOT, " | frame %.2f ms", frameMs));
        List<String> lines = new ArrayList<>();
        lines.add(MapSurfaceRenderer.summaryLine());
        lines.add(PropRenderer.summaryLine());
        lines.add(AnimatedPropRenderer.summaryLine());
        lines.add(LogicPropRenderer.summaryLine());
        lines.add(MoverRenderer.summaryLine());
        lines.add(ParticleEffects.summaryLine());
        lines.add(SourceAudio.summaryLine());
        lines.add(SkyRenderer.summaryLine());
        lines.add(dev.theredja.src2mc.client.view.MonitorCameras.status());
        for (String line : lines) page.append('\n').append(line);
        return page.toString();
    }

    // ---- look

    private static LiteralArgumentBuilder<CommandSourceStack> look() {
        var node = literal("look")
            .then(literal("on").executes(context -> LookCommands.toggle(context.getSource(), "post", true)))
            .then(literal("off").executes(context -> LookCommands.toggle(context.getSource(), "post", false)));
        for (String name : LookCommands.LOOK_SWITCHES) node.then(onOff(name, (source, value) -> LookCommands.toggle(source, name, value)));
        return node
            .then(onOff("bump", LookCommands::bump))
            .then(literal("grainscale")
                .then(argument("scale", FloatArgumentType.floatArg(-1f, 64f))
                    .executes(context -> LookCommands.grainScale(context.getSource(), FloatArgumentType.getFloat(context, "scale")))))
            .then(literal("measure")
                .then(literal("gamma").executes(context -> LookCommands.measure(context.getSource(), false)))
                .then(literal("linear").executes(context -> LookCommands.measure(context.getSource(), true))))
            .then(literal("scale")
                .then(literal("auto").executes(context -> LookCommands.held(context.getSource(), 0)))
                .then(argument("scale", FloatArgumentType.floatArg(0.01f, 64f))
                    .executes(context -> LookCommands.held(context.getSource(), FloatArgumentType.getFloat(context, "scale")))));
    }

    // ---- light

    private static LiteralArgumentBuilder<CommandSourceStack> light() {
        var node = literal("light");
        for (String name : LookCommands.LIGHT_SWITCHES) node.then(onOff(name, (source, value) -> LookCommands.toggle(source, name, value)));
        return node
            .then(onOff("smooth", MapSurfaceRenderer::setSmoothLighting))
            .then(onOff("occlusion", MapSurfaceRenderer::setLightOcclusion))
            .then(onOff("relight", MapSurfaceRenderer::setRelight))
            .then(literal("exposure")
                .then(argument("scale", FloatArgumentType.floatArg(0.01f, 64f))
                    .executes(context -> BakedLighting.setExposure(context.getSource(), FloatArgumentType.getFloat(context, "scale")))))
            .then(literal("atten")
                .then(literal("vertex").executes(context -> LookCommands.lightAtten(context.getSource(), true)))
                .then(literal("pixel").executes(context -> LookCommands.lightAtten(context.getSource(), false))))
            .then(literal("dlight")
                .then(literal("off").executes(context -> LookCommands.dlight(context.getSource(), "off", 0)))
                .then(literal("linear").executes(context -> LookCommands.dlight(context.getSource(), "linear", 1)))
                .then(literal("quadratic").executes(context -> LookCommands.dlight(context.getSource(), "quadratic", 2))))
            .then(literal("movers")
                .then(literal("vertex").executes(context -> { MoverRenderer.setPerVertexLight(true); return reply(context.getSource(), MoverRenderer.status()); }))
                .then(literal("single").executes(context -> { MoverRenderer.setPerVertexLight(false); return reply(context.getSource(), MoverRenderer.status()); })))
            .then(literal("movershading")
                .then(literal("turned").executes(context -> { MoverRenderer.setTurnShading(true); return reply(context.getSource(), MoverRenderer.status()); }))
                .then(literal("unturned").executes(context -> { MoverRenderer.setTurnShading(false); return reply(context.getSource(), MoverRenderer.status()); })));
    }

    // ---- draw

    private static LiteralArgumentBuilder<CommandSourceStack> draw() {
        return literal("draw")
            .then(onOff("props", (source, value) -> reply(source, PropRenderer.setDrawing(value))))
            .then(onOff("logicprops", (source, value) -> { LogicPropRenderer.setEnabled(value); return reply(source, LogicPropRenderer.status()); }))
            .then(onOff("anim", (source, value) -> { AnimatedPropRenderer.setEnabled(value); return reply(source, AnimatedPropRenderer.status()); }))
            .then(onOff("movers", (source, value) -> { MoverRenderer.setEnabled(value); return reply(source, MoverRenderer.status()); }))
            .then(onOff("sky", SkyRenderer::setEnabled))
            .then(onOff("skyroom", SkyRenderer::setRoom))
            .then(onOff("monitors", (source, value) -> {
                MonitorScreens.enabled = value;
                return reply(source, dev.theredja.src2mc.client.view.MonitorCameras.status());
            }))
            .then(onOff("monitorcamera", (source, value) -> {
                dev.theredja.src2mc.client.view.MonitorCameras.enabled = value;
                return reply(source, dev.theredja.src2mc.client.view.MonitorCameras.status());
            }))
            .then(onOff("monitorexposure", (source, value) -> {
                MonitorScreens.exposure = value;
                return reply(source, dev.theredja.src2mc.client.view.MonitorCameras.status());
            }))
            .then(onOff("particles", (source, value) -> { ParticleEffects.setEnabled(value); return reply(source, ParticleEffects.summaryLine()); }))
            .then(onOff("decals", (source, value) -> { DecalRenderer.setEnabled(value); return reply(source, DecalRenderer.status()); })
                .then(literal("clear").executes(context -> { DecalRenderer.clearAll(); return reply(context.getSource(), DecalRenderer.status()); })))
            .then(literal("particlerange")
                .then(argument("blocks", DoubleArgumentType.doubleArg(16, 1024))
                    .executes(context -> {
                        ParticleEffects.setRange(DoubleArgumentType.getDouble(context, "blocks"));
                        return reply(context.getSource(), ParticleEffects.summaryLine());
                    })));
    }

    // ---- cull

    private static LiteralArgumentBuilder<CommandSourceStack> cull() {
        return literal("cull")
            .then(onOff("pvs", MapSurfaceRenderer::setPvsCulling))
            .then(onOff("frustum", MapSurfaceRenderer::setFrustumCulling))
            .then(onOff("shadow", MapSurfaceRenderer::setShadowCulling))
            .then(onOff("occlusion", (source, value) -> reply(source, PropRenderer.setOcclusion(value))));
    }

    // ---- audio

    private static LiteralArgumentBuilder<CommandSourceStack> audio() {
        return literal("audio")
            .then(onOff("soundscapes", (source, value) -> { SourceAudio.setSoundscapes(value); return reply(source, SourceAudio.statusText()); }))
            .then(onOff("ambient", (source, value) -> { SourceAudio.setAmbient(value); return reply(source, SourceAudio.statusText()); }))
            .then(onOff("surfaces", (source, value) -> { SourceAudio.setSurfaces(value); return reply(source, SourceAudio.statusText()); }))
            .then(onOff("captions", (source, value) -> { CaptionOverlay.setEnabled(value); return reply(source, SourceAudio.statusText()); }))
            .then(literal("steps")
                .then(argument("gain", FloatArgumentType.floatArg(0, 5))
                    .executes(context -> {
                        SourceAudio.setStepGain(FloatArgumentType.getFloat(context, "gain"));
                        return reply(context.getSource(), SourceAudio.statusText());
                    })));
    }

    // ---- logic

    private static LiteralArgumentBuilder<CommandSourceStack> logic() {
        var show = literal("show");
        for (String mode : LogicOverlay.modeNames()) {
            show.then(literal(mode)
                .executes(context -> LogicOverlay.show(context.getSource(), mode, ""))
                .then(argument("filter", StringArgumentType.word())
                    .executes(context -> LogicOverlay.show(context.getSource(), mode, StringArgumentType.getString(context, "filter")))));
        }
        return literal("logic")
            .then(show)
            .then(literal("screen")
                .then(literal("clear").executes(context -> { ScreenOverlay.clear(); return reply(context.getSource(), ScreenOverlay.status()); })));
    }

    // ---- menu

    private static LiteralArgumentBuilder<CommandSourceStack> menu() {
        return literal("menu")
            .then(literal("progress").executes(context -> MenuClientCommands.progress(context.getSource())))
            .then(literal("chapters")
                .then(argument("unlocked", IntegerArgumentType.integer(0, 1000))
                    .executes(context -> MenuClientCommands.chapters(context.getSource(), IntegerArgumentType.getInteger(context, "unlocked")))));
    }

    // ---- debug

    private static LiteralArgumentBuilder<CommandSourceStack> debug() {
        return literal("debug")
            .then(literal("face").executes(context -> MapSurfaceRenderer.inspectFace(context.getSource())))
            .then(literal("region").executes(context -> MapSurfaceRenderer.probeRegion(context.getSource())))
            .then(literal("lightprobe").executes(context -> MapSurfaceRenderer.probeLight(context.getSource())))
            .then(literal("rebuild").executes(context -> MapSurfaceRenderer.rebuildMeshes(context.getSource())))
            .then(literal("shadowdistance")
                .then(argument("blocks", DoubleArgumentType.doubleArg(0))
                    .executes(context -> MapSurfaceRenderer.setShadowDistance(context.getSource(), DoubleArgumentType.getDouble(context, "blocks")))))
            // Without an argument: the per-span GPU frame report, which then starts over.
            .then(onOff("gpu", FrameTimeline::setEnabled)
                .executes(context -> FrameTimeline.report(context.getSource())))
            .then(onOff("overlay", (source, value) -> reply(source, PropRenderer.setOverlay(value))))
            .then(onOff("monitorforce", (source, value) -> {
                dev.theredja.src2mc.client.view.MonitorCameras.force = value;
                return reply(source, dev.theredja.src2mc.client.view.MonitorCameras.status());
            }))
            .then(literal("monitorshot").executes(context -> reply(context.getSource(), dev.theredja.src2mc.client.view.MonitorCameras.dump())))
            .then(onOff("lightonly", (source, value) -> LookCommands.toggle(source, "lightonly", value)))
            .then(literal("phong")
                .then(literal("off").executes(context -> LookCommands.phongDebug(context.getSource(), "off", 0)))
                .then(literal("diffuse").executes(context -> LookCommands.phongDebug(context.getSource(), "diffuse", 1)))
                .then(literal("spec").executes(context -> LookCommands.phongDebug(context.getSource(), "spec", 2)))
                .then(literal("lights").executes(context -> LookCommands.phongDebug(context.getSource(), "lights", 3))));
    }

    // ---- helpers

    /** {@code name on} and {@code name off}. */
    private static LiteralArgumentBuilder<CommandSourceStack> onOff(String name, Toggle handler) {
        return literal(name)
            .then(literal("on").executes(context -> handler.apply(context.getSource(), true)))
            .then(literal("off").executes(context -> handler.apply(context.getSource(), false)));
    }

    private static int reply(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }
}
