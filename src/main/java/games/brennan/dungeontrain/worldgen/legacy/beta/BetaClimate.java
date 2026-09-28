/*
 * Climate formula adapted from Moderner Beta (https://github.com/Nostalgica-Reverie/moderner-beta),
 * world/biome/provider/BiomeProviderBeta.java. Copyright (c) 2021 B3spectacled. MIT License — see
 * THIRD_PARTY_NOTICES.md.
 */
package games.brennan.dungeontrain.worldgen.legacy.beta;

import games.brennan.dungeontrain.worldgen.legacy.noise.SimplexOctaveNoise;

import java.util.Random;

/**
 * Beta's temperature / humidity field: three simplex octave stacks seeded from the world seed, blended
 * and clamped to {@code [0, 1]}. Drives the biome map, the terrain's flatness (dry = flatter), water
 * freezing and snow cover. Immutable after construction, so safe to share across worldgen threads.
 */
public final class BetaClimate {

    private static final double TEMP_SCALE = 0.025D;
    private static final double RAIN_SCALE = 0.05D;
    private static final double DETAIL_SCALE = 0.25D;

    private final SimplexOctaveNoise tempNoise;
    private final SimplexOctaveNoise rainNoise;
    private final SimplexOctaveNoise detailNoise;

    public BetaClimate(long seed) {
        this.tempNoise = new SimplexOctaveNoise(new Random(seed * 9871L), 4);
        this.rainNoise = new SimplexOctaveNoise(new Random(seed * 39811L), 4);
        this.detailNoise = new SimplexOctaveNoise(new Random(seed * 543321L), 2);
    }

    /** Temperature at block {@code (x, z)}, {@code 0..1}. */
    public double temperature(int x, int z) {
        return sample(x, z)[0];
    }

    /** {@code {temperature, humidity}} at block {@code (x, z)}, each {@code 0..1}. */
    public double[] sample(int x, int z) {
        double temp = tempNoise.sample(x, z, TEMP_SCALE, 0.25D);
        double rain = rainNoise.sample(x, z, RAIN_SCALE, 0.33333333333333331D);
        double detail = detailNoise.sample(x, z, DETAIL_SCALE, 0.58823529411764708D);
        detail = detail * 1.1D + 0.5D;
        temp = (temp * 0.15D + 0.7D) * 0.99D + detail * 0.01D;
        rain = (rain * 0.15D + 0.5D) * 0.998D + detail * 0.002D;
        temp = 1.0D - (1.0D - temp) * (1.0D - temp);
        return new double[] {clamp01(temp), clamp01(rain)};
    }

    /** The Beta biome at block {@code (x, z)}. */
    public BetaBiome biome(int x, int z) {
        double[] c = sample(x, z);
        return BetaBiome.of(c[0], c[1]);
    }

    private static double clamp01(double d) {
        return d < 0.0D ? 0.0D : Math.min(d, 1.0D);
    }
}
