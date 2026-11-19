#version 150

// A face of a material showing the camera target (_rt_Camera); UV0 is its texture coordinate,
// the face's texels over the material's size, as unlittwotexture_vs20.fxc passes it on.
in vec3 Position;
in vec2 UV0;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out float vertexDistance;
out float viewDepth;
out vec2 texCoord0;

void main() {
    vec4 view = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * view;
    vertexDistance = length(view.xyz);
    viewDepth = -view.z;
    texCoord0 = UV0;
}
