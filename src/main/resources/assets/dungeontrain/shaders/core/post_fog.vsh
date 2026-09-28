#version 150

// Full-screen pass: the quad arrives already in clip space (-1..1), so no matrices.
in vec3 Position;

out vec2 texCoord;

void main() {
    gl_Position = vec4(Position.xy, 0.0, 1.0);
    texCoord = Position.xy * 0.5 + 0.5;
}
