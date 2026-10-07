#version 150

#moj_import <fog.glsl>

// Albedo times light times Source's tone map scale, in linear light, then to gamma; Minecraft's
// fog over it.
uniform sampler2D Sampler0;
uniform sampler2D Sampler3;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform float Exposure;
// Alpha below which a texel is cut away; negative for none.
uniform float Cutout;

in float vertexDistance;
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
    fragColor = linear_fog(vec4(pow(max(color, vec3(0.0)), vec3(1.0 / 2.2)), albedo.a), vertexDistance, FogStart, FogEnd, FogColor);
}
