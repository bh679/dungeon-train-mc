package games.brennan.dungeontrain.worldgen.legacy.indev;

import games.brennan.dungeontrain.worldgen.legacy.beta.BetaFeatures;
import games.brennan.dungeontrain.worldgen.legacy.beta.BetaTrees;
import games.brennan.dungeontrain.worldgen.legacy.beta.BetaWorld;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Random;

/**
 * Decoration for an Indev floating chunk: ores in the island stone, then small oaks, flowers and mushrooms
 * on the island tops — Indev's own plant-life, with the old Beta-era tree and patch shapes. Nothing liquid:
 * a Floating level has no water or lava, so no lakes or springs.
 *
 * <p>The islands are stacked, so every surface pick starts at a random height and settles down onto the
 * first grass below it; that spreads the decoration over every layer rather than only the top one. Like the
 * Beta populator, the work area is offset {@code +8} into the neighbouring chunks.</p>
 */
public final class IndevFloatingPopulator {

    private static final int TREE_TRIES = 6;
    private static final int FLOWER_PATCHES = 2;

    private IndevFloatingPopulator() {}

    /** Decorate chunk {@code (chunkX, chunkZ)}; {@code world} must span the level's full height. */
    public static void populate(BetaWorld world, long seed, int chunkX, int chunkZ) {
        int height = world.height();
        int bx = chunkX * 16;
        int bz = chunkZ * 16;
        Random rand = new Random(seed);
        long mulX = rand.nextLong() / 2L * 2L + 1L;
        long mulZ = rand.nextLong() / 2L * 2L + 1L;
        rand.setSeed((long) chunkX * mulX + (long) chunkZ * mulZ ^ seed);

        veins(world, rand, bx, bz, 20, height, 16, Blocks.COAL_ORE.defaultBlockState());
        veins(world, rand, bx, bz, 20, height, 8, Blocks.IRON_ORE.defaultBlockState());
        veins(world, rand, bx, bz, 4, height, 8, Blocks.GOLD_ORE.defaultBlockState());
        veins(world, rand, bx, bz, 2, height, 7, Blocks.DIAMOND_ORE.defaultBlockState());

        for (int i = 0; i < TREE_TRIES; i++) {
            int x = bx + rand.nextInt(16) + 8;
            int z = bz + rand.nextInt(16) + 8;
            int y = grassBelow(world, x, rand.nextInt(height), z);
            if (y > 0) BetaTrees.grow(BetaTrees.Kind.OAK, world, rand, x, y, z);
        }
        for (int i = 0; i < FLOWER_PATCHES; i++) {
            patch(world, rand, bx, bz, rand.nextBoolean() ? Blocks.DANDELION : Blocks.POPPY);
        }
        if (rand.nextInt(4) == 0) patch(world, rand, bx, bz, Blocks.BROWN_MUSHROOM);
        if (rand.nextInt(8) == 0) patch(world, rand, bx, bz, Blocks.RED_MUSHROOM);
    }

    private static void veins(BetaWorld world, Random rand, int bx, int bz, int count, int maxY, int size,
                              BlockState ore) {
        for (int i = 0; i < count; i++) {
            BetaFeatures.vein(world, rand, bx + rand.nextInt(16), rand.nextInt(maxY), bz + rand.nextInt(16),
                    size, ore, Blocks.STONE);
        }
    }

    private static void patch(BetaWorld world, Random rand, int bx, int bz, Block plant) {
        int x = bx + rand.nextInt(16) + 8;
        int z = bz + rand.nextInt(16) + 8;
        int y = grassBelow(world, x, rand.nextInt(world.height()), z);
        if (y > 0) BetaFeatures.flowers(world, rand, x, y, z, plant.defaultBlockState());
    }

    /**
     * The Y just above the first grass block at or below {@code startY} in column {@code (x, z)} with air
     * over it, or {@code -1} when the column is empty below {@code startY}.
     */
    static int grassBelow(BetaWorld world, int x, int startY, int z) {
        for (int y = startY; y > 0; y--) {
            if (world.is(x, y - 1, z, Blocks.GRASS_BLOCK) && world.isAir(x, y, z)) return y;
        }
        return -1;
    }
}
