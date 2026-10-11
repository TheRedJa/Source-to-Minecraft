#version 150

// Source's lit surface in linear light: albedo times baked light times the tone map scale, then
// range fog on view depth with the factor squared (BlendPixelFog), then to gamma.
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform vec3 Tint;
uniform float VertexLit;
uniform float Cutout;
uniform float Exposure;
// 1: exact sRGB curve for the texture decode and the output; 0: pow 2.2.
uniform float SrgbCurve;
uniform vec3 SkyFogColor;
// Start and end in blocks of skybox space, maximum density; no fog when end is not past start.
uniform vec3 SkyFogRange;

in vec2 texCoord;
in vec2 lightCoord;
in vec3 vertexLight;
in float viewDepth;

out vec4 fragColor;

// Source reads textures and writes the frame through hardware sRGB: the exact piecewise curve.
// SrgbCurve 0 falls back to pow 2.2 (/src2mc_look srgb).
vec3 srgbToLinear(vec3 c) { c = max(c, vec3(0.0)); return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), c)); }
vec3 linearToSrgb(vec3 c) { c = max(c, vec3(0.0)); return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(vec3(0.0031308), c)); }
vec3 toLinearTex(vec3 c) { return SrgbCurve > 0.5 ? srgbToLinear(c) : pow(c, vec3(2.2)); }
vec3 toGamma(vec3 c) { return SrgbCurve > 0.5 ? linearToSrgb(c) : pow(max(c, vec3(0.0)), vec3(1.0 / 2.2)); }

void main() {
    vec4 albedo = texture(Sampler0, texCoord);
    if (Cutout > 0.5 && albedo.a < 0.5) discard;
    // The page stores linear light times 4096 in 16 bits: up to 16.
    vec3 light = VertexLit > 0.5 ? vertexLight : texture(Sampler1, lightCoord).rgb * 16.0;
    vec3 color = toLinearTex(albedo.rgb) * Tint * light * Exposure;
    if (SkyFogRange.y > SkyFogRange.x) {
        float fog = min(SkyFogRange.z, clamp((viewDepth - SkyFogRange.x) / (SkyFogRange.y - SkyFogRange.x), 0.0, 1.0));
        color = mix(color, SkyFogColor * Exposure, fog * fog);
    }
    fragColor = vec4(toGamma(color), albedo.a);
}
