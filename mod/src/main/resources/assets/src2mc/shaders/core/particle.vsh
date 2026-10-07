#version 150

// A particle quad corner, built on the CPU as Source's spritecard vertex shader places it. Frame
// holds the second animation frame's texture coordinates and the blend between the two.
in vec3 Position;
in vec4 Color;
in vec2 UV0;
in vec4 Frame;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out float vertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;
out vec3 frame1;

void main() {
    vec4 view = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * view;
    vertexDistance = length(view.xyz);
    vertexColor = Color;
    texCoord0 = UV0;
    frame1 = Frame.xyz;
}
