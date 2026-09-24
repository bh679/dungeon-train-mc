/*
 * Terrain shape and surface adapted from Moderner Beta (https://github.com/Nostalgica-Reverie/moderner-beta),
 * world/chunk/provider/ChunkProviderInfdev415.java, ChunkProviderInfdev420.java, ChunkProviderInfdev611.java
 * + api/world/chunk/ChunkProviderNoise.java, with the density constants of settings/ModernBetaSettingsPresets.java.
 * Copyright (c) 2021 B3spectacled. MIT License — see THIRD_PARTY_NOTICES.md.
 * Restored to Infdev's own fixed 128-block column.
 */
package games.brennan.dungeontrain.worldgen.legacy.infdev;

import games.brennan.dungeontrain.worldgen.legacy.beta.BetaBlocks;
import games.brennan.dungeontrain.worldgen.legacy.beta.BetaCaves;
import games.brennan.dungeontrain.worldgen.legacy.beta.BetaTerrain;
import games.brennan.dungeontrain.worldgen.legacy.noise.PerlinOctaveNoise;

import java.util.Random;

/**
 * One of the three Infdev density generators — 20100415, 20100420 or 20100611. They share the pipeline
 * (a coarse 3-D density grid, trilinearly interpolated to blocks; Beta-style sand and gravel beaches;
 * Beta caves for 420 and 611) and differ in how a density sample is formed. Immutable and thread-safe.
 */
final class InfdevDensityTerrain {

    /** Water fills every open block below this Y (top water at 63). */
    static final int SEA_LEVEL = 64;

    private static final int CELL_XZ = 4;
    private static final int CELLS_XZ = 16 / CELL_XZ;
    private static final int SIZE_XZ = CELLS_XZ + 1;

    // Preset constants — the originals are floats, so keep their float rounding.
    private static final double COORD_SCALE = 684.412F;
    private static final double LIMIT_SCALE = 512.0F;
    private static final double MAIN_SCALE_XZ = 80.0F;
    private static final double BASE_SIZE = 8.5F;
    private static final double STRETCH_Y = 12.0F;
    private static final double DEPTH_SCALE_XZ = 100.0F;

    private final InfdevVersion version;
    private final int cellY;
    private final int cellsY;
    private final PerlinOctaveNoise minLimitNoise;
    private final PerlinOctaveNoise maxLimitNoise;
    private final PerlinOctaveNoise mainNoise;
    private final PerlinOctaveNoise beachNoise;
    private final PerlinOctaveNoise surfaceNoise;
    private final PerlinOctaveNoise scaleNoise;
    private final PerlinOctaveNoise depthNoise;
    private final PerlinOctaveNoise forestNoise;
    private final BetaCaves caves;

    InfdevDensityTerrain(long seed, InfdevVersion version, BetaCaves caves) {
        if (!version.isDensity()) throw new IllegalArgumentException("not a density version: " + version);
        this.version = version;
        this.cellY = version == InfdevVersion.V415 ? 4 : 8;
        this.cellsY = BetaTerrain.HEIGHT / cellY;
        this.caves = version == InfdevVersion.V415 ? null : caves;
        // Construction order is load-bearing: each stack consumes the shared Random as the original did.
        Random random = new Random(seed);
        this.minLimitNoise = new PerlinOctaveNoise(random, 16);
        this.maxLimitNoise = new PerlinOctaveNoise(random, 16);
        this.mainNoise = new PerlinOctaveNoise(random, 8);
        this.beachNoise = new PerlinOctaveNoise(random, 4);
        this.surfaceNoise = new PerlinOctaveNoise(random, 4);
        if (version == InfdevVersion.V611) {
            this.scaleNoise = new PerlinOctaveNoise(random, 10);
            this.depthNoise = new PerlinOctaveNoise(random, 16);
            this.forestNoise = new PerlinOctaveNoise(random, 8);
        } else {
            new PerlinOctaveNoise(random, 5); // unused in the original, but it consumes the Random
            this.scaleNoise = null;
            this.depthNoise = null;
            this.forestNoise = new PerlinOctaveNoise(random, 5);
        }
    }

    PerlinOctaveNoise forestNoise() {
        return forestNoise;
    }

    /** The old-world column for chunk {@code (chunkX, chunkZ)}, in {@link BetaTerrain#index} layout. */
    byte[] generate(int chunkX, int chunkZ) {
        byte[] blocks = new byte[16 * 16 * BetaTerrain.HEIGHT];
        shapeTerrain(chunkX, chunkZ, blocks);
        buildSurface(chunkX, chunkZ, blocks);
        if (caves != null) caves.carve(chunkX, chunkZ, blocks);
        return blocks;
    }

    // ---- density terrain ----------------------------------------------------------

