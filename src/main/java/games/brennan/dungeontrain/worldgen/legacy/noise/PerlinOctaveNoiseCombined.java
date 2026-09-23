/*
 * Adapted from Moderner Beta (https://github.com/Nostalgica-Reverie/moderner-beta),
 * util/noise/PerlinOctaveNoiseCombined.java. Copyright (c) 2021 B3spectacled. MIT License — see THIRD_PARTY_NOTICES.md.
 */
package games.brennan.dungeontrain.worldgen.legacy.noise;

/** Classic/Indev "combined" noise: the first stack sampled at an X warped by the second. */
public final class PerlinOctaveNoiseCombined {

    private final PerlinOctaveNoise first;
    private final PerlinOctaveNoise second;

    public PerlinOctaveNoiseCombined(PerlinOctaveNoise first, PerlinOctaveNoise second) {
        this.first = first;
        this.second = second;
    }

    public double sample(double x, double y) {
        return first.sampleXY(x + second.sampleXY(x, y), y);
    }
}
