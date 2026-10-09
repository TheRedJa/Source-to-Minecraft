#version 150

// Source's engine_post: the frame plus the blurred bloom times the bloom amount, through up to
// four colour lookups weighted against the uncorrected image, then (Portal 2) darkened towards
// the edges by the vignette texture's red channel.
uniform sampler2D Frame;
uniform sampler2D Bloom;
uniform sampler3D Lookup0;
uniform sampler3D Lookup1;
uniform sampler3D Lookup2;
uniform sampler3D Lookup3;
uniform sampler2D Vignette;
uniform float BloomAmount;
uniform int Lookups;
uniform float DefaultWeight;
uniform vec4 LookupWeights;
uniform int VignetteOn;

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
    // Half a step of 8-bit noise against banding where the vignette and lookups rescale the frame.
    color += (fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715)))) - 0.5) / 255.0;
    fragColor = vec4(color, 1.0);
}
