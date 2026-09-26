#version 150

// Dungeon Train void wall. Drawn over the finished frame while a wall is in view: every pixel whose
// surface lies past a wall is blended toward the sky that was behind it — fully past a standing wall
// (so whatever chunk culling let through is still hidden, to the pixel), and by the fade strength past
// a fading one, so the far side comes into view out of the real skybox rather than a flat colour.
//
// Sampler0 — the colour buffer right after the sky was drawn (the sky the wall reveals)
// Sampler1 — the vanilla scene depth, copied at the end of the frame
// Sampler2 — Distant Horizons' LOD depth (only read when HasDh is 1)
//
// A surface is found in vanilla depth first, then DH's; depth at "nothing drawn" is the sky and is left
// alone. Positions come back to camera-relative world space through InvProj / InvView.

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;

uniform mat4 InvProj;
uniform mat4 DhInvProj;
uniform mat4 InvView;
uniform int HasDh;
uniform int DhReverseZ;
uniform int DhZeroToOne;

// Camera-relative X of the standing wall and of the fading one (a huge value when there is none), the
// fading wall's strength, and the track corridor spared from both (min Y, max Y, min Z, max Z).
uniform float CullX;
uniform float VeilX;
uniform float Strength;
// The same for the walls behind: hide what lies before them (a hugely negative value when there is none).
uniform float BackCullX;
uniform float BackVeilX;
uniform float BackStrength;
uniform vec4 Corridor;
// Camera-relative height at and above which nothing is faded — the cloud layer, which is never culled.
uniform float MaxY;

in vec2 texCoord;

out vec4 fragColor;

vec3 toWorld(mat4 invProj, float ndcZ) {
    vec4 view = invProj * vec4(texCoord * 2.0 - 1.0, ndcZ, 1.0);
    return (InvView * vec4(view.xyz / view.w, 1.0)).xyz;
}

void main() {
    vec3 world;
    float depth = texture(Sampler1, texCoord).r;
    if (depth < 1.0) {
        world = toWorld(InvProj, depth * 2.0 - 1.0);
    } else if (HasDh == 1) {
        // DH clears to its far value (0 under reverse-Z, 1 otherwise); its projection is built for the
        // same convention, so the raw depth goes straight into its inverse.
        float d = texture(Sampler2, texCoord).r;
        bool drawn = DhReverseZ == 1 ? d > 0.0 : d < 1.0;
        if (!drawn) discard;
        world = toWorld(DhInvProj, DhZeroToOne == 1 ? d : d * 2.0 - 1.0);
    } else {
        discard;
    }

    if (world.y >= MaxY) discard;
    float alpha = 0.0;
    if (world.x > CullX || world.x < BackCullX) alpha = 1.0;
    else if (world.x > VeilX) alpha = clamp(Strength, 0.0, 1.0);
    else if (world.x < BackVeilX) alpha = clamp(BackStrength, 0.0, 1.0);
    if (alpha <= 0.0) discard;
    if (world.y >= Corridor.x && world.y <= Corridor.y && world.z >= Corridor.z && world.z <= Corridor.w) {
        discard;
    }
    fragColor = vec4(texture(Sampler0, texCoord).rgb, alpha);
}
