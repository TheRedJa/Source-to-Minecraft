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

vec3 toLinear(vec3 color) {
    return pow(max(color, vec3(0.0)), vec3(2.2));
}

vec4 lightTexel(int row, int texel) {
    return texelFetch(Sampler8, ivec2(texel, row), 0);
}

// One local light of a set at the point p (camera-relative): the unit direction to it and its colour
// with distance and cone falloff; false for an empty slot or out of range. The same terms as the
// fragment shader's localLight, here per vertex.
bool localLight(int row, int i, vec3 p, out vec3 toLight, out vec3 color) {
    int at = 6 + 5 * i;
    vec4 a = lightTexel(row, at);
    toLight = vec3(0.0, 1.0, 0.0);
    color = vec3(0.0);
    if (a.w < 0.5) return false;
    int type = int(a.w + 0.5) - 1;
    vec4 c = lightTexel(row, at + 1);
    vec4 d = lightTexel(row, at + 2);
    if (type == 3) {
        toLight = -d.xyz;
        color = c.rgb;
        return true;
    }
    vec3 delta = a.xyz + MapOffset - p;
    float dist = length(delta);
    if (dist > c.w) return false;
    toLight = delta / max(dist, 1.0e-4);
    float cosine = -dot(toLight, d.xyz);
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
    color = c.rgb * atten;
    return atten > 0.0;
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
    if (Baked.w > 1.5 && PhongToggles.z > 0.5) {
        int row = Baked.w > 2.5 ? int(Surface.z + 0.5) : LightRow;
        if (row > 0) {
            vec3 n = normalize(worldNormal);
            int count = int(lightTexel(row, 0).w + 0.5);
            for (int i = 0; i < 8; i++) {
                if (i >= count) break;
                vec3 l, c;
                if (localLight(row, i, worldOffset, l, c)) vertexLocal += c * clamp(dot(n, l), 0.0, 1.0);
            }
        }
    }
    // Minecraft's lightmap at this block light and no sky light, less what it shows in the dark:
    // only what a torch adds.
    int block = clamp(UV2.x / 16, 0, 15);
    blockLight = max(toLinear(texelFetch(Sampler2, ivec2(block, 0), 0).rgb) - toLinear(texelFetch(Sampler2, ivec2(0, 0), 0).rgb), vec3(0.0));
}
