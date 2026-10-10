#version 150

#moj_import <fog.glsl>

// Albedo times light times Source's tone map scale, in linear light, then to gamma; the map's
// fog over it, or Minecraft's outside a map.
uniform sampler2D Sampler0;
uniform sampler2D Sampler3;
// The atlas page's bump layer: normal maps, or $ssbump's per-direction light shares; its alpha
// is the reflection's mask.
uniform sampler2D Sampler4;
// The map's cubemaps, each a strip of six faces; and per material its reflection settings.
uniform sampler2D Sampler5;
uniform sampler2D Sampler6;
// The atlas page's blend layer: a blended displacement's second texture.
uniform sampler2D Sampler7;
// The map's detail and blend modulation textures, one per layer, each repeated to fill it; bound
// by SurfaceEffects on a unit of its own, as Minecraft binds the samplers above as plain 2D ones.
uniform sampler2DArray DetailArray;
// Reflections, detail textures, displacement blending, self-illumination: 1 on, 0 off.
uniform vec4 SurfaceToggles;

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
in vec3 worldOffset;
in vec3 worldNormal;
flat in int envRow;
// The base texture's own coordinates, one per repeat, and the displacement blend.
in vec3 surface;
in vec3 blockLight;

out vec4 fragColor;

// vrad's bump basis in tangent space (g_localBumpBasis), the directions of a bump-mapped face's
// three bump lightmaps.
const vec3 BUMP_BASIS_0 = vec3(0.81649661, 0.0, 0.57735026);
const vec3 BUMP_BASIS_1 = vec3(-0.40824834, 0.70710677, 0.57735026);
const vec3 BUMP_BASIS_2 = vec3(-0.40824822, -0.70710683, 0.57735026);

// LightmappedGeneric's diffuse light: the flat lightmap, or for bakedLight.w below zero the face's
// three bump lightmaps, bakedLight.z apart across the page, weighted by the bump layer: a normal
// map by its squared clamped dot with each basis direction over their sum (w -1), a $ssbump
// texel by its channels directly (w -2).
// The material's settings, texel 0 to 7 of its row (see SurfaceEffects).
vec4 setting(int texel) {
    int m = envRow - 1;
    return texelFetch(Sampler6, ivec2((m % 128) * 8 + texel, m / 128), 0);
}

// A repeating texture of the detail array: tile.x its layer, tile.yz its share of the layer's
// sides; uv in repeats, with its screen gradients for the mip.
vec4 repeating(vec4 tile, vec2 uv, vec2 gx, vec2 gy) {
    return textureGrad(DetailArray, vec3(fract(uv * tile.yz), tile.x), gx * tile.yz, gy * tile.yz);
}

// detailWeights scales the three bump directions' light (TCOMBINE_SSBUMP_BUMP, 2 * detail).
vec3 lightmap(vec3 bump, vec3 detailWeights) {
    if (bakedLight.w > -0.5) {
        return texture(Sampler3, bakedLight.xy).rgb;
    }
    vec3 l1 = texture(Sampler3, bakedLight.xy + vec2(bakedLight.z, 0.0)).rgb;
    vec3 l2 = texture(Sampler3, bakedLight.xy + vec2(2.0 * bakedLight.z, 0.0)).rgb;
    vec3 l3 = texture(Sampler3, bakedLight.xy + vec2(3.0 * bakedLight.z, 0.0)).rgb;
    if (bakedLight.w < -1.5) {
        vec3 w = bump * detailWeights;
        return w.x * l1 + w.y * l2 + w.z * l3;
    }
    vec3 n = bump * 2.0 - 1.0;
    vec3 dp = clamp(vec3(dot(n, BUMP_BASIS_0), dot(n, BUMP_BASIS_1), dot(n, BUMP_BASIS_2)), 0.0, 1.0);
    dp *= dp * detailWeights;
    return (dp.x * l1 + dp.y * l2 + dp.z * l3) / max(dp.x + dp.y + dp.z, 1e-4);
}

