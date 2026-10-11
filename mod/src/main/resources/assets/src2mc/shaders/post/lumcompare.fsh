#version 150

// Source's luminance_compare_ps2x: a pixel of the frame passes when its luminance lies in the
// bucket's range; an occlusion query counts the passing pixels. Only the centre region Source
// measures (mat_exposure_center_region_x/y) is tested. Linear 0: the luminance of the stored
// gamma values, which reproduces where INFRA settles (D30); 1: decoded to linear light first, as
// dev/lumcompare's sRGB read asks for.
uniform sampler2D Frame;
// Bucket minimum and maximum luminance.
uniform vec2 Range;
// The measured region in window pixels: x0, y0, x1, y1, inclusive.
uniform vec4 Region;
uniform int Linear;
// 1: decode with the exact sRGB curve rather than pow 2.2.
uniform int Srgb;

in vec2 texCoord;
out vec4 fragColor;

// Source reads textures and writes the frame through hardware sRGB: the exact piecewise curve.
vec3 srgbToLinear(vec3 c) { c = max(c, vec3(0.0)); return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), c)); }

void main() {
    vec2 p = gl_FragCoord.xy - 0.5;
    if (p.x < Region.x || p.y < Region.y || p.x > Region.z || p.y > Region.w) discard;
    float luminance = dot(Linear == 1 ? (Srgb == 1 ? srgbToLinear(texture(Frame, texCoord).rgb) : pow(texture(Frame, texCoord).rgb, vec3(2.2))) : texture(Frame, texCoord).rgb, vec3(0.2125, 0.7154, 0.0721));
    if (luminance < Range.x || luminance > Range.y) discard;
    fragColor = vec4(0.0);
}
