#version 150

// A surface of the map lit as Source lit it (format section 22). Baked holds the light: page
// coordinates on the map's lightmap texture (w 0), linear light per vertex (w 1), or, w 2, the
// ambient cube in the Ambient uniforms evaluated for the vertex normal turned by NormalTurn. UV2's
// block light adds Minecraft's light sources on top; its sky light is not used.
in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;
in vec3 Normal;
in vec4 Baked;

uniform sampler2D Sampler2;

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
out vec3 blockLight;

vec3 toLinear(vec3 color) {
    return pow(max(color, vec3(0.0)), vec3(2.2));
}

void main() {
    vec4 view = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * view;
    vertexDistance = length(view.xyz);
    viewDepth = -view.z;
    vertexColor = Color;
    texCoord0 = UV0;
    if (Baked.w > 1.5) {
        // Source's AmbientLight: each axis's side by the normal's square along it.
        vec3 n = normalize(NormalTurn * Normal);
        vec3 w = n * n;
        vec3 light = w.x * (n.x < 0.0 ? AmbientNX : AmbientPX)
            + w.y * (n.y < 0.0 ? AmbientNY : AmbientPY)
            + w.z * (n.z < 0.0 ? AmbientNZ : AmbientPZ);
        bakedLight = vec4(light, 1.0);
    } else {
        bakedLight = Baked;
    }
    // Minecraft's lightmap at this block light and no sky light, less what it shows in the dark:
    // only what a torch adds.
    int block = clamp(UV2.x / 16, 0, 15);
    blockLight = max(toLinear(texelFetch(Sampler2, ivec2(block, 0), 0).rgb) - toLinear(texelFetch(Sampler2, ivec2(0, 0), 0).rgb), vec3(0.0));
}
