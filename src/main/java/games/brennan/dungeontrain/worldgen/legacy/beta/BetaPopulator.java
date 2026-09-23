package games.brennan.dungeontrain.worldgen.legacy.beta;

import games.brennan.dungeontrain.worldgen.legacy.noise.PerlinOctaveNoise;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Random;

/**
 * Beta 1.7.3's populate step for one chunk, in Beta's order and with Beta's per-chunk
 * {@link java.util.Random}: lakes, dungeons, clay, dirt/gravel/ore veins, trees (count from the forest
 * noise plus a per-biome bonus), flowers, grass, dead bushes, mushrooms, reeds, pumpkins, cacti,
 * springs, then snow cover by temperature.
 *
 * <p>Like Beta, the work area is the 16×16 square offset {@code +8} into the neighbouring chunks, so
 * features straddle chunk borders instead of stopping at them. Every write stays within the
 * decorating chunk and its east/south neighbours — inside the worldgen region's writable window.</p>
 */
public final class BetaPopulator {

    private static final BlockState WATER = Blocks.WATER.defaultBlockState();
    private static final BlockState LAVA = Blocks.LAVA.defaultBlockState();

    private BetaPopulator() {}

    /** Beta's own populate: biome from the climate, snow by temperature. */
    public static void populate(BetaWorld world, BetaTerrain terrain, int chunkX, int chunkZ) {
        BetaBiome biome = terrain.climate().biome(chunkX * 16 + 16, chunkZ * 16 + 16);
        populate(world, terrain.seed(), terrain.forestNoise(), biome, terrain.climate(), chunkX, chunkZ);
    }

    /**
     * The shared populate step for a Beta-family generator (Beta, Sky): {@code biome} drives trees and
     * plants; {@code snowClimate} lays snow by temperature, or {@code null} for none (Sky).
     */
    public static void populate(BetaWorld world, long seed, PerlinOctaveNoise forestNoise, BetaBiome biome,
                                BetaClimate snowClimate, int chunkX, int chunkZ) {
        int bx = chunkX * 16;
        int bz = chunkZ * 16;
        Random rand = new Random(seed);
        long mulX = rand.nextLong() / 2L * 2L + 1L;
        long mulZ = rand.nextLong() / 2L * 2L + 1L;
        rand.setSeed((long) chunkX * mulX + (long) chunkZ * mulZ ^ seed);

        if (rand.nextInt(4) == 0) {
            BetaFeatures.lake(world, rand, bx + rand.nextInt(16) + 8, rand.nextInt(128), bz + rand.nextInt(16) + 8, WATER);
        }
        if (rand.nextInt(8) == 0) {
            int x = bx + rand.nextInt(16) + 8;
            int y = rand.nextInt(rand.nextInt(120) + 8);
            int z = bz + rand.nextInt(16) + 8;
            if (y < BetaTerrain.SEA_LEVEL || rand.nextInt(10) == 0) BetaFeatures.lake(world, rand, x, y, z, LAVA);
        }
        for (int i = 0; i < 8; i++) {
            BetaDungeon.place(world, rand, bx + rand.nextInt(16) + 8, rand.nextInt(128), bz + rand.nextInt(16) + 8);
        }
        for (int i = 0; i < 10; i++) {
            BetaFeatures.clay(world, rand, bx + rand.nextInt(16), rand.nextInt(128), bz + rand.nextInt(16), 32);
        }
        veins(world, rand, bx, bz, 20, 128, 32, Blocks.DIRT.defaultBlockState());
        veins(world, rand, bx, bz, 10, 128, 32, Blocks.GRAVEL.defaultBlockState());
        veins(world, rand, bx, bz, 20, 128, 16, Blocks.COAL_ORE.defaultBlockState());
        veins(world, rand, bx, bz, 20, 64, 8, Blocks.IRON_ORE.defaultBlockState());
        veins(world, rand, bx, bz, 2, 32, 8, Blocks.GOLD_ORE.defaultBlockState());
        veins(world, rand, bx, bz, 8, 16, 7, Blocks.REDSTONE_ORE.defaultBlockState());
        veins(world, rand, bx, bz, 1, 16, 7, Blocks.DIAMOND_ORE.defaultBlockState());
        BetaFeatures.vein(world, rand, bx + rand.nextInt(16), rand.nextInt(16) + rand.nextInt(16), bz + rand.nextInt(16),
                6, Blocks.LAPIS_ORE.defaultBlockState(), Blocks.STONE);

        trees(world, rand, forestNoise, biome, bx, bz);
        plants(world, rand, biome, bx, bz);
        springs(world, rand, bx, bz);
        if (snowClimate != null) snow(world, snowClimate, bx, bz);
    }

    private static void veins(BetaWorld world, Random rand, int bx, int bz, int count, int maxY, int size,
                              BlockState ore) {
        for (int i = 0; i < count; i++) {
            BetaFeatures.vein(world, rand, bx + rand.nextInt(16), rand.nextInt(maxY), bz + rand.nextInt(16),
                    size, ore, Blocks.STONE);
        }
    }

