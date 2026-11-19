#version 150

// Half-Life 2's script_intro composite (materialsystem/stdshaders/IntroScreenSpaceEffect_ps2x.fxc): Scene is the
// camera entity's view, Gman the player's own. Source draws one pass per blend mode into a black target with
// SRC_ALPHA, ONE blending, each pass's colour clamped by the 8-bit target first; this sums both passes at once.

uniform sampler2D Scene;
uniform sampler2D Gman;
uniform int Mode0;
uniform int Mode1;
uniform float Alpha0;
uniform float Alpha1;

in vec2 texCoord;
out vec4 fragColor;

const float THIRD = 1.0 / 3.0;

vec3 rgbToHsv(vec3 rgb) {
    vec3 hsv;
    float fmin = min(min(rgb.r, rgb.g), rgb.b);
    float fmax = max(max(rgb.r, rgb.g), rgb.b);
    hsv.b = fmax;
    float delta = fmax - fmin;
    if (delta != 0.0) {
        hsv.g = delta / fmax;
        if (rgb.r == fmax) hsv.r = (rgb.g - rgb.b) / delta;
        else if (rgb.g == fmax) hsv.r = 2.0 + (rgb.b - rgb.r) / delta;
        else hsv.r = 4.0 + (rgb.r - rgb.g) / delta;
        hsv.r *= 60.0;
        if (hsv.r < 0.0) hsv.r += 360.0;
    } else {
        hsv.g = 0.0;
        hsv.r = -1.0;
    }
    return hsv;
}

vec3 hsvToRgb(vec3 hsv) {
    float h = hsv.r, s = hsv.g, v = hsv.b;
    if (s == 0.0) return vec3(v);
    h /= 60.0;
    float i = floor(h);
    float f = h - i;
    float p = v * (1.0 - s);
    float q = v * (1.0 - s * f);
    float t = v * (1.0 - s * (1.0 - f));
    if (h < 1.0) return vec3(v, t, p);
    if (h < 2.0) return vec3(q, v, p);
    if (h < 3.0) return vec3(p, v, t);
    if (h < 4.0) return vec3(p, q, v);
    if (h < 5.0) return vec3(t, p, v);
    return vec3(v, p, q);
}

/** The ramp of modes 3 and 4 over the player view's luminance. */
float lumBlend(float lum) {
    float a = 0.0, b = 0.4, c = 0.7, d = 1.0;
    float blend;
    if (lum < b) blend = (lum - a) / (b - a);
    else if (lum > c) blend = 1.0 - ((lum - c) / (d - c));
    else blend = 1.0;
    return clamp(blend, 0.0, 1.0);
}

vec3 modeColor(int mode, vec3 scene, vec3 gman) {
    if (mode == 0) {
        float lum = dot(vec3(THIRD), scene);
        return (vec3(1.0) - vec3(lum)) * gman;
    }
    if (mode == 1) {
        float sceneLum = dot(vec3(THIRD), scene);
        float gmanLum = dot(vec3(THIRD), gman);
        if (gmanLum < 0.3) return vec3(1.0) - gman;
        return (vec3(1.0) - gman) * vec3(sceneLum);
    }
    if (mode == 2) {
        float gmanLum = dot(vec3(THIRD), gman);
        return min(vec3(gmanLum), scene);
    }
    if (mode == 3) {
        float gmanLum = dot(vec3(THIRD), gman);
        float blend = lumBlend(gmanLum);
        return vec3(gmanLum) * (vec3(1.0) - vec3(blend)) + scene * blend;
    }
    if (mode == 4) {
        float gmanLum = dot(vec3(THIRD), gman);
        float blend = lumBlend(gmanLum);
        return gman * (vec3(1.0) - vec3(blend)) + scene * blend;
    }
    if (mode == 5) {
        if (scene.r > 0.0) return scene;
        vec3 hsv = rgbToHsv(gman);
        float blend = hsv.b - 0.5;
        hsv.b *= 1.0 + blend;
        hsv.g *= 1.0 - blend;
        return hsvToRgb(hsv);
    }
    if (mode == 6) return scene + gman;
    if (mode == 7) return scene;
    if (mode == 8) return gman;
    if (mode == 9) {
        float layer1 = clamp(dot(scene, vec3(0.333, 0.334, 0.333)), 0.0, 1.0);
        vec3 contrasty = clamp(scene * scene * 2.0, 0.0, 1.0);
        return contrasty + gman * clamp(1.0 - layer1 * 2.0, 0.0, 1.0);
    }
    return vec3(0.0);
}

void main() {
    vec3 scene = texture(Scene, texCoord).rgb;
    vec3 gman = texture(Gman, texCoord).rgb;
    vec3 result = vec3(0.0);
    if (Alpha0 != 0.0) result += clamp(modeColor(Mode0, scene, gman), 0.0, 1.0) * Alpha0;
    if (Alpha1 != 0.0) result += clamp(modeColor(Mode1, scene, gman), 0.0, 1.0) * Alpha1;
    fragColor = vec4(clamp(result, 0.0, 1.0), 1.0);
}
