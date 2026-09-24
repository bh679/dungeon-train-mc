/*
 * Terrain shape and surface adapted from Moderner Beta (https://github.com/Nostalgica-Reverie/moderner-beta),
 * world/chunk/provider/ChunkProviderAlpha.java + util/noise/PerlinNoise.java (sampleAlpha).
 * Copyright (c) 2021 B3spectacled. MIT License — see THIRD_PARTY_NOTICES.md.
 * Restored to Alpha 1.1.2's own fixed 128-block column and 5×17×5 density grid.
 */
package games.brennan.dungeontrain.worldgen.legacy.alpha;

import games.brennan.dungeontrain.worldgen.legacy.beta.BetaBlocks;
import games.brennan.dungeontrain.worldgen.legacy.beta.BetaCaves;
import games.brennan.dungeontrain.worldgen.legacy.beta.BetaTerrain;
import games.brennan.dungeontrain.worldgen.legacy.noise.PerlinOctaveNoise;

import java.util.Random;

/**
 * Alpha 1.1.2's chunk generator, as pure Java: one instance per world seed, immutable after construction
 * (every per-chunk {@link Random} is local), so worldgen workers can share it.
 *
 * <p>Alpha predates biomes: one grass-and-dirt surface everywhere, sand and gravel beaches, no climate.
 * The shape pass is Beta's density grid without the climate factor and with Alpha's own depth curve;
 * every array noise goes through the 3-D sampler ({@link PerlinOctaveNoise#sampleAlphaGrid}). Its
 * "winter mode" froze the sea surface (and later snowed over everything, in {@link AlphaPopulator}).</p>
 *
 * <p>{@link #generate} returns the 16×128×16 block column in Beta's layout ({@link BetaTerrain#index})
 * using {@link BetaBlocks} ids, after terrain, surface and {@link BetaCaves} (Alpha's cave carver is the
 * same algorithm Beta kept).</p>
 */
public final class AlphaTerrain {

    private static final int HEIGHT = BetaTerrain.HEIGHT;
    private static final int SEA_LEVEL = BetaTerrain.SEA_LEVEL;

    private static final int CELLS = 4;
    private static final int SIZE_XZ = CELLS + 1;
    private static final int SIZE_Y = 17;
    private static final double BASE_SIZE = 8.5D;
    private static final double STRETCH_Y = 12.0D;

    private final long seed;
    private final PerlinOctaveNoise minLimitNoise;
    private final PerlinOctaveNoise maxLimitNoise;
    private final PerlinOctaveNoise mainNoise;
    private final PerlinOctaveNoise beachNoise;
    private final PerlinOctaveNoise surfaceNoise;
    private final PerlinOctaveNoise scaleNoise;
    private final PerlinOctaveNoise depthNoise;
    private final PerlinOctaveNoise forestNoise;
    private final BetaCaves caves;

    public AlphaTerrain(long seed) {
        this.seed = seed;
        // Construction order is load-bearing: each stack consumes the shared Random exactly as Alpha did.
        Random random = new Random(seed);
        this.minLimitNoise = new PerlinOctaveNoise(random, 16);
        this.maxLimitNoise = new PerlinOctaveNoise(random, 16);
        this.mainNoise = new PerlinOctaveNoise(random, 8);
        this.beachNoise = new PerlinOctaveNoise(random, 4);
        this.surfaceNoise = new PerlinOctaveNoise(random, 4);
        this.scaleNoise = new PerlinOctaveNoise(random, 10);
        this.depthNoise = new PerlinOctaveNoise(random, 16);
        this.forestNoise = new PerlinOctaveNoise(random, 8);
        this.caves = new BetaCaves(seed);
    }

    public long seed() {
        return seed;
    }

    /** The noise behind Alpha's per-chunk tree count. */
    public PerlinOctaveNoise forestNoise() {
        return forestNoise;
    }

