/*
 * Adapted from Moderner Beta (https://github.com/Nostalgica-Reverie/moderner-beta),
 * util/noise/PerlinNoise.java. Copyright (c) 2021 B3spectacled. MIT License — see THIRD_PARTY_NOTICES.md.
 * Trimmed to the Alpha/Beta samplers and ported to plain Java (no Minecraft types).
 */
package games.brennan.dungeontrain.worldgen.legacy.noise;

import java.util.Random;

/**
 * One octave of the Alpha/Beta improved-Perlin noise. Construction consumes the {@link Random} exactly
 * as the original did (three origin doubles, then the 256-entry shuffle), so octave stacks built from the
 * same seeded {@code Random} reproduce the old terrain.
 */
public final class PerlinNoise {

    private final int[] permutations = new int[512];
    private final double offsetX;
    private final double offsetY;
    private final double offsetZ;

    public PerlinNoise(Random random) {
        this.offsetX = random.nextDouble() * 256.0D;
        this.offsetY = random.nextDouble() * 256.0D;
        this.offsetZ = random.nextDouble() * 256.0D;
        for (int i = 0; i < 256; i++) {
            permutations[i] = i;
        }
        for (int i = 0; i < 256; i++) {
            int j = random.nextInt(256 - i) + i;
            int k = permutations[i];
            permutations[i] = permutations[j];
            permutations[j] = k;
            permutations[i + 256] = permutations[i];
        }
    }

    /** Scalar 3-D sample (used with {@code z = 0} as the old 2-D sampler). */
    public double sample(double x, double y, double z) {
        x += offsetX;
        y += offsetY;
        z += offsetZ;
        int floorX = LegacyMath.floor(x);
        int floorY = LegacyMath.floor(y);
        int floorZ = LegacyMath.floor(z);
        int cx = floorX & 0xFF;
        int cy = floorY & 0xFF;
        int cz = floorZ & 0xFF;
        x -= floorX;
        y -= floorY;
        z -= floorZ;
        double u = fade(x);
        double v = fade(y);
        double w = fade(z);
        int a = permutations[cx] + cy;
        int aa = permutations[a] + cz;
        int ab = permutations[a + 1] + cz;
        int b = permutations[cx + 1] + cy;
        int ba = permutations[b] + cz;
        int bb = permutations[b + 1] + cz;
        return lerp(w,
                lerp(v,
                        lerp(u, grad(permutations[aa], x, y, z), grad(permutations[ba], x - 1.0D, y, z)),
                        lerp(u, grad(permutations[ab], x, y - 1.0D, z), grad(permutations[bb], x - 1.0D, y - 1.0D, z))),
                lerp(v,
                        lerp(u, grad(permutations[aa + 1], x, y, z - 1.0D), grad(permutations[ba + 1], x - 1.0D, y, z - 1.0D)),
                        lerp(u, grad(permutations[ab + 1], x, y - 1.0D, z - 1.0D), grad(permutations[bb + 1], x - 1.0D, y - 1.0D, z - 1.0D))));
    }

    /**
     * Accumulate a {@code sizeX × sizeY × sizeZ} grid into {@code arr} (index {@code (x·sizeZ + z)·sizeY + y}),
     * scaled by {@code 1/amplitude}. A {@code sizeY == 1} grid takes the old 2-D path (Y ignored); otherwise
     * the 3-D path reproduces the original's cached-gradient quirk (gradients are only recomputed when the
     * Y lattice cell changes), which shapes the Beta overhangs.
     */
    public void sampleGrid(double[] arr, double x, double y, double z, int sizeX, int sizeY, int sizeZ,
                           double scaleX, double scaleY, double scaleZ, double amplitude) {
        if (sizeY == 1) {
            int ndx = 0;
            for (int sX = 0; sX < sizeX; sX++) {
                for (int sZ = 0; sZ < sizeZ; sZ++) {
                    arr[ndx++] += sampleXZ((x + sX) * scaleX, (z + sZ) * scaleZ, amplitude);
                }
            }
            return;
        }
        sampleGrid3D(arr, x, y, z, sizeX, sizeY, sizeZ, scaleX, scaleY, scaleZ, amplitude);
    }

