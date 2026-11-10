#version 150

// The in-game ring logo (client/logo/LogoRingRenderer). Vertices come from LogoRingGeometry:
// Color.r = face (0-3 long faces, 4 = cut end), Color.g = segment, UV0 = (along, across).
// The along/across frame for the crack depth map follows from the position and the face normal:
// along the ring at this angle, and across the face (normal x along).

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;
in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec3 vNormalV;
out vec3 vViewDir;
out vec3 vTangentV;
out vec3 vBitangentV;
out vec3 vSurf;
out float vPiece;

void main() {
    vec4 mv = ModelViewMat * vec4(Position, 1.0);
    mat3 nm = mat3(ModelViewMat);
    vec3 n = normalize(Normal);
    float phi = atan(Position.y, Position.x);
    vec3 t = vec3(-sin(phi), cos(phi), 0.0);
    vNormalV = nm * n;
    vTangentV = nm * t;
    vBitangentV = nm * cross(n, t);
    vViewDir = -mv.xyz;
    vSurf = vec3(floor(Color.r * 255.0 + 0.5), UV0.x, UV0.y);
    vPiece = floor(Color.g * 255.0 + 0.5);
    gl_Position = ProjMat * mv;
}
