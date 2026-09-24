/*
 * Terrain shape and surface adapted from Moderner Beta (https://github.com/Nostalgica-Reverie/moderner-beta),
 * world/chunk/provider/ChunkProviderSky.java + the Skylands preset in settings/ModernBetaSettingsPresets.java.
 * Copyright (c) 2021 B3spectacled. MIT License — see THIRD_PARTY_NOTICES.md.
 * Restored to Beta 1.7.3's own fixed 128-block column and 3×33×3 density grid.
 */
package games.brennan.dungeontrain.worldgen.legacy.sky;

import games.brennan.dungeontrain.worldgen.legacy.beta.BetaBiome;
import games.brennan.dungeontrain.worldgen.legacy.beta.BetaBlocks;
import games.brennan.dungeontrain.worldgen.legacy.beta.BetaCaves;
import games.brennan.dungeontrain.worldgen.legacy.beta.BetaChunk;
import games.brennan.dungeontrain.worldgen.legacy.beta.BetaTerrain;
import games.brennan.dungeontrain.worldgen.legacy.noise.PerlinOctaveNoise;

import java.util.Arrays;
import java.util.Random;

/**
 * Beta 1.7.3's unused "Sky" dimension generator: floating grass-and-dirt islands over open void — no sea,
 * no beaches, no bedrock. The density field is Beta's own noise at twice the horizontal scale, pushed down
 * by a constant and squeezed by long top and bottom slides, so land only forms in the lower middle of
 * the column and never reaches old {@code y = 0}.
 *
 * <p>One instance per world seed, immutable after construction (every per-chunk {@link Random} is local),
 * so worldgen workers can share it. {@link #generate} returns the column in Beta's layout
 * ({@link BetaTerrain#index}) after the density, surface and {@link BetaCaves} passes; decoration comes
 * later, like Beta's separate populate step.</p>
 */
public final class SkyTerrain {

    private static final int CELLS = 2;           // horizontal density cells per chunk (8 blocks each)
    private static final int SIZE_XZ = CELLS + 1; // density samples per chunk, per horizontal axis
    private static final int SIZE_Y = 33;         // density samples per column (4-block cells)

    private static final double COORD_SCALE = 684.412D * 2.0D;
    private static final double HEIGHT_SCALE = 684.412D;
    /** Constant pushed off every density sample — what turns Beta's ground into scattered islands. */
    private static final double DENSITY_OFFSET = 8.0D;
    private static final double SLIDE_TARGET = -30.0D;
    private static final int TOP_SLIDE_SAMPLES = 32;
    private static final int BOTTOM_SLIDE_SAMPLES = 8;

    private final long seed;
    private final PerlinOctaveNoise minLimitNoise;
    private final PerlinOctaveNoise maxLimitNoise;
    private final PerlinOctaveNoise mainNoise;
    private final PerlinOctaveNoise surfaceNoise;
    private final PerlinOctaveNoise forestNoise;
    private final BetaCaves caves;

    public SkyTerrain(long seed) {
        this.seed = seed;
        // Construction order is load-bearing: Sky built Beta's full set of stacks off one Random, including
        // the beach, scale and depth stacks it never samples, so each kept stack sees Beta's seed stream.
        Random random = new Random(seed);
        this.minLimitNoise = new PerlinOctaveNoise(random, 16);
        this.maxLimitNoise = new PerlinOctaveNoise(random, 16);
        this.mainNoise = new PerlinOctaveNoise(random, 8);
        new PerlinOctaveNoise(random, 4);  // beach (unused in Sky)
        this.surfaceNoise = new PerlinOctaveNoise(random, 4);
        new PerlinOctaveNoise(random, 10); // scale (unused in Sky)
        new PerlinOctaveNoise(random, 16); // depth (unused in Sky)
        this.forestNoise = new PerlinOctaveNoise(random, 8);
        this.caves = new BetaCaves(seed);
    }

    public long seed() {
        return seed;
    }

    /** The noise behind the per-chunk tree count. */
    public PerlinOctaveNoise forestNoise() {
        return forestNoise;
    }

    /** Generate the Sky column for chunk {@code (chunkX, chunkZ)}. */
    public BetaChunk generate(int chunkX, int chunkZ) {
        Random rand = new Random((long) chunkX * 341873128712L + (long) chunkZ * 132897987541L);
        byte[] blocks = new byte[16 * 16 * BetaTerrain.HEIGHT];
        shapeTerrain(chunkX, chunkZ, blocks);
        buildSurface(chunkX, chunkZ, blocks, rand);
        caves.carve(chunkX, chunkZ, blocks);
        BetaBiome[] biomes = new BetaBiome[256];
        Arrays.fill(biomes, BetaBiome.SKY);
        return new BetaChunk(blocks, biomes);
    }

    // ---- density terrain ----------------------------------------------------------

