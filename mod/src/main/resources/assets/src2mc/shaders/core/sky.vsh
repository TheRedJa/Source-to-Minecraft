#version 150

// A sky face, in map-local blocks; Offset moves it to the camera's frame.
in vec3 Position;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec3 Offset;

out vec3 direction;

void main() {
    vec3 relative = Position + Offset;
    gl_Position = ProjMat * ModelViewMat * vec4(relative, 1.0);
    direction = relative;
}
