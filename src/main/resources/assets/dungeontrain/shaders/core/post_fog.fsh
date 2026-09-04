#version 150

// Dungeon Train post-composite fog + lift. Drawn over the finished frame (after a shader pack's
// own composite) with premultiplied-alpha blending: ONE, ONE_MINUS_SRC_ALPHA.
//
// Sampler0 is a copy of the scene depth taken at the end of the gbuffer phase. Depth >= 1.0 is
// the sky (or a Distant Horizons LOD, whose depth is not from this projection) and is left alone.

uniform sampler2D Sampler0;
uniform mat4 InvProj;
uniform vec4 FogColor;
uniform float FogStart;
uniform float FogEnd;
uniform float Lift;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    float depth = texture(Sampler0, texCoord).r;
    if (depth >= 1.0) {
        discard;
    }
    vec4 view = InvProj * vec4(texCoord * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    float dist = length(view.xyz / view.w);
    // Vanilla's linear fog shape, in view distance.
    float fog = clamp((dist - FogStart) / max(FogEnd - FogStart, 0.001), 0.0, 1.0);
    float lift = clamp(Lift, 0.0, 1.0);

    // mix(mix(scene, fog, f), white, lift) folded into one premultiplied source.
    vec3 rgb = FogColor.rgb * fog * (1.0 - lift) + vec3(lift);
    float alpha = 1.0 - (1.0 - fog) * (1.0 - lift);
    if (alpha <= 0.0) {
        discard;
    }
    fragColor = vec4(rgb, alpha);
}
