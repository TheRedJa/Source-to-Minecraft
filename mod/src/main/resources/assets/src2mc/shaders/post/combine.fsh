#version 150

// Source's engine_post: the frame plus the blurred bloom times the bloom amount, through up to
// four colour lookups weighted against the uncorrected image, then (Portal 2) darkened towards
// the edges by the vignette texture's red channel, then (INFRA) filmed with Engine_Post's grain.
uniform sampler2D Frame;
uniform sampler2D Bloom;
uniform sampler3D Lookup0;
uniform sampler3D Lookup1;
uniform sampler3D Lookup2;
uniform sampler3D Lookup3;
uniform sampler2D Vignette;
uniform sampler2D Noise;
uniform float BloomAmount;
uniform int Lookups;
uniform float DefaultWeight;
uniform vec4 LookupWeights;
uniform int VignetteOn;
uniform int GrainOn;
uniform float GrainScale;
uniform float Time;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec3 color = texture(Frame, texCoord).rgb;
    color += BloomAmount * texture(Bloom, texCoord).rgb;
    if (Lookups > 0) {
        vec3 at = color * (31.0 / 32.0) + 0.5 / 32.0;
        vec3 corrected = color * DefaultWeight;
        corrected += texture(Lookup0, at).rgb * LookupWeights.x;
        if (Lookups > 1) corrected += texture(Lookup1, at).rgb * LookupWeights.y;
        if (Lookups > 2) corrected += texture(Lookup2, at).rgb * LookupWeights.z;
        if (Lookups > 3) corrected += texture(Lookup3, at).rgb * LookupWeights.w;
        color = corrected;
    }
    if (VignetteOn == 1) {
        // Source's texture rows run top to bottom.
        float vignette = texture(Vignette, vec2(texCoord.x, 1.0 - texCoord.y)).r;
        color *= clamp(vignette * 0.55 + 0.46, 0.0, 1.0);
    }
    if (GrainOn == 1) {
        // Engine_Post_ps2x.fxc: two scrolling lookups pick the texel the third reads, in display
        // gamma after the vignette (the texture is R8 holding the VTF's green channel, so .r); darker pixels get more of it. The texture repeats.
        vec2 uv = vec2(texCoord.x, 1.0 - texCoord.y);
        float a = texture(Noise, uv * 10.0 + Time).r;
        float b = texture(Noise, uv.yx * 20.0 - Time).r;
        float n = texture(Noise, clamp(vec2(a, b), 0.0, 1.0)).r;
        float lum = clamp(dot(color, vec3(0.2125, 0.7154, 0.0721)), 0.0, 1.0);
        float k = 0.2 + 0.8 * clamp(pow(1.0 - lum, 12.0), 0.0, 1.0);
        color += (n * 0.3 - 0.15) * GrainScale * k;
    }
    // Half a step of 8-bit noise against banding where the vignette and lookups rescale the frame.
    color += (fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715)))) - 0.5) / 255.0;
    fragColor = vec4(color, 1.0);
}
