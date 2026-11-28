#version 150

// Chest Sense's glow edge: vanilla's entity_sobel, but it looks Radius texels out instead of one,
// so the ring it leaves around each silhouette is about 2 * Radius wide before the blur.
uniform sampler2D DiffuseSampler;
uniform float Radius;

in vec2 texCoord;
in vec2 oneTexel;

out vec4 fragColor;

const int MAX_RADIUS = 4;

void main() {
    vec4 center = texture(DiffuseSampler, texCoord);
    float edge = 0.0;
    vec3 colour = center.rgb * center.a;
    float weight = center.a;
    float r = min(Radius, float(MAX_RADIUS));
    for (int x = -MAX_RADIUS; x <= MAX_RADIUS; x++) {
        for (int y = -MAX_RADIUS; y <= MAX_RADIUS; y++) {
            vec2 d = vec2(float(x), float(y));
            if (dot(d, d) > r * r + 0.5) continue;
            vec4 s = texture(DiffuseSampler, texCoord + d * oneTexel);
            edge = max(edge, abs(center.a - s.a));
            colour += s.rgb * s.a;
            weight += s.a;
        }
    }
    fragColor = vec4(weight > 0.0 ? colour / weight : vec3(0.0), edge);
}