// The cubemap texel a Source-axes direction lands on: Direct3D's face selection, inside the
// cubemap's strip of six faces at place.xy, place.z texels a side.
vec3 cubemap(vec4 place, vec3 d) {
    vec3 a = abs(d);
    float face, sc, tc, ma;
    if (a.x >= a.y && a.x >= a.z) {
        ma = a.x; face = d.x >= 0.0 ? 0.0 : 1.0; sc = d.x >= 0.0 ? -d.z : d.z; tc = -d.y;
    } else if (a.y >= a.z) {
        ma = a.y; face = d.y >= 0.0 ? 2.0 : 3.0; sc = d.x; tc = d.y >= 0.0 ? d.z : -d.z;
    } else {
        ma = a.z; face = d.z >= 0.0 ? 4.0 : 5.0; sc = d.z >= 0.0 ? d.x : -d.x; tc = -d.y;
    }
    float side = place.z;
    vec2 st = clamp((vec2(sc, tc) / max(ma, 1e-6) + 1.0) * 0.5 * side, vec2(0.5), vec2(side - 0.5));
    vec2 texel = place.xy + vec2(face * side, 0.0) + st;
    return texture(Sampler5, texel / vec2(textureSize(Sampler5, 0))).rgb;
}

// LightmappedGeneric's cubemap term: the reflection of the eye about the bumped normal, read
// from the material's cubemap, times its mask and tint, its contrast and saturation applied,
// and the Fresnel factor. tangentNormal is in the face's tangent space (x along the texture's
// s, y along t); tbn its axes in world space.
vec3 reflection(vec3 tangentNormal, mat3 tbn, float mask) {
    vec4 place = setting(0);
    if (place.w < 0.5 || SurfaceToggles.x < 0.5) return vec3(0.0);
    vec4 tintFresnel = setting(1);
    vec4 saturationContrast = setting(2);
    vec3 n = normalize(tbn * tangentNormal);
    vec3 eye = -worldOffset;
    vec3 r = 2.0 * dot(n, eye) * n - dot(n, n) * eye;
    float fresnel = pow(1.0 - dot(n, normalize(eye)), 5.0);
    fresnel = fresnel * (1.0 - tintFresnel.w) + tintFresnel.w;
    // Minecraft (x, y, z) is Source (x, -z, y).
    vec3 spec = cubemap(place, vec3(r.x, -r.z, r.y)) * mask * tintFresnel.rgb;
    spec = mix(spec, spec * spec, saturationContrast.w);
    spec = mix(vec3(dot(spec, vec3(0.299, 0.587, 0.114))), spec, saturationContrast.rgb);
    return spec * fresnel;
}

