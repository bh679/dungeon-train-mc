#version 150

// The in-game ring logo (client/logo/LogoRingRenderer). Vertices come from LogoRingGeometry:
// Color.r = face (0-3 long faces, 4 = cut end), Color.g = segment, UV0 = (along, across),
// UV2.x = the segment's length x SEG_LEN_SCALE (a growing loading-bar carriage is shorter),
// UV2.y = the direction "along" runs, an xy-plane angle x TANGENT_SCALE (the tube can follow any
// track). The across direction for the crack depth map is normal x along.

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
out float vSegLen;

const float SEG_LEN_SCALE = 30000.0; // LogoCanvas.SEG_LEN_SCALE
const float TANGENT_SCALE = 10000.0; // LogoCanvas.TANGENT_SCALE

void main() {
    vec4 mv = ModelViewMat * vec4(Position, 1.0);
    mat3 nm = mat3(ModelViewMat);
    vec3 n = normalize(Normal);
    float along = float(UV2.y) / TANGENT_SCALE;
    vec3 t = vec3(cos(along), sin(along), 0.0);
    vNormalV = nm * n;
    vTangentV = nm * t;
    vBitangentV = nm * cross(n, t);
    vViewDir = -mv.xyz;
    vSurf = vec3(floor(Color.r * 255.0 + 0.5), UV0.x, UV0.y);
    vPiece = floor(Color.g * 255.0 + 0.5);
    vSegLen = float(UV2.x) / SEG_LEN_SCALE;
    gl_Position = ProjMat * mv;
}
