/*
 * Adapted from Moderner Beta (https://github.com/Nostalgica-Reverie/moderner-beta),
 * util/noise/PerlinOctaveNoiseCombined.java. Copyright (c) 2021 B3spectacled. MIT License — see THIRD_PARTY_NOTICES.md.
 */
package games.brennan.dungeontrain.worldgen.legacy.noise;

/** Classic / Indev's domain-warped 2-D noise: {@code first} sampled at X shifted by {@code second}. */
public final class CombinedNoise {

    private final PerlinOctaveNoise first;
    private final PerlinOctaveNoise second;

    public CombinedNoise(PerlinOctaveNoise first, PerlinOctaveNoise second) {
        this.first = first;
        this.second = second;
    }

    public double sample(double x, double z) {
        return first.sample2D(x + second.sample2D(x, z), z);
    }
}