void main() {
    // Screen derivatives, taken before anything is discarded: the face's tangent axes and the
    // repeating textures' mips.
    vec3 dp1 = dFdx(worldOffset), dp2 = dFdy(worldOffset);
    vec2 duv1 = dFdx(texCoord0), duv2 = dFdy(texCoord0);
    vec2 dsx = dFdx(surface.xy), dsy = dFdy(surface.xy);
    vec4 albedo = texture(Sampler0, texCoord0);
    if (albedo.a < Cutout) {
        discard;
    }
    albedo *= vertexColor * ColorModulator;
    vec3 base = pow(max(albedo.rgb, vec3(0.0)), vec3(2.2));
    float baseAlpha = albedo.a;
    float alpha = albedo.a;
    bool effects = envRow > 0;
    // A self-illuminated texture's alpha is its glow, not its opacity.
    if (effects && setting(3).w > 0.5) alpha = 1.0;

    // WorldVertexTransition: the second texture by the displacement's alpha, shaped by the
    // blend modulation texture (green the centre, red the width).
    if (effects && SurfaceToggles.z > 0.5 && setting(6).w > 0.5) {
        vec4 second = texture(Sampler7, texCoord0);
        float blend = surface.z;
        vec4 modulateTile = setting(7);
        if (modulateTile.w > 0.5) {
            vec4 m = repeating(modulateTile, surface.xy, dsx, dsy);
            blend = smoothstep(clamp(m.g - m.r, 0.0, 1.0), clamp(m.g + m.r, 0.0, 1.0), blend);
        }
        base = mix(base, pow(max(second.rgb, vec3(0.0)), vec3(2.2)), blend);
        alpha = mix(alpha, second.a, blend);
    }

    // The detail texture, combined as TextureCombine does: read raw, sRGB-decoded only when
    // additive; mode 10 weights the bump lightmaps, 5 and 6 add after lighting.
    vec4 detailSettings = effects ? setting(5) : vec4(0.0, 0.0, 0.0, -1.0);
    int mode = detailSettings.w < -0.5 || SurfaceToggles.y < 0.5 ? -1 : int(detailSettings.w + 0.5);
    vec4 detail = vec4(1.0);
    float factor = detailSettings.z;
    vec3 detailWeights = vec3(1.0);
    if (mode >= 0) {
        detail = repeating(setting(4), surface.xy * detailSettings.xy, dsx * detailSettings.xy, dsy * detailSettings.xy);
        detail.rgb *= setting(6).rgb;
        if (mode == 1) detail.rgb = pow(max(detail.rgb, vec3(0.0)), vec3(2.2));
        if (mode == 0) base *= mix(vec3(1.0), 2.0 * detail.rgb, factor);
        else if (mode == 1) base += factor * detail.rgb;
        else if (mode == 2) base = mix(base, detail.rgb, factor * detail.a);
        else if (mode == 3) { base = mix(base, detail.rgb, factor); alpha = mix(alpha, detail.a, factor); }
        else if (mode == 4) { base = mix(base, detail.rgb, factor * (1.0 - alpha)); alpha = detail.a; }
        else if (mode == 7) base *= mix(vec3(1.0), vec3(2.0 * mix(detail.r, detail.a, baseAlpha)), factor);
        else if (mode == 8) { base = mix(base, base * detail.rgb, factor); alpha = mix(alpha, alpha * detail.a, factor); }
        else if (mode == 9) alpha = mix(alpha, alpha * detail.a, factor);
        else if (mode == 10) detailWeights = 2.0 * detail.rgb;
        else if (mode == 11) base *= dot(detail.rgb, vec3(2.0 / 3.0));
    }

    vec4 layer = (bakedLight.w < -0.5 || effects) ? texture(Sampler4, texCoord0) : vec4(0.5, 0.5, 1.0, 1.0);
    vec3 light = bakedLight.w < 0.5 ? lightmap(layer.xyz, detailWeights) : bakedLight.rgb;
    vec3 diffuse = base * light;
    if (effects && SurfaceToggles.w > 0.5) {
        // $selfillum: towards the tinted texture itself by the base alpha.
        vec4 selfillum = setting(3);
        if (selfillum.w > 0.5) diffuse = mix(diffuse, selfillum.rgb * base, baseAlpha);
    }
    if (mode == 5 || mode == 6) diffuse += factor * detail.rgb;
    vec3 color = diffuse * Exposure + base * blockLight;
    if (effects) {
        vec3 tangentNormal = vec3(0.0, 0.0, 1.0);
        if (bakedLight.w < -1.5) {
            tangentNormal = normalize(BUMP_BASIS_0 * layer.x + BUMP_BASIS_1 * layer.y + BUMP_BASIS_2 * layer.z);
        } else if (bakedLight.w < -0.5) {
            tangentNormal = layer.xyz * 2.0 - 1.0;
        }
        vec3 n = normalize(worldNormal);
        vec3 dp2perp = cross(dp2, n), dp1perp = cross(n, dp1);
        vec3 t = dp2perp * duv1.x + dp1perp * duv2.x;
        vec3 b = dp2perp * duv1.y + dp1perp * duv2.y;
        t = dot(t, t) > 0.0 ? normalize(t) : vec3(0.0);
        b = dot(b, b) > 0.0 ? normalize(b) : vec3(0.0);
        color += reflection(tangentNormal, mat3(t, b, n), layer.a) * Exposure;
    }
    if (SourceFog.w > 0.5) {
        if (SourceFog.w < 1.5) color = mix(color, SourceFogColor * Exposure, source_fog(viewDepth));
        fragColor = vec4(pow(max(color, vec3(0.0)), vec3(1.0 / 2.2)) + dither(), alpha);
    } else {
        fragColor = linear_fog(vec4(pow(max(color, vec3(0.0)), vec3(1.0 / 2.2)), alpha), vertexDistance, FogStart, FogEnd, FogColor);
    }
}
