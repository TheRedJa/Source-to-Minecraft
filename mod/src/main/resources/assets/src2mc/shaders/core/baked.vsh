#version 150

// A surface of the map lit as Source lit it (format section 22). Baked holds the light: page
// coordinates on the map's lightmap texture (w 0), linear light per vertex (w 1), or, w 2, the
// ambient cube in the Ambient uniforms evaluated for the vertex normal turned by NormalTurn (passed
// on as w 2), or, w 3, a cube-lit prop's light in rgb whose light set Surface.z names. UV2's
// block light adds Minecraft's light sources on top; its sky light is not used, and a surface
// that reflects a cubemap holds its material row there instead, negated.
in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;
in vec3 Normal;
in vec4 Baked;
in vec3 Surface;

uniform sampler2D Sampler2;
uniform sampler2D Sampler8;
uniform vec4 PhongToggles;
uniform vec3 MapOffset;
uniform int LightRow;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat3 NormalTurn;
uniform vec3 AmbientPX;
uniform vec3 AmbientNX;
uniform vec3 AmbientPY;
uniform vec3 AmbientNY;
uniform vec3 AmbientPZ;
uniform vec3 AmbientNZ;

out float vertexDistance;
out float viewDepth;
out vec4 vertexColor;
out vec2 texCoord0;
out vec4 bakedLight;
// The vertex relative to the camera and its normal, in world axes; the reflecting material's row
// in the reflection settings, 0 for none.
out vec3 worldOffset;
out vec3 worldNormal;
flat out int envRow;
out vec3 surface;
out vec3 blockLight;
// What the local world lights of a cube-lit prop's light set add at this vertex, Lambert on the
// vertex normal, as Source's vertex shader lights a non-bumped VertexLitGeneric; zero for none.
out vec3 vertexLocal;
// Per-vertex attenuation of the row's lights, slots 0-3 and 4-7, for a phong prop's pixels.
out vec4 lightAtten0;
out vec4 lightAtten1;

vec3 toLinear(vec3 color) {
    return pow(max(color, vec3(0.0)), vec3(2.2));
}

vec4 lightTexel(int row, int texel) {
    return texelFetch(Sampler8, ivec2(texel, row), 0);
}

// What one local light of a set is worth at the point p (camera-relative) before the cosine term:
// distance falloff, spot cone, radius cutoff, quake falloff; 1 for a directional light, 0 for an empty
// slot or out of range. Source's GetVertexAttenForLight; the one function behind both the per-vertex
// lights of a non-phong prop and the attenuation varyings a phong prop's pixels interpolate.
float lightAtten(int row, int i, vec3 p) {
    int at = 6 + 5 * i;
    vec4 a = lightTexel(row, at);
    if (a.w < 0.5) return 0.0;
    int type = int(a.w + 0.5) - 1;
    if (type == 3) return 1.0;
    vec4 c = lightTexel(row, at + 1);
    vec4 d = lightTexel(row, at + 2);
    vec3 delta = a.xyz + MapOffset - p;
    float dist = length(delta);
    if (dist > c.w) return 0.0;
    float cosine = -dot(delta / max(dist, 1.0e-4), d.xyz);
    float atten;
    if (type == 0) {
        atten = max(cosine, 0.0) / max(1.0, 1024.0 * dist * dist);
    } else if (type == 4) {
        atten = max(0.0, 1.0 - dist * 1024.0 / max(lightTexel(row, at + 3).y, 1.0e-4));
    } else {
        vec4 e = lightTexel(row, at + 3);
        atten = 1.0 / max(e.x + dist * (e.y + dist * e.z), 1.0e-4);
        if (type == 2) {
            float cone = clamp(cosine * e.w + lightTexel(row, at + 4).x, 0.0, 1.0);
            atten *= cone > 0.0 ? pow(cone, d.w) : 0.0;
        }
    }
    return atten;
}

// Unit direction to light i at p (directional: against its direction).
vec3 lightDirection(int row, int i, vec3 p) {
    int at = 6 + 5 * i;
    vec4 a = lightTexel(row, at);
    if (int(a.w + 0.5) - 1 == 3) return -lightTexel(row, at + 2).xyz;
    vec3 delta = a.xyz + MapOffset - p;
    return delta / max(length(delta), 1.0e-4);
}

void main() {
    vec4 view = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * view;
    vertexDistance = length(view.xyz);
    viewDepth = -view.z;
    worldOffset = transpose(mat3(ModelViewMat)) * view.xyz;
    worldNormal = NormalTurn * Normal;
    envRow = UV2.y < 0 ? -UV2.y : 0;
    surface = Surface;
    vertexColor = Color;
    texCoord0 = UV0;
    if (Baked.w > 1.5 && Baked.w < 2.5) {
        // Source's AmbientLight: each axis's side by the normal's square along it.
        vec3 n = normalize(NormalTurn * Normal);
        vec3 w = n * n;
        vec3 light = w.x * (n.x < 0.0 ? AmbientNX : AmbientPX)
            + w.y * (n.y < 0.0 ? AmbientNY : AmbientPY)
            + w.z * (n.z < 0.0 ? AmbientNZ : AmbientPZ);
        bakedLight = vec4(light, 2.0);
    } else {
        bakedLight = Baked;
    }
    vertexLocal = vec3(0.0);
    lightAtten0 = vec4(0.0);
    lightAtten1 = vec4(0.0);
    {
        // The set a prop's light is named by, as the fragment shader picks it (a phong material
        // on a vertex-lit surface included).
        int row = Baked.w > 2.5 ? int(Surface.z + 0.5) : Baked.w > 1.5 ? LightRow : Baked.w > 0.5 ? int(Surface.z + 0.5) : 0;
        if (row > 0) {
            vec3 n = normalize(worldNormal);
            int count = int(lightTexel(row, 0).w + 0.5);
            bool perVertex = PhongToggles.w > 0.5;
            bool cubeLit = Baked.w > 1.5 && PhongToggles.z > 0.5;
            for (int i = 0; i < 8; i++) {
                if (i >= count) break;
                float atten = lightAtten(row, i, worldOffset);
                if (perVertex) {
                    if (i < 4) lightAtten0[i] = atten; else lightAtten1[i - 4] = atten;
                }
                if (cubeLit && atten > 0.0) {
                    vec3 c = lightTexel(row, 7 + 5 * i).rgb * atten;
                    vertexLocal += c * clamp(dot(n, lightDirection(row, i, worldOffset)), 0.0, 1.0);
                }
            }
        }
    }
    // Minecraft's lightmap at this block light and no sky light, less what it shows in the dark:
    // only what a torch adds.
    int block = clamp(UV2.x / 16, 0, 15);
    blockLight = max(toLinear(texelFetch(Sampler2, ivec2(block, 0), 0).rgb) - toLinear(texelFetch(Sampler2, ivec2(0, 0), 0).rgb), vec3(0.0));
}
