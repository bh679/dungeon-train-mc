/*
 * Adapted from Moderner Beta (https://github.com/Nostalgica-Reverie/moderner-beta),
 * util/noise/PerlinOctaveNoise.java. Copyright (c) 2021 B3spectacled. MIT License — see THIRD_PARTY_NOTICES.md.
 * Trimmed to the Alpha/Beta/Infdev samplers.
 */
package games.brennan.dungeontrain.worldgen.legacy.noise;

import java.util.Random;

/** A stack of {@link PerlinNoise} octaves, each half the frequency and double the amplitude of the last. */
public final class PerlinOctaveNoise {

    private final PerlinNoise[] noises;

    public PerlinOctaveNoise(Random random, int octaves) {
        this.noises = new PerlinNoise[octaves];
        for (int i = 0; i < octaves; i++) {
            noises[i] = new PerlinNoise(random);
        }
    }

    /**
     * The old array sampler: a {@code sizeX × sizeY × sizeZ} grid starting at {@code (x, y, z)}, index
     * {@code (x·sizeZ + z)·sizeY + y}. {@code sizeY == 1} is the 2-D form (Y ignored).
     */
    public double[] sampleGrid(double x, double y, double z, int sizeX, int sizeY, int sizeZ,
                               double scaleX, double scaleY, double scaleZ) {
        double[] out = new double[sizeX * sizeY * sizeZ];
        double frequency = 1.0D;
        for (PerlinNoise noise : noises) {
            noise.sampleGrid(out, x, y, z, sizeX, sizeY, sizeZ,
                    scaleX * frequency, scaleY * frequency, scaleZ * frequency, frequency);
            frequency /= 2.0D;
        }
        return out;
    }

    /** The old scalar 2-D sampler (octave {@code i} at {@code 1/2^i} of the coordinate, weighted {@code 2^i}). */
    public double sample2D(double x, double z) {
        double total = 0.0D;
        double frequency = 1.0D;
        for (PerlinNoise noise : noises) {
            total += noise.sample(x * frequency, z * frequency, 0.0D) / frequency;
            frequency /= 2.0D;
        }
        return total;
    }

    /**
     * The Infdev scalar 3-D sampler: octave {@code i} reads {@code (x, y, z) / 2^i}, weighted {@code 2^i}.
     * Moderner Beta's {@code PerlinOctaveNoise.sample(x, y, z)}.
     */
    public double sample3D(double x, double y, double z) {
        double total = 0.0D;
        double frequency = 1.0D;
        for (PerlinNoise noise : noises) {
            total += noise.sample(x / frequency, y / frequency, z / frequency) * frequency;
            frequency *= 2.0D;
        }
        return total;
    }

    /**
     * The Alpha/Infdev scaled 3-D sampler (vanilla's improved-noise path with the Y-lattice clamp):
     * octave {@code i} reads the scaled point at {@code 1/2^i}, weighted {@code 2^i}. Moderner Beta's
     * {@code PerlinOctaveNoise.sample(x, y, z, scaleX, scaleY, scaleZ)}.
     */
    public double sampleScaled(double x, double y, double z, double scaleX, double scaleY, double scaleZ) {
        double total = 0.0D;
        double frequency = 1.0D;
        for (PerlinNoise noise : noises) {
            total += noise.sampleXYZ(x * scaleX * frequency, y * scaleY * frequency, z * scaleZ * frequency,
                    scaleY * frequency, y * scaleY * frequency) / frequency;
            frequency /= 2.0D;
        }
        return total;
    }
}
