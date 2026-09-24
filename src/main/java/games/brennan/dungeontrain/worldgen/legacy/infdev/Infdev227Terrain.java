/*
 * Terrain, landmarks and surface adapted from Moderner Beta (https://github.com/Nostalgica-Reverie/moderner-beta),
 * world/chunk/provider/ChunkProviderInfdev227.java. Copyright (c) 2021 B3spectacled. MIT License — see
 * THIRD_PARTY_NOTICES.md. Restored to Infdev's own 128-block column; landmark placement adapted for the train
 * corridor (see the class javadoc).
 */
package games.brennan.dungeontrain.worldgen.legacy.infdev;

import games.brennan.dungeontrain.worldgen.legacy.beta.BetaBlocks;
import games.brennan.dungeontrain.worldgen.legacy.beta.BetaTerrain;
import games.brennan.dungeontrain.worldgen.legacy.noise.PerlinOctaveNoise;

import java.util.Random;

/**
 * Infdev 20100227's generator: a 2-D noise heightmap (no caves, no overhangs), grass over one layer of
 * dirt, and its two landmarks — the brick pyramids and the obsidian walls.
 *
 * <p>The original put its walls along {@code x == 0} and {@code z == 0} and seeded its pyramids from the
 * region index alone, 128–640 blocks into each 1024-block region. Here the band sits thousands of blocks
 * out and the track runs along {@code z ≈ 0}, so a faithful port would show nothing (and put a wall inside
 * the corridor). Instead a wall crosses the track at every 1024-block region boundary along X, and each
 * region's pyramid keeps the original X rule but is centred {@link #PYRAMID_MIN_DIST}–{@link #PYRAMID_MAX_DIST}
 * blocks either side of the track, from a world-seeded roll. The pyramid shape itself is unchanged.</p>
 *
 * <p>Works in Infdev's own Y ({@code 0..128}, water up to and including {@link #SEA_LEVEL}) and writes one
 * block lower into the old-world column, so its top water lands on old {@code y = 63} like Beta's.
 * Immutable and thread-safe.</p>
 */
final class Infdev227Terrain {

    /** Infdev 227's sea level: water fills every open block at or below it. */
    static final int SEA_LEVEL = 64;
    /** Landmark grid along X — the original's region size. */
    static final int REGION = 1024;
    /** Nearest / farthest a pyramid centre sits from the track (exclusive max). */
    static final int PYRAMID_MIN_DIST = 40;
    static final int PYRAMID_MAX_DIST = 200;
    /** Pyramid apex height, in Infdev Y. */
    static final int PYRAMID_APEX = 127;

    private final long seed;
    private final PerlinOctaveNoise noiseA;
    private final PerlinOctaveNoise noiseB;
    private final PerlinOctaveNoise noiseC;
    private final PerlinOctaveNoise noiseD;
    private final PerlinOctaveNoise noiseE;
    private final PerlinOctaveNoise noiseF;
    private final PerlinOctaveNoise forestNoise;

    Infdev227Terrain(long seed) {
        this.seed = seed;
        // Construction order is load-bearing: each stack consumes the shared Random as the original did.
        Random random = new Random(seed);
        this.noiseA = new PerlinOctaveNoise(random, 16);
        this.noiseB = new PerlinOctaveNoise(random, 16);
        this.noiseC = new PerlinOctaveNoise(random, 8);
        this.noiseD = new PerlinOctaveNoise(random, 4);
        this.noiseE = new PerlinOctaveNoise(random, 4);
        this.noiseF = new PerlinOctaveNoise(random, 5);
        this.forestNoise = new PerlinOctaveNoise(random, 5);
    }

    PerlinOctaveNoise forestNoise() {
        return forestNoise;
    }

    /** A region's pyramid centre, {@code {x, z}} in world blocks. */
    record Pyramid(int x, int z) {}

    Pyramid pyramid(int regionX) {
        Random rand = new Random(seed * 0x9E3779B97F4A7C15L + (long) regionX * 13871L);
        int x = (regionX << 10) + 128 + rand.nextInt(512);
        int dist = PYRAMID_MIN_DIST + rand.nextInt(PYRAMID_MAX_DIST - PYRAMID_MIN_DIST);
        return new Pyramid(x, rand.nextBoolean() ? dist : -dist);
    }

