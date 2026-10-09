#version 150

#moj_import <fog.glsl>

// Albedo times light times Source's tone map scale, in linear light, then to gamma; the map's
// fog over it, or Minecraft's outside a map.
uniform sampler2D Sampler0;
uniform sampler2D Sampler3;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform float Exposure;
// Alpha below which a texel is cut away; negative for none.
uniform float Cutout;

// Source's range fog (D30), when SourceFog.w is 1: on view depth, capped at the maximum density,
// the factor squared (BlendPixelFog); the colour in linear light times the tone map scale.
// SourceFog.w 2: the map has no fog; 0: Minecraft's.
uniform vec4 SourceFog;
uniform vec3 SourceFogColor;

// Half a step of 8-bit noise, fixed to the screen (interleaved gradient noise): breaks up the
// bands smooth fog leaves in dark scenes once the frame is stored at 8 bits.
float dither() {
    return (fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715)))) - 0.5) / 255.0;
}

float source_fog(float depth) {
    float f = min(SourceFog.z, clamp((depth - SourceFog.x) / (SourceFog.y - SourceFog.x), 0.0, 1.0));
    return f * f;
}

in float vertexDistance;
in float viewDepth;
in vec4 vertexColor;
in vec2 texCoord0;
in vec4 bakedLight;
in vec3 blockLight;

out vec4 fragColor;

void main() {
    vec4 albedo = texture(Sampler0, texCoord0);
    if (albedo.a < Cutout) {
        discard;
    }
    albedo *= vertexColor * ColorModulator;
    vec3 light = bakedLight.w < 0.5 ? texture(Sampler3, bakedLight.xy).rgb : bakedLight.rgb;
    vec3 color = pow(albedo.rgb, vec3(2.2)) * (light * Exposure + blockLight);
    if (SourceFog.w > 0.5) {
        if (SourceFog.w < 1.5) color = mix(color, SourceFogColor * Exposure, source_fog(viewDepth));
        fragColor = vec4(pow(max(color, vec3(0.0)), vec3(1.0 / 2.2)) + dither(), albedo.a);
    } else {
        fragColor = linear_fog(vec4(pow(max(color, vec3(0.0)), vec3(1.0 / 2.2)), albedo.a), vertexDistance, FogStart, FogEnd, FogColor);
    }
}
