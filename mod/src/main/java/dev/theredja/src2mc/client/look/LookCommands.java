package dev.theredja.src2mc.client.look;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.theredja.src2mc.Src2mc;
import dev.theredja.src2mc.client.render.IrisCompat;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * {@code /src2mc_look}: switches for each part of Source's post-processing, a held tone map
 * scale, and what the camera's map makes of its view this frame.
 */
@EventBusSubscriber(modid = Src2mc.MOD_ID, value = Dist.CLIENT)
public final class LookCommands {
    private LookCommands() {}

    @SubscribeEvent
    static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) { LookClient.clear(); }

    @SubscribeEvent
    static void registerCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(onOff(literal("src2mc_look"), value -> SourcePost.enabled = value, "post-processing")
            .then(onOff(literal("bloom"), value -> SourcePost.bloomOn = value, "bloom"))
            .then(onOff(literal("exposure"), value -> SourcePost.exposureOn = value, "auto exposure"))
            .then(onOff(literal("correction"), value -> SourcePost.correctionOn = value, "colour correction"))
            .then(onOff(literal("vignette"), value -> SourcePost.vignetteOn = value, "vignette"))
            .then(onOff(literal("fog"), value -> LookClient.fogOn = value, "Source fog"))
            .then(onOff(literal("reflections"), value -> dev.theredja.src2mc.client.render.SurfaceEffects.enabled = value, "cubemap reflections"))
            .then(onOff(literal("detail"), value -> dev.theredja.src2mc.client.render.SurfaceEffects.detailOn = value, "detail textures"))
            .then(onOff(literal("blend"), value -> dev.theredja.src2mc.client.render.SurfaceEffects.blendOn = value, "displacement blending"))
            .then(onOff(literal("selfillum"), value -> dev.theredja.src2mc.client.render.SurfaceEffects.selfillumOn = value, "self-illumination"))
            .then(literal("bump")
                .then(literal("on").executes(context -> bump(context.getSource(), true)))
                .then(literal("off").executes(context -> bump(context.getSource(), false))))
            .then(literal("measure")
                .then(literal("gamma").executes(context -> { SourcePost.measureLinear = false; return reply(context.getSource(), "src2mc exposure measures the stored gamma values"); }))
                .then(literal("linear").executes(context -> { SourcePost.measureLinear = true; return reply(context.getSource(), "src2mc exposure measures linear light"); })))
            .then(literal("scale")
                .then(literal("auto").executes(context -> held(context.getSource(), 0)))
                .then(argument("scale", FloatArgumentType.floatArg(0.01f, 64f))
                    .executes(context -> held(context.getSource(), FloatArgumentType.getFloat(context, "scale")))))
            .then(literal("status").executes(context -> status(context.getSource()))));
    }

    /** {@code on} and {@code off} under {@code node}. */
    private static LiteralArgumentBuilder<CommandSourceStack> onOff(LiteralArgumentBuilder<CommandSourceStack> node, Consumer<Boolean> set, String what) {
        return node
            .then(literal("on").executes(context -> { set.accept(true); return reply(context.getSource(), "src2mc " + what + " on"); }))
            .then(literal("off").executes(context -> { set.accept(false); return reply(context.getSource(), "src2mc " + what + " off"); }));
    }

    /** Bump-mapped faces through their bump lightmaps, or flat; surface meshes rebuild to show it. */
    private static int bump(CommandSourceStack source, boolean value) {
        dev.theredja.src2mc.client.render.BakedLighting.bumpEnabled = value;
        reply(source, "src2mc bump mapping " + (value ? "on" : "off") + " (moving parts keep theirs until they rebuild)");
        return dev.theredja.src2mc.client.render.MapSurfaceRenderer.rebuildMeshes(source);
    }

    private static int held(CommandSourceStack source, float value) {
        SourcePost.force(value);
        return reply(source, value > 0 ? "src2mc tone map scale held at " + value : "src2mc tone map scale back to auto exposure");
    }

    private static int status(CommandSourceStack source) {
        LookClient.Frame frame = LookClient.frame();
        StringBuilder text = new StringBuilder("src2mc look: post ").append(SourcePost.enabled ? "on" : "off")
            .append(", bloom ").append(SourcePost.bloomOn ? "on" : "off")
            .append(", exposure ").append(SourcePost.exposureOn ? "on" : "off")
            .append(", correction ").append(SourcePost.correctionOn ? "on" : "off")
            .append(", vignette ").append(SourcePost.vignetteOn ? "on" : "off")
            .append(", fog ").append(LookClient.fogOn ? "on" : "off")
            .append(", bump ").append(dev.theredja.src2mc.client.render.BakedLighting.bumpEnabled ? "on" : "off")
            .append(", reflections ").append(dev.theredja.src2mc.client.render.SurfaceEffects.enabled ? "on" : "off")
            .append(", detail ").append(dev.theredja.src2mc.client.render.SurfaceEffects.detailOn ? "on" : "off")
            .append(", blend ").append(dev.theredja.src2mc.client.render.SurfaceEffects.blendOn ? "on" : "off")
            .append(", selfillum ").append(dev.theredja.src2mc.client.render.SurfaceEffects.selfillumOn ? "on" : "off")
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
            text.append(String.format(Locale.ROOT, "\n%d lookup(s) of %d in the map, uncorrected share %.2f",
                frame.lookups().size(), look == null ? 0 : look.lookups().size(), frame.defaultWeight()));
            for (LookClient.Lookup lookup : frame.lookups()) text.append(String.format(Locale.ROOT, " %.2f", lookup.weight()));
            if (look != null && look.vignette() != null) text.append(", vignette ").append(look.vignette().width()).append('x').append(look.vignette().height());
        }
        return reply(source, text.toString());
    }

    private static int reply(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }
}
