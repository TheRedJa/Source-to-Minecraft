#version 150

// One triangle over the whole viewport; texCoord is 0..1 across it, bottom-left origin.
out vec2 texCoord;

void main() {
    vec2 corner = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    texCoord = corner;
    gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
}
