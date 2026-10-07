#version 150

// Source's lit surface in linear light: albedo times baked light times the tone map scale, then
// range fog on view depth with the factor squared (BlendPixelFog), then to gamma.
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform vec3 Tint;
uniform float VertexLit;
uniform float Cutout;
uniform float Exposure;
uniform vec3 SkyFogColor;
// Start and end in blocks of skybox space, maximum density; no fog when end is not past start.
uniform vec3 SkyFogRange;

in vec2 texCoord;
in vec2 lightCoord;
in vec3 vertexLight;
in float viewDepth;

out vec4 fragColor;

void main() {
    vec4 albedo = texture(Sampler0, texCoord);
    if (Cutout > 0.5 && albedo.a < 0.5) discard;
    // The page stores linear light times 4096 in 16 bits: up to 16.
    vec3 light = VertexLit > 0.5 ? vertexLight : texture(Sampler1, lightCoord).rgb * 16.0;
    vec3 color = pow(albedo.rgb, vec3(2.2)) * Tint * light * Exposure;
    if (SkyFogRange.y > SkyFogRange.x) {
        float fog = min(SkyFogRange.z, clamp((viewDepth - SkyFogRange.x) / (SkyFogRange.y - SkyFogRange.x), 0.0, 1.0));
        color = mix(color, SkyFogColor, fog * fog);
    }
    fragColor = vec4(pow(max(color, vec3(0.0)), vec3(1.0 / 2.2)), albedo.a);
}
