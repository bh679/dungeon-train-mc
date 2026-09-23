/*
 * Terrain shape and surface adapted from Moderner Beta (https://github.com/Nostalgica-Reverie/moderner-beta),
 * world/chunk/provider/ChunkProviderBeta.java + api/world/chunk/ChunkProviderNoise.java.
 * Copyright (c) 2021 B3spectacled. MIT License — see THIRD_PARTY_NOTICES.md.
 * Restored to Beta 1.7.3's own fixed 128-block column and 5×17×5 density grid.
 */
package games.brennan.dungeontrain.worldgen.legacy.beta;

import games.brennan.dungeontrain.worldgen.legacy.noise.PerlinOctaveNoise;

import java.util.Random;

/**
 * Beta 1.7.3's chunk generator, as pure Java: one instance per world seed, immutable after
 * construction (every per-chunk {@link Random} is local), so C2ME / vanilla worldgen workers can share it.
 *
 * <p>{@link #generate} returns a {@link BetaChunk} — the 16×128×16 Beta block column in Beta's own
 * layout ({@code x << 11 | z << 7 | y}), after the three passes the original ran inside chunk
 * generation: density terrain (stone / water / ice), the biome surface (grass, dirt, sand and gravel
 * beaches, sandstone, bedrock), and {@link BetaCaves}. Decoration (trees, ores, lakes, …) happens later,
 * in {@link BetaPopulator}, like Beta's separate populate step.</p>
 */
public final class BetaTerrain {

    /** Beta's world height. */
    public static final int HEIGHT = 128;
    /** Beta's sea level: water fills every non-solid block below it. */
    public static final int SEA_LEVEL = 64;

    private static final int CELLS = 4;          // horizontal density cells per chunk
    private static final int SIZE_XZ = CELLS + 1; // density samples per chunk, per horizontal axis
    private static final int SIZE_Y = 17;        // density samples per column (8-block cells)

    private final long seed;
    private final PerlinOctaveNoise minLimitNoise;
    private final PerlinOctaveNoise maxLimitNoise;
    private final PerlinOctaveNoise mainNoise;
    private final PerlinOctaveNoise beachNoise;
    private final PerlinOctaveNoise surfaceNoise;
    private final PerlinOctaveNoise scaleNoise;
    private final PerlinOctaveNoise depthNoise;
    private final PerlinOctaveNoise forestNoise;
    private final BetaClimate climate;
    private final BetaCaves caves;

    public BetaTerrain(long seed) {
        this.seed = seed;
        // Construction order is load-bearing: each stack consumes the shared Random exactly as Beta did.
        Random random = new Random(seed);
        this.minLimitNoise = new PerlinOctaveNoise(random, 16);
        this.maxLimitNoise = new PerlinOctaveNoise(random, 16);
        this.mainNoise = new PerlinOctaveNoise(random, 8);
        this.beachNoise = new PerlinOctaveNoise(random, 4);
        this.surfaceNoise = new PerlinOctaveNoise(random, 4);
        this.scaleNoise = new PerlinOctaveNoise(random, 10);
        this.depthNoise = new PerlinOctaveNoise(random, 16);
        this.forestNoise = new PerlinOctaveNoise(random, 8);
        this.climate = new BetaClimate(seed);
        this.caves = new BetaCaves(seed);
    }

    public long seed() {
        return seed;
    }

    public BetaClimate climate() {
        return climate;
    }

    /** The noise behind Beta's per-chunk tree count. */
    public PerlinOctaveNoise forestNoise() {
        return forestNoise;
    }

