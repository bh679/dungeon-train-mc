#version 150

// Surface shading for the in-game ring logo, ported from the website's logo-ring/shading.js.
//
// Two-tone, painted onto the segments themselves: faces 0/2 are pale and 1/3 dark (the 180 degree
// twist joins 0 to 2 and 1 to 3, so the bands stay unbroken); the cut ends are dark. Cracks get
// real depth from the crack depth map (LogoCracks): parallax occlusion traces the view ray into the
// height field, then the normal is bent by the height gradient and lit by a fixed light, so one
// groove wall catches light and the other falls into shade. Deeper is darker.
//
// The black edge lines (three.js's fat lines on the website) are drawn here: a face darkens within
// half a line width of its own edges, so two faces meeting at an edge make one full line.

uniform sampler2D Sampler0;
uniform float Relief;
uniform float GrooveDepth;
uniform float GrooveShadow;
uniform float LineWidth;
uniform vec4 Atlas; // atlas width px, row height px, world length per row, rows
// 0: smooth (the website's look, the menu's ring). 1: item sprite (the loading bar): flat two-tone
// faces, three light bands, four brightness bands and a crisp outline, like a Minecraft item icon;
// LogoCanvas renders it small and scales it up with hard edges.
uniform float Style;

in vec3 vNormalV;
in vec3 vViewDir;
in vec3 vTangentV;
in vec3 vBitangentV;
in vec3 vSurf;
in float vPiece;
in float vSegLen;

out vec4 fragColor;

const float HALF = 0.2;
const vec3 LIGHT = vec3(-0.45, 0.65, 0.6);

vec2 w2uv() { return vec2(1.0 / Atlas.z, 1.0 / (2.0 * HALF * Atlas.w)); }
float depthAt(vec2 uv) { return texture(Sampler0, uv).r; }

// Parallax occlusion: step along the view ray until it falls below the surface.
vec2 traceDepth(vec2 uv0, vec3 viewT) {
    const int STEPS = 24;
    vec2 W2UV = w2uv();
    vec2 stepv = viewT.xy / max(viewT.z, 0.25) * GrooveDepth / float(STEPS);
    float layer = 0.0;
    float d = depthAt(uv0);
    vec2 off = vec2(0.0);
    for (int i = 0; i < STEPS; i++) {
        if (layer >= d) break;
        off -= stepv;
        layer += 1.0 / float(STEPS);
        d = depthAt(uv0 + off * W2UV);
    }
    // Interpolate between the last two steps for a smooth wall.
    vec2 prevOff = off + stepv;
    float after = d - layer;
    float before = depthAt(uv0 + prevOff * W2UV) - (layer - 1.0 / float(STEPS));
    float t = after / (after - before + 1e-5);
    return uv0 + mix(off, prevOff, clamp(t, 0.0, 1.0)) * W2UV;
}

// Pixels from this fragment to the nearest edge of its face.
float edgePixels(float k) {
    if (k < 3.5) {
        float across = (1.0 - abs(vSurf.z)) / max(fwidth(vSurf.z), 1e-6);
        float along = min(vSurf.y, vSegLen - vSurf.y) / max(fwidth(vSurf.y), 1e-6);
        return min(across, along);
    }
    float u = vSurf.y / HALF;
    return min((1.0 - abs(u)) / max(fwidth(u), 1e-6), (1.0 - abs(vSurf.z)) / max(fwidth(vSurf.z), 1e-6));
}

void main() {
    float k = floor(vSurf.x + 0.5);
    float across = clamp(vSurf.z * 0.5 + 0.5, 0.0, 1.0);
    bool pale = k < 3.5 && mod(k, 2.0) < 0.5;
    bool item = Style > 0.5;
    vec3 col = pale ? mix(vec3(0.55, 0.51, 0.5), vec3(0.88, 0.84, 0.83), across)
                    : mix(vec3(0.06), vec3(0.2), across);
    if (item) col = pale ? vec3(0.80, 0.76, 0.75) : vec3(0.17);
    vec3 N = normalize(vNormalV);
    vec3 V = normalize(vViewDir);
    float facing = clamp(dot(N, V), 0.0, 1.0);
    float lit = mix(1.0, 0.55 + 0.6 * facing, Relief);
    if (item) lit = floor(lit * 3.0 + 0.5) / 3.0;   // three light bands
    col *= lit;

    // Cracks from this segment's depth-map rows (the cut ends have none). The end of face k's row
    // and the start of face k+1's row are the same edge.
    if (k < 3.5) {
        vec2 W2UV = w2uv();
        vec2 TEXEL = vec2(1.0 / Atlas.x, 1.0 / (Atlas.y * Atlas.w));
        float row = vPiece * 4.0 + k;
        vec2 uv0 = vec2(vSurf.y, 0.0) * W2UV + vec2(0.0, (row + across) / Atlas.w);
        vec3 T = normalize(vTangentV - N * dot(N, vTangentV));
        vec3 B = normalize(vBitangentV - N * dot(N, vBitangentV) - T * dot(T, vBitangentV));
        vec2 uv = traceDepth(uv0, vec3(dot(V, T), dot(V, B), dot(V, N)));
        float depth = depthAt(uv);

        // Height-field normal from central differences, in world units.
        vec2 e = TEXEL * 1.5;
        float dx = (depthAt(uv + vec2(e.x, 0.0)) - depthAt(uv - vec2(e.x, 0.0))) / (2.0 * e.x) * W2UV.x;
        float dy = (depthAt(uv + vec2(0.0, e.y)) - depthAt(uv - vec2(0.0, e.y))) / (2.0 * e.y) * W2UV.y;
        vec3 Nb = normalize(N + (T * dx + B * dy) * GrooveDepth);
        vec3 L = normalize(LIGHT);
        float wallLight = max(dot(Nb, L), 0.0) - max(dot(N, L), 0.0);

        if (pale) {
            col *= 1.0 - GrooveShadow * pow(depth, 0.7);
            col *= 1.0 + 1.6 * wallLight;
        } else {
            col *= 1.0 - GrooveShadow * 0.85 * pow(depth, 0.7);
            col += max(wallLight, 0.0) * 0.45 * (1.0 - 0.6 * depth); // the lit wall still shows grey
        }
    }

    col = clamp(col, 0.0, 1.0);
    if (item) {                                      // four flat bands of brightness, colour kept
        float lum = max(dot(col, vec3(0.3, 0.59, 0.11)), 1e-4);
        col *= (floor(lum * 4.0 + 0.5) / 4.0) / lum;
    }
    float halfLine = LineWidth * 0.5;
    float line = item ? step(edgePixels(k), max(0.6, halfLine))   // crisp, no anti-aliasing
                      : 1.0 - smoothstep(halfLine - 0.5, halfLine + 0.5, edgePixels(k));
    col = mix(col, vec3(0.0), line);
    fragColor = vec4(col, 1.0);
}
