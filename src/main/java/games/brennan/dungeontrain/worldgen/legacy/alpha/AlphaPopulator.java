package games.brennan.dungeontrain.worldgen.legacy.alpha;

import games.brennan.dungeontrain.worldgen.legacy.LegacyChunkWriter;
import games.brennan.dungeontrain.worldgen.legacy.beta.BetaDungeon;
import games.brennan.dungeontrain.worldgen.legacy.beta.BetaFeatures;
import games.brennan.dungeontrain.worldgen.legacy.beta.BetaTrees;
import games.brennan.dungeontrain.worldgen.legacy.beta.BetaWorld;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Random;

/**
 * Alpha 1.1.2's populate step for one chunk, in Alpha's order and with its per-chunk
 * {@link java.util.Random} (seeded exactly as Beta's): dungeons, clay, dirt/gravel/ore veins, trees
 * (count from the forest noise — Alpha had no biomes, so every chunk may be forest), flowers, mushrooms,
 * reeds, cacti and springs; in winter mode, snow over every exposed surface.
 *
 * <p>Reuses the Beta decorators, which Beta inherited from Alpha unchanged. What Alpha did <em>not</em>
 * have, and so is left out here: lakes, lapis, tall grass and ferns, dead bushes, pumpkins, birch and
 * conifer trees, and any biome-dependent counts. The dungeon chest keeps Beta's loot list (a few items
 * newer than Alpha 1.1.2).</p>
 *
 * <p>Like the original, the work area is the 16×16 square offset {@code +8} into the neighbouring
 * chunks; every write stays within the decorating chunk and its east/south neighbours.</p>
 */
public final class AlphaPopulator {

    private static final BlockState WATER = Blocks.WATER.defaultBlockState();
    private static final BlockState LAVA = Blocks.LAVA.defaultBlockState();

    private AlphaPopulator() {}

    public static void populate(WorldGenLevel level, AlphaTerrain terrain, int chunkX, int chunkZ, boolean winter) {
        BetaWorld world = new BetaWorld(level, LegacyChunkWriter.Y_OFFSET);
        long seed = terrain.seed();
        int bx = chunkX * 16;
        int bz = chunkZ * 16;
        Random rand = new Random(seed);
        long mulX = rand.nextLong() / 2L * 2L + 1L;
        long mulZ = rand.nextLong() / 2L * 2L + 1L;
        rand.setSeed((long) chunkX * mulX + (long) chunkZ * mulZ ^ seed);

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

        trees(world, rand, terrain, bx, bz);
        plants(world, rand, bx, bz);
        springs(world, rand, bx, bz);
        if (winter) BetaFeatures.snowCover(world, bx, bz, (x, y, z) -> true);
    }

    private static void veins(BetaWorld world, Random rand, int bx, int bz, int count, int maxY, int size,
                              BlockState ore) {
        for (int i = 0; i < count; i++) {
            BetaFeatures.vein(world, rand, bx + rand.nextInt(16), rand.nextInt(maxY), bz + rand.nextInt(16),
                    size, ore, Blocks.STONE);
        }
    }

    /** Alpha's tree count: forest noise (never negative), plus one on a 1-in-10; big oak 1-in-10. */
    private static void trees(BetaWorld world, Random rand, AlphaTerrain terrain, int bx, int bz) {
        int count = (int) ((terrain.forestNoise().sample2D(bx * 0.5D, bz * 0.5D) / 8.0D
                + rand.nextDouble() * 4.0D + 4.0D) / 3.0D);
        if (count < 0) count = 0;
        if (rand.nextInt(10) == 0) count++;
        for (int i = 0; i < count; i++) {
            int x = bx + rand.nextInt(16) + 8;
            int z = bz + rand.nextInt(16) + 8;
            BetaTrees.Kind kind = rand.nextInt(10) == 0 ? BetaTrees.Kind.FANCY : BetaTrees.Kind.OAK;
            BetaTrees.grow(kind, world, rand, x, world.heightValue(x, z), z);
        }
    }

    private static void plants(BetaWorld world, Random rand, int bx, int bz) {
        for (int i = 0; i < 2; i++) {
            BetaFeatures.flowers(world, rand, bx + rand.nextInt(16) + 8, rand.nextInt(128), bz + rand.nextInt(16) + 8,
                    Blocks.DANDELION.defaultBlockState());
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
        for (int i = 0; i < 2; i++) {
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
}