    private void shapeTerrain(int chunkX, int chunkZ, byte[] blocks) {
        int sizeY = cellsY + 1;
        double[] density = new double[SIZE_XZ * SIZE_XZ * sizeY];
        for (int x = 0; x < SIZE_XZ; x++) {
            for (int z = 0; z < SIZE_XZ; z++) {
                sampleColumn(density, (x * SIZE_XZ + z) * sizeY, chunkX * CELLS_XZ + x, chunkZ * CELLS_XZ + z);
            }
        }
        for (int cx = 0; cx < CELLS_XZ; cx++) {
            for (int cz = 0; cz < CELLS_XZ; cz++) {
                int c00 = (cx * SIZE_XZ + cz) * sizeY;
                int c01 = (cx * SIZE_XZ + cz + 1) * sizeY;
                int c10 = ((cx + 1) * SIZE_XZ + cz) * sizeY;
                int c11 = ((cx + 1) * SIZE_XZ + cz + 1) * sizeY;
                for (int cy = 0; cy < cellsY; cy++) {
                    fillCell(blocks, density, cx, cy, cz, c00 + cy, c01 + cy, c10 + cy, c11 + cy);
                }
            }
        }
    }

    /** Trilinear fill of one {@code 4 × cellY × 4} cell from its eight corner samples. */
    private void fillCell(byte[] blocks, double[] d, int cx, int cy, int cz, int i00, int i01, int i10, int i11) {
        for (int sy = 0; sy < cellY; sy++) {
            double ty = (double) sy / cellY;
            double d00 = lerp(ty, d[i00], d[i00 + 1]);
            double d01 = lerp(ty, d[i01], d[i01 + 1]);
            double d10 = lerp(ty, d[i10], d[i10 + 1]);
            double d11 = lerp(ty, d[i11], d[i11 + 1]);
            int y = cy * cellY + sy;
            for (int sx = 0; sx < CELL_XZ; sx++) {
                double tx = (double) sx / CELL_XZ;
                double z0 = lerp(tx, d00, d10);
                double z1 = lerp(tx, d01, d11);
                for (int sz = 0; sz < CELL_XZ; sz++) {
                    double value = lerp((double) sz / CELL_XZ, z0, z1);
                    byte block = value > 0.0D ? BetaBlocks.STONE : y < SEA_LEVEL ? BetaBlocks.WATER : BetaBlocks.AIR;
                    blocks[BetaTerrain.index(cx * CELL_XZ + sx, y, cz * CELL_XZ + sz)] = block;
                }
            }
        }
    }

    /** One density column at noise coordinates {@code (nx, nz)}, written to {@code out[base .. base + cellsY]}. */
    private void sampleColumn(double[] out, int base, int nx, int nz) {
        switch (version) {
            case V415 -> column415(out, base, nx, nz);
            case V420 -> column420(out, base, nx, nz, BASE_SIZE, STRETCH_Y, 1.0D, 2.0D);
            case V611 -> column611(out, base, nx, nz);
            default -> throw new IllegalStateException(version.name());
        }
    }

    /** 20100415: main noise picks between the clamped limit noises; offset {@code y·4 − 64}, tripled below. */
    private void column415(double[] out, int base, int nx, int nz) {
        final double heightScale = 984.412F;
        final double mainScaleY = 400.0F;
        for (int y = 0; y <= cellsY; y++) {
            double offset = y * cellY - (double) SEA_LEVEL;
            if (offset < 0.0D) offset *= 3.0D;
            double main = mainNoise.sample3D(nx * COORD_SCALE / MAIN_SCALE_XZ, y * COORD_SCALE / mainScaleY,
                    nz * COORD_SCALE / MAIN_SCALE_XZ) / 2.0D;
            double density;
            if (main < -1.0D) {
                density = clamp(limit(minLimitNoise, nx, y, nz, heightScale) - offset);
            } else if (main > 1.0D) {
                density = clamp(limit(maxLimitNoise, nx, y, nz, heightScale) - offset);
            } else {
                double lo = clamp(limit(minLimitNoise, nx, y, nz, heightScale) - offset);
                double hi = clamp(limit(maxLimitNoise, nx, y, nz, heightScale) - offset);
                density = lo + (hi - lo) * ((main + 1.0D) / 2.0D);
            }
            out[base + y] = density;
        }
    }

    private double limit(PerlinOctaveNoise noise, int nx, int y, int nz, double heightScale) {
        return noise.sample3D(nx * COORD_SCALE, y * heightScale, nz * COORD_SCALE) / LIMIT_SCALE;
    }

    private static double clamp(double density) {
        return Math.max(-10.0D, Math.min(10.0D, density));
    }

    /**
     * 20100420 (and 611's inner loop): Alpha-style blend of the limit noises by the main noise, minus
     * {@code (y − depth) · stretch / scale} ({@code × lowMul} below the centre).
     */
    private void column420(double[] out, int base, int nx, int nz, double depth, double stretch, double scale,
                           double lowMul) {
        for (int y = 0; y <= cellsY; y++) {
            double offset = (y - depth) * stretch / scale;
            if (offset < 0.0D) offset *= lowMul;
            double main = (mainNoise.sampleScaled(nx, y, nz, COORD_SCALE / MAIN_SCALE_XZ, COORD_SCALE / 160.0F,
                    COORD_SCALE / MAIN_SCALE_XZ) / 10.0D + 1.0D) / 2.0D;
            double density;
            if (main < 0.0D) {
                density = scaledLimit(minLimitNoise, nx, y, nz);
            } else if (main > 1.0D) {
                density = scaledLimit(maxLimitNoise, nx, y, nz);
            } else {
                double lo = scaledLimit(minLimitNoise, nx, y, nz);
                double hi = scaledLimit(maxLimitNoise, nx, y, nz);
                density = lo + (hi - lo) * main;
            }
            out[base + y] = density - offset;
        }
    }