    /** True where an obsidian wall stands: one block thick, on every region boundary along X. */
    static boolean isWall(int worldX) {
        return Math.floorMod(worldX, REGION) == 0;
    }

    /** Terrain height (Infdev Y of the top stone) at world {@code (x, z)}. */
    int height(int x, int z) {
        float a = (float) (noiseA.sample3D(x / 0.03125F, 0.0D, z / 0.03125F)
                - noiseB.sample3D(x / 0.015625F, 0.0D, z / 0.015625F)) / 512.0F / 4.0F;
        float b = (float) noiseE.sample2D(x / 4.0F, z / 4.0F);
        float c = (float) noiseF.sample2D(x / 8.0F, z / 8.0F) / 8.0F;
        b = b > 0.0F
                ? (float) (noiseC.sample2D(x * 0.25714284F * 2.0F, z * 0.25714284F * 2.0F) * c / 4.0D)
                : (float) (noiseD.sample2D(x * 0.25714284F, z * 0.25714284F) * c);
        int height = (int) (a + SEA_LEVEL + b);
        if ((float) noiseE.sample2D(x, z) < 0.0F) {
            height = height / 2 << 1;
            if ((float) noiseE.sample2D(x / 5, z / 5) < 0.0F) height++;
        }
        return height;
    }

    /** The old-world column for chunk {@code (chunkX, chunkZ)}, in {@link BetaTerrain#index} layout. */
    byte[] generate(int chunkX, int chunkZ) {
        byte[] blocks = new byte[16 * 16 * BetaTerrain.HEIGHT];
        int startX = chunkX << 4;
        int startZ = chunkZ << 4;
        Pyramid pyramid = pyramid(Math.floorDiv(startX, REGION));
        for (int lx = 0; lx < 16; lx++) {
            int x = startX + lx;
            boolean wall = isWall(x);
            for (int lz = 0; lz < 16; lz++) {
                int z = startZ + lz;
                int height = height(x, z);
                int d = Math.max(Math.abs(x - pyramid.x()), Math.abs(z - pyramid.z()));
                int pyramidTop = Math.max(PYRAMID_APEX - d, height);
                for (int oldY = 0; oldY < BetaTerrain.HEIGHT; oldY++) {
                    int y = oldY + 1;
                    byte block = BetaBlocks.AIR;
                    if (wall && y <= height + 2) block = BetaBlocks.OBSIDIAN;
                    else if (y <= height) block = BetaBlocks.STONE;
                    else if (y <= SEA_LEVEL) block = BetaBlocks.WATER;
                    if (y <= pyramidTop && (block == BetaBlocks.AIR || block == BetaBlocks.WATER)) {
                        block = BetaBlocks.BRICKS;
                    }
                    blocks[BetaTerrain.index(lx, oldY, lz)] = block;
                }
            }
        }
        buildSurface(chunkX, chunkZ, blocks);
        return blocks;
    }

    /** Grass (dirt below sea level) over one dirt layer, on stone only; bedrock at the floor. */
    private static void buildSurface(int chunkX, int chunkZ, byte[] blocks) {
        Random bedrockRand = InfdevTerrain.surfaceRandom(chunkX, chunkZ);
        int grassFrom = SEA_LEVEL - 1; // Infdev Y 64, in old-world Y
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int run = 0;
                for (int y = BetaTerrain.HEIGHT - 1; y >= 0; y--) {
                    int i = BetaTerrain.index(x, y, z);
                    if (y <= bedrockRand.nextInt(5)) {
                        blocks[i] = BetaBlocks.BEDROCK;
                        continue;
                    }
                    byte b = blocks[i];
                    if (b == BetaBlocks.AIR || b == BetaBlocks.WATER) {
                        run = 0;
                        continue;
                    }
                    if (b != BetaBlocks.STONE) continue;
                    if (run == 0) blocks[i] = y >= grassFrom ? BetaBlocks.GRASS : BetaBlocks.DIRT;
                    else if (run == 1) blocks[i] = BetaBlocks.DIRT;
                    run++;
                }
            }
        }
    }
}
