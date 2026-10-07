#version 150

// A 3D skybox vertex: camera-relative skybox position, atlas coordinates, lightmap page
// coordinates, linear vertex light (format section 21).
in vec3 Position;
in vec2 UV0;
in vec2 UV1;
in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec3 Offset;

out vec2 texCoord;
out vec2 lightCoord;
out vec3 vertexLight;
out float viewDepth;

void main() {
    vec4 view = ModelViewMat * vec4(Position + Offset, 1.0);
    gl_Position = ProjMat * view;
    texCoord = UV0;
    lightCoord = UV1;
    vertexLight = Normal;
    viewDepth = -view.z;
}
