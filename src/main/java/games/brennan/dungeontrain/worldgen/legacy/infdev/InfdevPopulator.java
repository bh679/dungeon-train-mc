/*
 * Decoration counts adapted from Moderner Beta (https://github.com/Nostalgica-Reverie/moderner-beta),
 * world/biome/ModernBetaBiomeFeatures.java (addInfdev*Features) and
 * world/feature/placement/noise/NoiseBasedCountInfdev415/420/611.java.
 * Copyright (c) 2021 B3spectacled. MIT License — see THIRD_PARTY_NOTICES.md.
 */
package games.brennan.dungeontrain.worldgen.legacy.infdev;

import games.brennan.dungeontrain.worldgen.legacy.beta.BetaFeatures;
import games.brennan.dungeontrain.worldgen.legacy.beta.BetaTrees;
import games.brennan.dungeontrain.worldgen.legacy.beta.BetaWorld;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Random;

/**
 * Infdev's decoration for one chunk. Infdev had no lakes, dungeons, springs or ore generation, so this
 * is only the plant life each snapshot grew: 20100227 scattered dandelions over bare grass; 415 and 420
 * grew mostly big oaks (one in ten a small oak), 611 only small oaks, each with its own noise-driven
 * per-chunk count; every version adds the odd tuft of grass and mushrooms.
 *
 * <p>Like the Beta populator, the work area is the 16×16 square offset {@code +8} into the neighbouring
 * chunks, inside the worldgen region's writable window.</p>
 */
public final class InfdevPopulator {

    private static final BlockState DANDELION = Blocks.DANDELION.defaultBlockState();
    private static final BlockState GRASS = Blocks.SHORT_GRASS.defaultBlockState();

    private InfdevPopulator() {}

    public static void populate(BetaWorld world, InfdevTerrain terrain, InfdevVersion version,
                                int chunkX, int chunkZ) {
        long seed = terrain.seed();
        int bx = chunkX * 16;
        int bz = chunkZ * 16;
        Random rand = new Random(seed);
        long mulX = rand.nextLong() / 2L * 2L + 1L;
        long mulZ = rand.nextLong() / 2L * 2L + 1L;
        rand.setSeed((long) chunkX * mulX + (long) chunkZ * mulZ ^ seed);

        if (version == InfdevVersion.V227) {
            int dandelions = rand.nextInt(11);
            for (int i = 0; i < dandelions; i++) dandelion(world, rand, bx, bz);
        } else {
            trees(world, rand, terrain, version, bx, bz, chunkX, chunkZ);
        }
        if (rand.nextFloat() < 0.05F) {
            int x = bx + rand.nextInt(16) + 8;
            int z = bz + rand.nextInt(16) + 8;
            BetaFeatures.tallGrass(world, rand, x, world.heightValue(x, z), z, GRASS);
        }
        if (rand.nextInt(4) == 0) mushrooms(world, rand, bx, bz, Blocks.BROWN_MUSHROOM.defaultBlockState());
        if (rand.nextInt(8) == 0) mushrooms(world, rand, bx, bz, Blocks.RED_MUSHROOM.defaultBlockState());
    }

    /** 227's single dandelion on the surface (a simple-block feature, not a patch). */
    private static void dandelion(BetaWorld world, Random rand, int bx, int bz) {
        int x = bx + rand.nextInt(16) + 8;
        int z = bz + rand.nextInt(16) + 8;
        int y = world.heightValue(x, z);
        if (world.isAir(x, y, z) && world.is(x, y - 1, z, Blocks.GRASS_BLOCK)) world.set(x, y, z, DANDELION);
    }

    private static void mushrooms(BetaWorld world, Random rand, int bx, int bz, BlockState mushroom) {
        BetaFeatures.flowers(world, rand, bx + rand.nextInt(16) + 8, rand.nextInt(128), bz + rand.nextInt(16) + 8,
                mushroom);
    }

    private static void trees(BetaWorld world, Random rand, InfdevTerrain terrain, InfdevVersion version,
                              int bx, int bz, int chunkX, int chunkZ) {
        int count = treeCount(terrain, version, chunkX, chunkZ, rand);
        for (int i = 0; i < count; i++) {
            int x = bx + rand.nextInt(16) + 8;
            int z = bz + rand.nextInt(16) + 8;
            BetaTrees.Kind kind = version == InfdevVersion.V611 || rand.nextInt(10) == 0
                    ? BetaTrees.Kind.OAK : BetaTrees.Kind.FANCY;
            BetaTrees.grow(kind, world, rand, x, world.heightValue(x, z), z);
        }
    }

    /** The snapshot's noise-based per-chunk tree count ({@code NoiseBasedCountInfdev*}). */
    static int treeCount(InfdevTerrain terrain, InfdevVersion version, int chunkX, int chunkZ, Random rand) {
        double startX = chunkX << 4;
        double startZ = chunkZ << 4;
        return switch (version) {
            case V415 -> Math.max(0, (int) terrain.forestNoise(version).sample2D(startX * 0.25D, startZ * 0.25D) << 3);
            case V420 -> Math.max(0, (int) (terrain.forestNoise(version).sample2D(startX * 0.05D, startZ * 0.05D)
                    - rand.nextDouble()));
            case V611 -> Math.max(0, (int) (terrain.forestNoise(version).sample2D(startX * 0.5D, startZ * 0.5D) / 8.0D
                    + rand.nextDouble() * 4.0D + 4.0D));
            case V227 -> 0;
        };
    }
}