    private static void trees(BetaWorld world, Random rand, PerlinOctaveNoise forestNoise, BetaBiome biome,
                              int bx, int bz) {
        int noiseCount = (int) ((forestNoise.sample2D(bx * 0.5D, bz * 0.5D) / 8.0D
                + rand.nextDouble() * 4.0D + 4.0D) / 3.0D);
        int count = rand.nextInt(10) == 0 ? 1 : 0;
        count += switch (biome) {
            case FOREST, RAINFOREST, TAIGA -> noiseCount + 5;
            case SEASONAL_FOREST -> noiseCount + 2;
            case DESERT, TUNDRA, PLAINS -> -20;
            default -> 0;
        };
        for (int i = 0; i < count; i++) {
            int x = bx + rand.nextInt(16) + 8;
            int z = bz + rand.nextInt(16) + 8;
            BetaTrees.Kind kind = BetaTrees.pick(biome, rand);
            BetaTrees.grow(kind, world, rand, x, world.heightValue(x, z), z);
        }
    }

    private static void plants(BetaWorld world, Random rand, BetaBiome biome, int bx, int bz) {
        int flowers = switch (biome) {
            case FOREST, TAIGA -> 2;
            case SEASONAL_FOREST -> 4;
            case PLAINS -> 3;
            default -> 0;
        };
        for (int i = 0; i < flowers; i++) {
            BetaFeatures.flowers(world, rand, bx + rand.nextInt(16) + 8, rand.nextInt(128), bz + rand.nextInt(16) + 8,
                    Blocks.DANDELION.defaultBlockState());
        }
        int grass = switch (biome) {
            case FOREST, SEASONAL_FOREST -> 2;
            case RAINFOREST, PLAINS -> 10;
            case TAIGA -> 1;
            default -> 0;
        };
        for (int i = 0; i < grass; i++) {
            BlockState plant = biome == BetaBiome.RAINFOREST && rand.nextInt(3) != 0
                    ? Blocks.FERN.defaultBlockState() : Blocks.SHORT_GRASS.defaultBlockState();
            BetaFeatures.tallGrass(world, rand, bx + rand.nextInt(16) + 8, rand.nextInt(128), bz + rand.nextInt(16) + 8, plant);
        }
        int deadBushes = biome == BetaBiome.DESERT ? 2 : 0;
        for (int i = 0; i < deadBushes; i++) {
            BetaFeatures.deadBush(world, rand, bx + rand.nextInt(16) + 8, rand.nextInt(128), bz + rand.nextInt(16) + 8);
        }
        if (rand.nextInt(2) == 0) {
            BetaFeatures.flowers(world, rand, bx + rand.nextInt(16) + 8, rand.nextInt(128), bz + rand.nextInt(16) + 8,
                    Blocks.POPPY.defaultBlockState());
        }
        if (rand.nextInt(4) == 0) {
            BetaFeatures.flowers(world, rand, bx + rand.nextInt(16) + 8, rand.nextInt(128), bz + rand.nextInt(16) + 8,
                    Blocks.BROWN_MUSHROOM.defaultBlockState());
        }
        if (rand.nextInt(8) == 0) {
            BetaFeatures.flowers(world, rand, bx + rand.nextInt(16) + 8, rand.nextInt(128), bz + rand.nextInt(16) + 8,
                    Blocks.RED_MUSHROOM.defaultBlockState());
        }
        for (int i = 0; i < 10; i++) {
            BetaFeatures.reeds(world, rand, bx + rand.nextInt(16) + 8, rand.nextInt(128), bz + rand.nextInt(16) + 8);
        }
        if (rand.nextInt(32) == 0) {
            BetaFeatures.pumpkins(world, rand, bx + rand.nextInt(16) + 8, rand.nextInt(128), bz + rand.nextInt(16) + 8);
        }
        int cacti = biome == BetaBiome.DESERT ? 10 : 0;
        for (int i = 0; i < cacti; i++) {
            BetaFeatures.cactus(world, rand, bx + rand.nextInt(16) + 8, rand.nextInt(128), bz + rand.nextInt(16) + 8);
        }
    }

    private static void springs(BetaWorld world, Random rand, int bx, int bz) {
        for (int i = 0; i < 50; i++) {
            BetaFeatures.spring(world, bx + rand.nextInt(16) + 8, rand.nextInt(rand.nextInt(120) + 8),
                    bz + rand.nextInt(16) + 8, WATER);
        }
        for (int i = 0; i < 20; i++) {
            BetaFeatures.spring(world, bx + rand.nextInt(16) + 8, rand.nextInt(rand.nextInt(rand.nextInt(112) + 8) + 8),
                    bz + rand.nextInt(16) + 8, LAVA);
        }
    }

    /** Snow on exposed solid ground wherever Beta's altitude-adjusted temperature drops below 0.5. */
    private static void snow(BetaWorld world, BetaClimate climate, int bx, int bz) {
        BlockState snow = Blocks.SNOW.defaultBlockState();
        for (int x = bx + 8; x < bx + 24; x++) {
            for (int z = bz + 8; z < bz + 24; z++) {
                int y = world.topSolidOrLiquid(x, z);
                if (y <= 0 || y >= BetaTerrain.HEIGHT) continue;
                double t = climate.temperature(x, z) - (y - 64) / 64.0D * 0.3D;
                if (t < 0.5D && world.isAir(x, y, z) && world.isSolid(x, y - 1, z)
                        && !world.is(x, y - 1, z, Blocks.ICE)) {
                    world.set(x, y, z, snow);
                }
            }
        }
    }
}
