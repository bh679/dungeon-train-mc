/*
 * Adapted from Moderner Beta (https://github.com/Nostalgica-Reverie/moderner-beta),
 * util/noise/SimplexOctaveNoise.java. Copyright (c) 2021 B3spectacled. MIT License — see THIRD_PARTY_NOTICES.md.
 */
package games.brennan.dungeontrain.worldgen.legacy.noise;

import java.util.Random;

/** Octave stack of {@link SimplexNoise}, as the Beta climate maps sampled it. */
public final class SimplexOctaveNoise {

    private static final double NOISE_SCALE = 1.5D;

    private final SimplexNoise[] noises;

    public SimplexOctaveNoise(Random random, int octaves) {
        this.noises = new SimplexNoise[octaves];
        for (int i = 0; i < octaves; i++) {
            noises[i] = new SimplexNoise(random);
        }
    }

    /** Sample at {@code (x, z)} with persistence 0.5 and the given lacunarity. */
    public double sample(double x, double z, double scale, double lacunarity) {
        double s = scale / NOISE_SCALE;
        double total = 0.0D;
        double amplitude = 1.0D;
        double frequency = 1.0D;
        for (SimplexNoise noise : noises) {
            total += noise.sample(x, z, s * frequency, s * frequency) * (0.55D / amplitude);
            frequency *= lacunarity;
            amplitude *= 0.5D;
        }
        return total;
    }
}
