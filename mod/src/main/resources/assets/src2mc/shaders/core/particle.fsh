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
uniform float ParticleOverbright;
uniform float ParticleAddSelf;
uniform float ParticleBlendFrames;
uniform float ParticleMaxLum;
uniform float ParticleAdditive;

in float vertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;
in vec3 frame1;

out vec4 fragColor;

vec4 toLinear(vec4 c) { return vec4(pow(max(c.rgb, vec3(0.0)), vec3(2.2)), c.a); }

void main() {
    vec4 base0 = toLinear(texture(Sampler0, texCoord0));
    vec4 base1 = toLinear(texture(Sampler0, frame1.xy));
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
    vec3 color = pow(max(blended.rgb, vec3(0.0)), vec3(1.0 / 2.2));
    float fog = clamp((vertexDistance - FogStart) / max(FogEnd - FogStart, 1e-4), 0.0, 1.0);
    if (ParticleAdditive > 0.5) {
        // Fog takes an additive glow away rather than painting it the fog colour.
        fragColor = vec4(color * (1.0 - fog * FogColor.a), blended.a);
    } else {
        fragColor = linear_fog(vec4(color, blended.a), vertexDistance, FogStart, FogEnd, FogColor);
    }
}