    private void shapeTerrain(int chunkX, int chunkZ, byte[] blocks) {
        double[] density = densityField(chunkX * CELLS, chunkZ * CELLS);
        for (int cx = 0; cx < CELLS; cx++) {
            for (int cz = 0; cz < CELLS; cz++) {
                for (int cy = 0; cy < SIZE_Y - 1; cy++) {
                    double d000 = density[((cx) * SIZE_XZ + cz) * SIZE_Y + cy];
                    double d010 = density[((cx) * SIZE_XZ + cz + 1) * SIZE_Y + cy];
                    double d100 = density[((cx + 1) * SIZE_XZ + cz) * SIZE_Y + cy];
                    double d110 = density[((cx + 1) * SIZE_XZ + cz + 1) * SIZE_Y + cy];
                    double s000 = (density[((cx) * SIZE_XZ + cz) * SIZE_Y + cy + 1] - d000) * 0.25D;
                    double s010 = (density[((cx) * SIZE_XZ + cz + 1) * SIZE_Y + cy + 1] - d010) * 0.25D;
                    double s100 = (density[((cx + 1) * SIZE_XZ + cz) * SIZE_Y + cy + 1] - d100) * 0.25D;
                    double s110 = (density[((cx + 1) * SIZE_XZ + cz + 1) * SIZE_Y + cy + 1] - d110) * 0.25D;
                    for (int sy = 0; sy < 4; sy++) {
                        double x0z0 = d000;
                        double x0z1 = d010;
                        double stepX0 = (d100 - d000) * 0.125D;
                        double stepX1 = (d110 - d010) * 0.125D;
                        int y = cy * 4 + sy;
                        for (int sx = 0; sx < 8; sx++) {
                            int x = cx * 8 + sx;
                            double d = x0z0;
                            double stepZ = (x0z1 - x0z0) * 0.125D;
                            for (int sz = 0; sz < 8; sz++) {
                                int z = cz * 8 + sz;
                                blocks[BetaTerrain.index(x, y, z)] = d > 0.0D ? BetaBlocks.STONE : BetaBlocks.AIR;
                                d += stepZ;
                            }
                            x0z0 += stepX0;
                            x0z1 += stepX1;
                        }
                        d000 += s000;
                        d010 += s010;
                        d100 += s100;
                        d110 += s110;
                    }
                }
            }
        }
    }

    /** The 3×33×3 density grid at noise-cell origin {@code (nx, nz)}, index {@code (x·3 + z)·33 + y}. */
    private double[] densityField(int nx, int nz) {
        double[] main = mainNoise.sampleGrid(nx, 0, nz, SIZE_XZ, SIZE_Y, SIZE_XZ,
                COORD_SCALE / 80.0D, HEIGHT_SCALE / 160.0D, COORD_SCALE / 80.0D);
        double[] min = minLimitNoise.sampleGrid(nx, 0, nz, SIZE_XZ, SIZE_Y, SIZE_XZ, COORD_SCALE, HEIGHT_SCALE, COORD_SCALE);
        double[] max = maxLimitNoise.sampleGrid(nx, 0, nz, SIZE_XZ, SIZE_Y, SIZE_XZ, COORD_SCALE, HEIGHT_SCALE, COORD_SCALE);

        double[] out = new double[SIZE_XZ * SIZE_Y * SIZE_XZ];
        for (int i = 0; i < out.length; i++) {
            int y = i % SIZE_Y;
            double lo = min[i] / 512.0D;
            double hi = max[i] / 512.0D;
            double mix = (main[i] / 10.0D + 1.0D) / 2.0D;
            double d;
            if (mix < 0.0D) d = lo;
            else if (mix > 1.0D) d = hi;
            else d = lo + (hi - lo) * mix;
            out[i] = slide(d - DENSITY_OFFSET, y);
        }
        return out;
    }

    /** Sky's two slides: toward {@link #SLIDE_TARGET} over nearly the whole column top-down, and below sample 8. */
    static double slide(double d, int y) {
        int topStart = SIZE_Y - TOP_SLIDE_SAMPLES;
        if (y > topStart) {
            double t = (float) (y - topStart) / ((float) TOP_SLIDE_SAMPLES - 1.0F);
            d = d * (1.0D - t) + SLIDE_TARGET * t;
        }
        if (y < BOTTOM_SLIDE_SAMPLES) {
            double t = (float) (BOTTOM_SLIDE_SAMPLES - y) / ((float) BOTTOM_SLIDE_SAMPLES - 1.0F);
            d = d * (1.0D - t) + SLIDE_TARGET * t;
        }
        return d;
    }

    // ---- surface ---------------------------------------------------------------------

    private void buildSurface(int chunkX, int chunkZ, byte[] blocks, Random rand) {
        final double d = 0.03125D;
        double[] depthNoise = surfaceNoise.sampleGrid(chunkX * 16, chunkZ * 16, 0.0D, 16, 16, 1, d * 2.0D, d * 2.0D, d * 2.0D);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int depth = (int) (depthNoise[z + x * 16] / 3.0D + 3.0D + rand.nextDouble() * 0.25D);
                int run = -1;
                byte top = BetaBiome.SKY.topBlock();
                byte filler = BetaBiome.SKY.fillerBlock();
                for (int y = BetaTerrain.HEIGHT - 1; y >= 0; y--) {
                    int i = BetaTerrain.index(x, y, z);
                    byte b = blocks[i];
                    if (b == BetaBlocks.AIR) {
                        run = -1;
                        continue;
                    }
                    if (b != BetaBlocks.STONE) continue;
                    if (run == -1) {
                        if (depth <= 0) {
                            top = BetaBlocks.AIR;
                            filler = BetaBlocks.STONE;
                        }
                        run = depth;
                        blocks[i] = top;
                    } else if (run > 0) {
                        run--;
                        blocks[i] = filler;
                    }
                }
            }
        }
    }
}
