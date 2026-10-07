#version 150

// Source's 2D skybox, sampled where the view direction meets its box: rt, lf, bk, ft, up, dn.
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;
uniform sampler2D Sampler3;
uniform sampler2D Sampler4;
uniform sampler2D Sampler5;
// Depth before the map was drawn, after its opaque geometry, and before its translucent surfaces.
uniform sampler2D Sampler6;
uniform sampler2D Sampler7;
uniform sampler2D Sampler8;
// The 3D skybox, drawn over the 2D one, at screen size.
uniform sampler2D Sampler9;
// 0: the 2D skybox through the sky faces; 1: the 2D skybox as a background, everywhere;
// 2: the 3D skybox image through the sky faces.
uniform float Mode;

in vec3 direction;

out vec4 fragColor;

void main() {
    // The sky shows where its face is in front of Minecraft's own terrain, the map drew nothing
    // over that terrain, and nothing drawn since is in front of the face: behind everything of the
    // map, as Source draws it, and in front of the world the map stands in.
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    if (Mode != 1.0) {
        float face = gl_FragCoord.z;
        float terrain = texelFetch(Sampler6, pixel, 0).r;
        float map = texelFetch(Sampler7, pixel, 0).r;
        float later = texelFetch(Sampler8, pixel, 0).r;
        if (face > terrain || map < terrain || face > later) discard;
    }
    if (Mode == 2.0) {
        fragColor = vec4(texelFetch(Sampler9, pixel, 0).rgb, 1.0);
        return;
    }

    // Minecraft's (x, y, z) is Source's (x, z, -y).
    vec3 d = vec3(direction.x, -direction.z, direction.y);
    vec3 a = abs(d);
    // (s, t): right and up across the side as the eye sees it, each -1 to 1.
    vec2 st;
    int side;
    if (a.x >= a.y && a.x >= a.z) {
        side = d.x > 0.0 ? 0 : 1;
        st = d.x > 0.0 ? vec2(-d.y, d.z) / a.x : vec2(d.y, d.z) / a.x;
    } else if (a.y >= a.z) {
        side = d.y > 0.0 ? 2 : 3;
        st = d.y > 0.0 ? vec2(d.x, d.z) / a.y : vec2(-d.x, d.z) / a.y;
    } else {
        side = d.z > 0.0 ? 4 : 5;
        st = d.z > 0.0 ? vec2(-d.y, -d.x) / a.z : vec2(-d.y, d.x) / a.z;
    }
    vec2 uv = vec2(st.x * 0.5 + 0.5, 0.5 - st.y * 0.5);
    vec4 color;
    if (side == 0) color = textureLod(Sampler0, uv, 0.0);
    else if (side == 1) color = textureLod(Sampler1, uv, 0.0);
    else if (side == 2) color = textureLod(Sampler2, uv, 0.0);
    else if (side == 3) color = textureLod(Sampler3, uv, 0.0);
    else if (side == 4) color = textureLod(Sampler4, uv, 0.0);
    else color = textureLod(Sampler5, uv, 0.0);
    fragColor = vec4(color.rgb, 1.0);
}