    private double scaledLimit(PerlinOctaveNoise noise, int nx, int y, int nz) {
        return noise.sampleScaled(nx, y, nz, COORD_SCALE, COORD_SCALE, COORD_SCALE) / LIMIT_SCALE;
    }

    /** 20100611: per-column scale and depth noise set the centre and steepness, then top/bottom slides. */
    private void column611(double[] out, int base, int nx, int nz) {
        double scale = scaleNoise.sampleScaled(nx, 0.0D, nz, 1.0D, 0.0D, 1.0D);
        double depth = depthNoise.sampleScaled(nx, 0.0D, nz, DEPTH_SCALE_XZ, 0.0D, DEPTH_SCALE_XZ);
        scale = Math.min(1.0D, (scale + 256.0D) / 512.0D);
        depth = Math.abs(depth / 8000.0D) * 3.0D - 3.0D;
        if (depth < 0.0D) {
            depth = Math.max(-1.0D, depth / 2.0D) / 1.4D;
            scale = 0.0D;
        } else {
            depth = Math.min(1.0D, depth) / 6.0D;
        }
        scale += 0.5D;
        depth = BASE_SIZE + depth * BASE_SIZE / 8.0D * 4.0D;
        column420(out, base, nx, nz, depth, STRETCH_Y, scale, 4.0D);
        for (int y = 0; y <= cellsY; y++) {
            double d = out[base + y];
            d = clampedLerp(-10.0D, d, (cellsY - y) / 3.0D);
            d = clampedLerp(15.0D, d, y / 3.0D);
            out[base + y] = d;
        }
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }

    private static double clampedLerp(double start, double end, double t) {
        if (t < 0.0D) return start;
        if (t > 1.0D) return end;
        return lerp(t, start, end);
    }

    // ---- surface ---------------------------------------------------------------------

    /** Beta-style run-depth surface: grass/dirt, sand and gravel shores near sea level, bedrock floor. */
    private void buildSurface(int chunkX, int chunkZ, byte[] blocks) {
        final double s = 0.03125D;
        Random rand = InfdevTerrain.surfaceRandom(chunkX, chunkZ);
        Random bedrockRand = InfdevTerrain.surfaceRandom(chunkX, chunkZ);
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = (chunkX << 4) + lx;
                int z = (chunkZ << 4) + lz;
                boolean sandBeach = beachNoise.sample3D(x * s, z * s, 0.0D) + rand.nextDouble() * 0.2D > 0.0D;
                boolean gravelBeach = beachNoise.sample3D(z * s, 109.0134D, x * s) + rand.nextDouble() * 0.2D > 3.0D;
                int depth = (int) (surfaceNoise.sample2D(x * s * 2.0D, z * s * 2.0D) / 3.0D + 3.0D
                        + rand.nextDouble() * 0.25D);
                surfaceColumn(blocks, lx, lz, sandBeach, gravelBeach, depth, bedrockRand);
            }
        }
    }

    private static void surfaceColumn(byte[] blocks, int x, int z, boolean sandBeach, boolean gravelBeach,
                                      int depth, Random bedrockRand) {
        int run = -1;
        byte top = BetaBlocks.GRASS;
        byte filler = BetaBlocks.DIRT;
        for (int y = BetaTerrain.HEIGHT - 1; y >= 0; y--) {
            int i = BetaTerrain.index(x, y, z);
            if (y <= bedrockRand.nextInt(5)) {
                blocks[i] = BetaBlocks.BEDROCK;
                continue;
            }
            byte b = blocks[i];
            if (b == BetaBlocks.AIR) {
                run = -1;
            } else if (b == BetaBlocks.STONE) {
                if (run == -1) {
                    if (depth <= 0) {
                        top = BetaBlocks.AIR;
                        filler = BetaBlocks.STONE;
                    } else if (y >= SEA_LEVEL - 4 && y <= SEA_LEVEL + 1) {
                        top = BetaBlocks.GRASS;
                        filler = BetaBlocks.DIRT;
                        if (gravelBeach) {
                            top = BetaBlocks.AIR;
                            filler = BetaBlocks.GRAVEL;
                        }
                        if (sandBeach) {
                            top = BetaBlocks.SAND;
                            filler = BetaBlocks.SAND;
                        }
                    }
                    run = depth;
                    if (y < SEA_LEVEL && top == BetaBlocks.AIR) top = BetaBlocks.WATER;
                    boolean exposed = y >= SEA_LEVEL - 1 || blocks[BetaTerrain.index(x, y + 1, z)] == BetaBlocks.AIR;
                    blocks[i] = exposed ? top : filler;
                } else if (run > 0) {
                    run--;
                    blocks[i] = filler;
                }
            }
        }
    }
}
