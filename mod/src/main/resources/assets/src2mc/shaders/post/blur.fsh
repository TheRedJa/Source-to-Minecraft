#version 150

// Source's BlurFilter along one axis. Kernel 0 is the original cross of thirteen taps; 1 to 4 the
// Alien Swarm branch's Gaussians ($kernel). Step is one tap unit along the axis, in UV.
uniform sampler2D Source;
uniform vec2 Step;
uniform int Kernel;

in vec2 texCoord;
out vec4 fragColor;

vec4 tap(float offset) {
    return clamp(texture(Source, texCoord + Step * offset), 0.0, 1.0);
}

void main() {
    vec4 color = vec4(0.0);
    if (Kernel == 0) {
        color = tap(0.0) * 0.2013;
        color += (tap(1.3366) + tap(-1.3366)) * 0.2185;
        color += (tap(3.4295) + tap(-3.4295)) * 0.0821;
        color += (tap(5.4264) + tap(-5.4264)) * 0.0461;
        color += (tap(7.4359) + tap(-7.4359)) * 0.0262;
        color += (tap(9.4436) + tap(-9.4436)) * 0.0162;
        color += (tap(11.4401) + tap(-11.4401)) * 0.0102;
    } else if (Kernel == 1) {
        float k[5] = float[](0.004433, 0.296042, 0.399050, 0.296042, 0.004433);
        float o[5] = float[](-3.000000, -1.182425, 0.000000, 1.182425, 3.000000);
        for (int j = 0; j < 5; j++) color += k[j] * texture(Source, texCoord + Step * o[j]);
    } else if (Kernel == 2) {
        float k[5] = float[](0.019827, 0.320561, 0.319224, 0.320561, 0.019827);
        float o[5] = float[](-3.096215, -1.276878, 0.000000, 1.276878, 3.096215);
        for (int j = 0; j < 5; j++) color += k[j] * texture(Source, texCoord + Step * o[j]);
    } else if (Kernel == 3) {
        float k[7] = float[](0.004487, 0.069185, 0.312325, 0.228005, 0.312325, 0.069185, 0.004487);
        float o[7] = float[](-5.142349, -3.241796, -1.379942, 0.000000, 1.379942, 3.241796, 5.142349);
        for (int j = 0; j < 7; j++) color += k[j] * texture(Source, texCoord + Step * o[j]);
    } else {
        float k[13] = float[](0.000534, 0.003733, 0.018004, 0.059928, 0.137740, 0.218677, 0.122765, 0.218677, 0.137740, 0.059928, 0.018004, 0.003733, 0.000534);
        float o[13] = float[](-11.251852, -9.289172, -7.329586, -5.372686, -3.417910, -1.464557, 0.000000, 1.464557, 3.417910, 5.372686, 7.329586, 9.289172, 11.251852);
        for (int j = 0; j < 13; j++) color += k[j] * texture(Source, texCoord + Step * o[j]);
    }
    fragColor = color;
}
