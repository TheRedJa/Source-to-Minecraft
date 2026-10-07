package dev.theredja.src2mc.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * {@code ShaderInstance.setDefaultUniforms} writes Minecraft's own values into any uniform of
 * these names, at these sizes; a mod uniform of the same name and another size overflows its
 * buffer (DEV-0.35.0 crashed on a three-float {@code FogColor}).
 */
final class CoreShaderUniformTest {
    private static final Map<String, Integer> VANILLA = Map.ofEntries(
        Map.entry("ModelViewMat", 16), Map.entry("ProjMat", 16), Map.entry("TextureMat", 16),
        Map.entry("ScreenSize", 2), Map.entry("ColorModulator", 4), Map.entry("Light0_Direction", 3),
        Map.entry("Light1_Direction", 3), Map.entry("GlintAlpha", 1), Map.entry("FogStart", 1),
        Map.entry("FogEnd", 1), Map.entry("FogColor", 4), Map.entry("FogShape", 1), Map.entry("LineWidth", 1),
        Map.entry("GameTime", 1), Map.entry("ChunkOffset", 3));

    @Test
    void noShaderUniformTakesAVanillaNameAtAnotherSize() throws IOException {
        Path core = Path.of("src/main/resources/assets/src2mc/shaders/core");
        try (Stream<Path> files = Files.list(core)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                for (JsonElement element : root.getAsJsonArray("uniforms")) {
                    JsonObject uniform = element.getAsJsonObject();
                    String name = uniform.get("name").getAsString();
                    Integer size = VANILLA.get(name);
                    if (size != null) assertEquals(size, uniform.get("count").getAsInt(), file.getFileName() + ": " + name);
                }
            }
        }
    }
}
