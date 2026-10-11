#version 150

#moj_import <fog.glsl>

// Source's spritecard pixel shader (SDK spritecard_ps2x.fxc): two sheet frames blended, or the
// brighter of them, times the overbright factor; the tint, read as gamma, times that, or with
// $addself the texel's own alpha-weighted colour added; then the tone map scale, in linear light,
// written back to gamma. Blending is set per draw: alpha, additive, or premultiplied for $addself.
uniform sampler2D Sampler0;

uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform float ParticleExposure;
// 1: exact sRGB curve for the texture decode and the output; 0: pow 2.2.
uniform float ParticleSrgb;
uniform float ParticleOverbright;
uniform float ParticleAddSelf;
uniform float ParticleBlendFrames;
uniform float ParticleMaxLum;
uniform float ParticleAdditive;

// Source's range fog (D30), when SourceFog.w is 1: on view depth, capped at the maximum density,
// the factor squared (BlendPixelFog); the colour in linear light times the tone map scale.
// SourceFog.w 2: the map has no fog; 0: Minecraft's.
uniform vec4 SourceFog;
uniform vec3 SourceFogColor;

float source_fog(float depth) {
    float f = min(SourceFog.z, clamp((depth - SourceFog.x) / (SourceFog.y - SourceFog.x), 0.0, 1.0));
    return f * f;
}

in float vertexDistance;
in float viewDepth;
in vec4 vertexColor;
in vec2 texCoord0;
in vec3 frame1;

out vec4 fragColor;

vec4 toLinear(vec4 c) { return vec4(pow(max(c.rgb, vec3(0.0)), vec3(2.2)), c.a); }
// Source reads textures and writes the frame through hardware sRGB: the exact piecewise curve.
// ParticleSrgb 0 falls back to pow 2.2 (/src2mc_look srgb).
vec3 srgbToLinear(vec3 c) { c = max(c, vec3(0.0)); return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), c)); }
vec3 linearToSrgb(vec3 c) { c = max(c, vec3(0.0)); return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(vec3(0.0031308), c)); }
vec4 toLinearTex(vec4 c) { return ParticleSrgb > 0.5 ? vec4(srgbToLinear(c.rgb), c.a) : toLinear(c); }
vec3 toGamma(vec3 c) { return ParticleSrgb > 0.5 ? linearToSrgb(c) : pow(max(c, vec3(0.0)), vec3(1.0 / 2.2)); }

void main() {
    vec4 base0 = toLinearTex(texture(Sampler0, texCoord0));
    vec4 base1 = toLinearTex(texture(Sampler0, frame1.xy));
    vec4 blended = ParticleBlendFrames > 0.5 ? mix(base0, base1, frame1.z) : base0;
    if (ParticleMaxLum > 0.5) {
        float lum0 = dot(vec3(0.3, 0.59, 0.11), base0.rgb * (1.0 - frame1.z));
        float lum1 = dot(vec3(0.3, 0.59, 0.11), base1.rgb * frame1.z);
        blended = lum0 > lum1 ? base0 : base1;
    }
    blended.rgb *= ParticleOverbright;
    vec4 tint = toLinear(vertexColor);
    if (ParticleAddSelf > 0.0) {
        blended.a *= tint.a;
        blended.rgb *= blended.a;
        blended.rgb += ParticleOverbright * ParticleAddSelf * tint.a * blended.rgb;
        blended.rgb *= tint.rgb;
    } else {
        blended *= tint;
        if (blended.a <= 0.01) discard;
    }
    blended.rgb *= ParticleExposure;
    if (SourceFog.w > 0.5) {
        // Additive sprites fog to black, as Source draws them with FogToBlack.
        if (SourceFog.w < 1.5) blended.rgb = mix(blended.rgb, ParticleAdditive > 0.5 ? vec3(0.0) : SourceFogColor * ParticleExposure, source_fog(viewDepth));
        fragColor = vec4(toGamma(blended.rgb), blended.a);
        return;
    }
    vec3 color = toGamma(blended.rgb);
    float fog = clamp((vertexDistance - FogStart) / max(FogEnd - FogStart, 1e-4), 0.0, 1.0);
    if (ParticleAdditive > 0.5) {
        // Fog takes an additive glow away rather than painting it the fog colour.
        fragColor = vec4(color * (1.0 - fog * FogColor.a), blended.a);
    } else {
        fragColor = linear_fog(vec4(color, blended.a), vertexDistance, FogStart, FogEnd, FogColor);
    }
}