    /** Generate the Alpha column for chunk {@code (chunkX, chunkZ)}; {@code winter} freezes the sea surface. */
    public byte[] generate(int chunkX, int chunkZ, boolean winter) {
        Random rand = new Random((long) chunkX * 341873128712L + (long) chunkZ * 132897987541L);
        byte[] blocks = new byte[16 * 16 * HEIGHT];
        shapeTerrain(chunkX, chunkZ, blocks, winter);
        buildSurface(chunkX, chunkZ, blocks, rand);
        caves.carve(chunkX, chunkZ, blocks);
        return blocks;
    }

    private static int index(int x, int y, int z) {
        return BetaTerrain.index(x, y, z);
    }

    // ---- density terrain ----------------------------------------------------------

    private void shapeTerrain(int chunkX, int chunkZ, byte[] blocks, boolean winter) {
        double[] density = densityField(chunkX * CELLS, chunkZ * CELLS);
        for (int cx = 0; cx < CELLS; cx++) {
            for (int cz = 0; cz < CELLS; cz++) {
                for (int cy = 0; cy < SIZE_Y - 1; cy++) {
                    double d000 = density[((cx) * SIZE_XZ + cz) * SIZE_Y + cy];
                    double d010 = density[((cx) * SIZE_XZ + cz + 1) * SIZE_Y + cy];
                    double d100 = density[((cx + 1) * SIZE_XZ + cz) * SIZE_Y + cy];
                    double d110 = density[((cx + 1) * SIZE_XZ + cz + 1) * SIZE_Y + cy];
                    double s000 = (density[((cx) * SIZE_XZ + cz) * SIZE_Y + cy + 1] - d000) * 0.125D;
                    double s010 = (density[((cx) * SIZE_XZ + cz + 1) * SIZE_Y + cy + 1] - d010) * 0.125D;
                    double s100 = (density[((cx + 1) * SIZE_XZ + cz) * SIZE_Y + cy + 1] - d100) * 0.125D;
                    double s110 = (density[((cx + 1) * SIZE_XZ + cz + 1) * SIZE_Y + cy + 1] - d110) * 0.125D;
                    for (int sy = 0; sy < 8; sy++) {
                        double x0z0 = d000;
                        double x0z1 = d010;
                        double stepX0 = (d100 - d000) * 0.25D;
                        double stepX1 = (d110 - d010) * 0.25D;
                        int y = cy * 8 + sy;
                        for (int sx = 0; sx < 4; sx++) {
                            double d = x0z0;
                            double stepZ = (x0z1 - x0z0) * 0.25D;
                            for (int sz = 0; sz < 4; sz++) {
                                byte block = BetaBlocks.AIR;
                                if (y < SEA_LEVEL) {
                                    block = winter && y >= SEA_LEVEL - 1 ? BetaBlocks.ICE : BetaBlocks.WATER;
                                }
                                if (d > 0.0D) block = BetaBlocks.STONE;
                                blocks[index(cx * 4 + sx, y, cz * 4 + sz)] = block;
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

    /** The 5×17×5 density grid at noise-cell origin {@code (nx, nz)}, index {@code (x·5 + z)·17 + y}. */
    private double[] densityField(int nx, int nz) {
        final double coordScale = 684.412D;
        final double heightScale = 684.412D;
        double[] scale = scaleNoise.sampleAlphaGrid(nx, 0.0D, nz, SIZE_XZ, 1, SIZE_XZ, 1.0D, 0.0D, 1.0D);
        double[] depth = depthNoise.sampleAlphaGrid(nx, 0.0D, nz, SIZE_XZ, 1, SIZE_XZ, 100.0D, 0.0D, 100.0D);
        double[] main = mainNoise.sampleAlphaGrid(nx, 0, nz, SIZE_XZ, SIZE_Y, SIZE_XZ,
                coordScale / 80.0D, heightScale / 160.0D, coordScale / 80.0D);
        double[] min = minLimitNoise.sampleAlphaGrid(nx, 0, nz, SIZE_XZ, SIZE_Y, SIZE_XZ, coordScale, heightScale, coordScale);
        double[] max = maxLimitNoise.sampleAlphaGrid(nx, 0, nz, SIZE_XZ, SIZE_Y, SIZE_XZ, coordScale, heightScale, coordScale);

        double[] out = new double[SIZE_XZ * SIZE_Y * SIZE_XZ];
        int ndx = 0;
        for (int column = 0; column < SIZE_XZ * SIZE_XZ; column++) {
            double s = (scale[column] + 256.0D) / 512.0D;
            if (s > 1.0D) s = 1.0D;
            double dep = depth[column] / 8000.0D;
            if (dep < 0.0D) dep = -dep;
            dep = dep * 3.0D - 3.0D;
            if (dep < 0.0D) {
                dep /= 2.0D;
                if (dep < -1.0D) dep = -1.0D;
                dep /= 1.4D;
                dep /= 2.0D;
                s = 0.0D;
            } else {
                if (dep > 1.0D) dep = 1.0D;
                dep /= 6.0D;
            }
            s += 0.5D;
            dep = dep * BASE_SIZE / 8.0D;
            double centre = BASE_SIZE + dep * 4.0D;

            for (int y = 0; y < SIZE_Y; y++) {
                double offset = ((double) y - centre) * STRETCH_Y / s;
                if (offset < 0.0D) offset *= 4.0D;
                double lo = min[ndx] / 512.0D;
                double hi = max[ndx] / 512.0D;
                double mix = (main[ndx] / 10.0D + 1.0D) / 2.0D;
                double d;
                if (mix < 0.0D) d = lo;
                else if (mix > 1.0D) d = hi;
                else d = lo + (hi - lo) * mix;
                d -= offset;
                if (y > SIZE_Y - 4) {
                    double t = (float) (y - (SIZE_Y - 4)) / 3.0F;
                    d = d * (1.0D - t) + -10.0D * t;
                }
                out[ndx++] = d;
            }
        }
        return out;
    }

    // ---- surface ---------------------------------------------------------------------

    /**
     * Alpha's surface pass. Differs from Beta's: the gravel noise samples with X and Z swapped, bedrock
     * reaches one block lower, the top block also lands on any underwater floor open to the air above,
     * and there is no sandstone under the sand.
     */
    private void buildSurface(int chunkX, int chunkZ, byte[] blocks, Random rand) {
        final double d = 0.03125D;
        double[] sand = beachNoise.sampleAlphaGrid(chunkX * 16, chunkZ * 16, 0.0D, 16, 16, 1, d, d, 1.0D);
        double[] gravel = beachNoise.sampleAlphaGrid(chunkZ * 16, 109.0134D, chunkX * 16, 16, 1, 16, d, 1.0D, d);
        double[] depthNoise = surfaceNoise.sampleAlphaGrid(chunkX * 16, chunkZ * 16, 0.0D, 16, 16, 1,
                d * 2.0D, d * 2.0D, d * 2.0D);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int n = x + z * 16;
                boolean sandBeach = sand[n] + rand.nextDouble() * 0.2D > 0.0D;
                boolean gravelBeach = gravel[n] + rand.nextDouble() * 0.2D > 3.0D;
                int depth = (int) (depthNoise[n] / 3.0D + 3.0D + rand.nextDouble() * 0.25D);
                int run = -1;
                byte top = BetaBlocks.GRASS;
                byte filler = BetaBlocks.DIRT;
                for (int y = HEIGHT - 1; y >= 0; y--) {
                    int i = index(x, y, z);
                    if (y <= rand.nextInt(6) - 1) {
                        blocks[i] = BetaBlocks.BEDROCK;
                        continue;
                    }
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
                        if (y < SEA_LEVEL && top == BetaBlocks.AIR) top = BetaBlocks.WATER;
                        run = depth;
                        boolean openAbove = y + 1 < HEIGHT && blocks[index(x, y + 1, z)] == BetaBlocks.AIR;
                        blocks[i] = y >= SEA_LEVEL - 1 || openAbove ? top : filler;
                    } else if (run > 0) {
                        run--;
                        blocks[i] = filler;
                    }
                }
            }
        }
    }
}