    /** Generate the Beta column for chunk {@code (chunkX, chunkZ)}. */
    public BetaChunk generate(int chunkX, int chunkZ) {
        Random rand = new Random((long) chunkX * 341873128712L + (long) chunkZ * 132897987541L);
        double[] temps = new double[256];
        double[] rains = new double[256];
        BetaBiome[] biomes = new BetaBiome[256];
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                double[] c = climate.sample(chunkX * 16 + x, chunkZ * 16 + z);
                int i = x * 16 + z;
                temps[i] = c[0];
                rains[i] = c[1];
                biomes[i] = BetaBiome.of(c[0], c[1]);
            }
        }
        byte[] blocks = new byte[16 * 16 * HEIGHT];
        shapeTerrain(chunkX, chunkZ, blocks, temps, rains);
        buildSurface(chunkX, chunkZ, blocks, biomes, rand);
        caves.carve(chunkX, chunkZ, blocks);
        return new BetaChunk(blocks, biomes);
    }

    /** Beta block-array index. */
    public static int index(int x, int y, int z) {
        return x << 11 | z << 7 | y;
    }

    // ---- density terrain ----------------------------------------------------------

    private void shapeTerrain(int chunkX, int chunkZ, byte[] blocks, double[] temps, double[] rains) {
        double[] density = densityField(chunkX * CELLS, chunkZ * CELLS, temps, rains);
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
                            int x = cx * 4 + sx;
                            double d = x0z0;
                            double stepZ = (x0z1 - x0z0) * 0.25D;
                            for (int sz = 0; sz < 4; sz++) {
                                int z = cz * 4 + sz;
                                byte block = BetaBlocks.AIR;
                                if (y < SEA_LEVEL) {
                                    block = temps[x * 16 + z] < 0.5D && y >= SEA_LEVEL - 1
                                            ? BetaBlocks.ICE : BetaBlocks.WATER;
                                }
                                if (d > 0.0D) block = BetaBlocks.STONE;
                                blocks[index(x, y, z)] = block;
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
    private double[] densityField(int nx, int nz, double[] temps, double[] rains) {
        final double coordScale = 684.412D;
        final double heightScale = 684.412D;
        double[] scale = scaleNoise.sampleGrid(nx, 10.0D, nz, SIZE_XZ, 1, SIZE_XZ, 1.121D, 1.0D, 1.121D);
        double[] depth = depthNoise.sampleGrid(nx, 10.0D, nz, SIZE_XZ, 1, SIZE_XZ, 200.0D, 1.0D, 200.0D);
        double[] main = mainNoise.sampleGrid(nx, 0, nz, SIZE_XZ, SIZE_Y, SIZE_XZ,
                coordScale / 80.0D, heightScale / 160.0D, coordScale / 80.0D);
        double[] min = minLimitNoise.sampleGrid(nx, 0, nz, SIZE_XZ, SIZE_Y, SIZE_XZ, coordScale, heightScale, coordScale);
        double[] max = maxLimitNoise.sampleGrid(nx, 0, nz, SIZE_XZ, SIZE_Y, SIZE_XZ, coordScale, heightScale, coordScale);

        double[] out = new double[SIZE_XZ * SIZE_Y * SIZE_XZ];
        int column = 0;
        int ndx = 0;
        int step = 16 / SIZE_XZ;
        for (int x = 0; x < SIZE_XZ; x++) {
            int bx = x * step + step / 2;
            for (int z = 0; z < SIZE_XZ; z++) {
                int bz = z * step + step / 2;
                double temp = temps[bx * 16 + bz];
                double rain = rains[bx * 16 + bz] * temp;
                rain = 1.0D - rain;
                rain *= rain;
                rain *= rain;
                rain = 1.0D - rain;

                double s = (scale[column] + 256.0D) / 512.0D;
                s *= rain;
                if (s > 1.0D) s = 1.0D;
                double dep = depth[column] / 8000.0D;
                if (dep < 0.0D) dep = -dep * 0.3D;
                dep = dep * 3.0D - 2.0D;
                if (dep < 0.0D) {
                    dep /= 2.0D;
                    if (dep < -1.0D) dep = -1.0D;
                    dep /= 1.4D;
                    dep /= 2.0D;
                    s = 0.0D;
                } else {
                    if (dep > 1.0D) dep = 1.0D;
                    dep /= 8.0D;
                }
                if (s < 0.0D) s = 0.0D;
                s += 0.5D;
                dep = dep * (double) SIZE_Y / 16.0D;
                double centre = (double) SIZE_Y / 2.0D + dep * 4.0D;
                column++;

                for (int y = 0; y < SIZE_Y; y++) {
                    double offset = ((double) y - centre) * 12.0D / s;
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
        }
        return out;
    }

    // ---- surface ---------------------------------------------------------------------

    private void buildSurface(int chunkX, int chunkZ, byte[] blocks, BetaBiome[] biomes, Random rand) {
        final double d = 0.03125D;
        double[] sand = beachNoise.sampleGrid(chunkX * 16, chunkZ * 16, 0.0D, 16, 16, 1, d, d, 1.0D);
        double[] gravel = beachNoise.sampleGrid(chunkX * 16, 109.0134D, chunkZ * 16, 16, 1, 16, d, 1.0D, d);
        double[] depthNoise = surfaceNoise.sampleGrid(chunkX * 16, chunkZ * 16, 0.0D, 16, 16, 1, d * 2.0D, d * 2.0D, d * 2.0D);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                BetaBiome biome = biomes[x * 16 + z];
                int n = z + x * 16;
                boolean sandBeach = sand[n] + rand.nextDouble() * 0.2D > 0.0D;
                boolean gravelBeach = gravel[n] + rand.nextDouble() * 0.2D > 3.0D;
                int depth = (int) (depthNoise[n] / 3.0D + 3.0D + rand.nextDouble() * 0.25D);
                int run = -1;
                byte top = biome.topBlock();
                byte filler = biome.fillerBlock();
                for (int y = HEIGHT - 1; y >= 0; y--) {
                    int i = index(x, y, z);
                    if (y <= rand.nextInt(5)) {
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
                            top = biome.topBlock();
                            filler = biome.fillerBlock();
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
                        blocks[i] = y >= SEA_LEVEL - 1 ? top : filler;
                    } else if (run > 0) {
                        run--;
                        blocks[i] = filler;
                        if (run == 0 && filler == BetaBlocks.SAND) {
                            run = rand.nextInt(4);
                            filler = BetaBlocks.SANDSTONE;
                        }
                    }
                }
            }
        }
    }
}
