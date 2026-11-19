#version 150

#moj_import <fog.glsl>

// The three shaders Source's monitor materials draw the camera target (_rt_Camera) with, both
// textures read through sRGB into linear light, the result written back through sRGB:
// MonitorShader 0, UnlitGeneric (vertexlit_and_unlit_generic_ps2x.fxc:498): camera x colour, times
//   the tone map scale;
// 1, UnlitTwoTexture (unlittwotexture_ps2x.fxc: baseColor * baseColor2 * g_DiffuseModulation, alpha 1,
//   TONEMAP_SCALE_LINEAR);
// 2, MonitorScreen (monitorscreen_ps2x.fxc): camera x vertex colour (the raw $color) x texture2, then
//   contrast, saturation against a 1/3 grey and the tint, no tone map scale.
// MonitorColor is the linear modulation for 0 and 1 and the raw colour for 2, worked out per draw.
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;

uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform vec4 SourceFog;
uniform vec3 SourceFogColor;

uniform float MonitorShader;
uniform vec3 MonitorColor;
uniform float MonitorTexture2;
// $texture2's coordinates: texCoord0 * xy + zw.
uniform vec4 MonitorTransform;
uniform vec3 MonitorTint;
uniform float MonitorContrast;
uniform float MonitorSaturation;
uniform float MonitorExposure;
uniform float MonitorSrgb;
uniform float MonitorAdditive;

in float vertexDistance;
in float viewDepth;
in vec2 texCoord0;

out vec4 fragColor;

vec3 srgbToLinear(vec3 c) { c = max(c, vec3(0.0)); return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), c)); }
vec3 linearToSrgb(vec3 c) { c = max(c, vec3(0.0)); return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(vec3(0.0031308), c)); }
vec3 toLinear(vec3 c) { return MonitorSrgb > 0.5 ? srgbToLinear(c) : pow(max(c, vec3(0.0)), vec3(2.2)); }
vec3 toGamma(vec3 c) { return MonitorSrgb > 0.5 ? linearToSrgb(c) : pow(max(c, vec3(0.0)), vec3(1.0 / 2.2)); }

// Source's range fog on view depth, capped at the maximum density, the factor squared, as the
// map's surfaces take it (SourceFog.w 1); 2: the map has no fog; 0: Minecraft's.
float source_fog(float depth) {
    float f = min(SourceFog.z, clamp((depth - SourceFog.x) / (SourceFog.y - SourceFog.x), 0.0, 1.0));
    return f * f;
}

void main() {
    // The camera target is a GL framebuffer, its first row the picture's bottom; Source's t runs from the top.
    vec3 base = toLinear(texture(Sampler0, vec2(texCoord0.x, 1.0 - texCoord0.y)).rgb);
    vec3 second = MonitorTexture2 > 0.5 ? toLinear(texture(Sampler1, texCoord0 * MonitorTransform.xy + MonitorTransform.zw).rgb) : vec3(1.0);
    vec3 result;
    if (MonitorShader > 1.5) {
        result = base * MonitorColor * second;
        result = mix(result, result * result, MonitorContrast);
        result = mix(vec3(dot(result, vec3(0.33333))), result, MonitorSaturation);
        result *= MonitorTint;
    } else {
        result = base * second * MonitorColor * MonitorExposure;
    }
    if (SourceFog.w > 0.5) {
        // An additive surface fogs to black, else to the fog colour.
        if (SourceFog.w < 1.5) result = mix(result, MonitorAdditive > 0.5 ? vec3(0.0) : SourceFogColor * MonitorExposure, source_fog(viewDepth));
        fragColor = vec4(toGamma(result), 1.0);
        return;
    }
    vec3 color = toGamma(result);
    float fog = clamp((vertexDistance - FogStart) / max(FogEnd - FogStart, 1e-4), 0.0, 1.0);
    if (MonitorAdditive > 0.5) fragColor = vec4(color * (1.0 - fog * FogColor.a), 1.0);
    else fragColor = linear_fog(vec4(color, 1.0), vertexDistance, FogStart, FogEnd, FogColor);
}
