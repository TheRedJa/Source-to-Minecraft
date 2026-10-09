#version 150

// Source's Downsample_nohdr: a quarter-size copy of the frame, each pixel the average of the 4x4
// block it covers, read as four bilinear taps each between 2x2 texels. Bloom's shape raises the
// colour to Tint.w and scales it by its luminance under Tint.rgb (r_bloomtint*). The 2013 shader
// shapes each tap (PerTap 1); the Alien Swarm branch shapes their average and keeps its
// luminance in alpha for local contrast.
uniform sampler2D Frame;
// One source texel.
uniform vec2 Texel;
uniform vec4 Tint;
uniform int PerTap;

in vec2 texCoord;
out vec4 fragColor;

vec3 shape(vec3 pixel) {
    float luminance = dot(pixel, Tint.rgb);
    return pow(max(pixel, vec3(0.0)), vec3(Tint.w)) * luminance;
}

void main() {
    // The block's lower corner in source texels.
    vec2 base = floor(gl_FragCoord.xy) * 4.0;
    vec3 s0 = texture(Frame, (base + vec2(1.0, 1.0)) * Texel).rgb;
    vec3 s1 = texture(Frame, (base + vec2(3.0, 1.0)) * Texel).rgb;
    vec3 s2 = texture(Frame, (base + vec2(1.0, 3.0)) * Texel).rgb;
    vec3 s3 = texture(Frame, (base + vec2(3.0, 3.0)) * Texel).rgb;
    if (PerTap == 1) {
        fragColor = vec4((shape(s0) + shape(s1) + shape(s2) + shape(s3)) * 0.25, 1.0);
    } else {
        vec3 average = (s0 + s1 + s2 + s3) * 0.25;
        fragColor = vec4(shape(average), dot(average, vec3(0.299, 0.587, 0.114)));
    }
}