    /**
     * The 3-D grid path on its own, whatever {@code sizeY} is. Alpha's array sampler always took this path
     * (Beta later special-cased {@code sizeY == 1} to the 2-D form), so Alpha's flat 2-D noises — scale,
     * depth, beaches — go through here too.
     */
    public void sampleGrid3D(double[] arr, double x, double y, double z, int sizeX, int sizeY, int sizeZ,
                             double scaleX, double scaleY, double scaleZ, double amplitude) {
        double inv = 1.0D / amplitude;
        int ndx = 0;
        int flagY = -1;
        double lerp0 = 0.0D;
        double lerp1 = 0.0D;
        double lerp2 = 0.0D;
        double lerp3 = 0.0D;
        for (int sX = 0; sX < sizeX; sX++) {
            double curX = (x + sX) * scaleX + offsetX;
            int floorX = LegacyMath.floor(curX);
            int cx = floorX & 0xFF;
            curX -= floorX;
            double u = fade(curX);
            for (int sZ = 0; sZ < sizeZ; sZ++) {
                double curZ = (z + sZ) * scaleZ + offsetZ;
                int floorZ = LegacyMath.floor(curZ);
                int cz = floorZ & 0xFF;
                curZ -= floorZ;
                double w = fade(curZ);
                for (int sY = 0; sY < sizeY; sY++) {
                    double curY = (y + sY) * scaleY + offsetY;
                    int floorY = LegacyMath.floor(curY);
                    int cy = floorY & 0xFF;
                    curY -= floorY;
                    double v = fade(curY);
                    if (sY == 0 || cy != flagY) {
                        flagY = cy;
                        int a = permutations[cx] + cy;
                        int aa = permutations[a] + cz;
                        int ab = permutations[a + 1] + cz;
                        int b = permutations[cx + 1] + cy;
                        int ba = permutations[b] + cz;
                        int bb = permutations[b + 1] + cz;
                        lerp0 = lerp(u, grad(permutations[aa], curX, curY, curZ), grad(permutations[ba], curX - 1.0D, curY, curZ));
                        lerp1 = lerp(u, grad(permutations[ab], curX, curY - 1.0D, curZ), grad(permutations[bb], curX - 1.0D, curY - 1.0D, curZ));
                        lerp2 = lerp(u, grad(permutations[aa + 1], curX, curY, curZ - 1.0D), grad(permutations[ba + 1], curX - 1.0D, curY, curZ - 1.0D));
                        lerp3 = lerp(u, grad(permutations[ab + 1], curX, curY - 1.0D, curZ - 1.0D), grad(permutations[bb + 1], curX - 1.0D, curY - 1.0D, curZ - 1.0D));
                    }
                    arr[ndx++] += lerp(w, lerp(v, lerp0, lerp1), lerp(v, lerp2, lerp3)) * inv;
                }
            }
        }
    }

    /** The old 2-D sampler (Y fixed at the lattice origin), scaled by {@code 1/amplitude}. */
    public double sampleXZ(double x, double z, double amplitude) {
        x += offsetX;
        z += offsetZ;
        int floorX = LegacyMath.floor(x);
        int floorZ = LegacyMath.floor(z);
        int cx = floorX & 0xFF;
        int cz = floorZ & 0xFF;
        x -= floorX;
        z -= floorZ;
        double u = fade(x);
        double w = fade(z);
        int a = permutations[cx];
        int aa = permutations[a] + cz;
        int b = permutations[cx + 1];
        int ba = permutations[b] + cz;
        double l0 = lerp(u, grad(permutations[aa], x, 0.0D, z), grad(permutations[ba], x - 1.0D, 0.0D, z));
        double l1 = lerp(u, grad(permutations[aa + 1], x, 0.0D, z - 1.0D), grad(permutations[ba + 1], x - 1.0D, 0.0D, z - 1.0D));
        return lerp(w, l0, l1) / amplitude;
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6.0D - 15.0D) + 10.0D);
    }

    private static double grad(int hash, double x, double y, double z) {
        return switch (hash & 0xF) {
            case 0x0 -> x + y;
            case 0x1 -> -x + y;
            case 0x2 -> x - y;
            case 0x3 -> -x - y;
            case 0x4 -> x + z;
            case 0x5 -> -x + z;
            case 0x6 -> x - z;
            case 0x7 -> -x - z;
            case 0x8 -> y + z;
            case 0x9 -> -y + z;
            case 0xA -> y - z;
            case 0xB -> -y - z;
            case 0xC -> y + x;
            case 0xD -> -y + z;
            case 0xE -> y - x;
            default -> -y - z;
        };
    }
}
