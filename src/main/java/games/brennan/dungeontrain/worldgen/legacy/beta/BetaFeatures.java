package games.brennan.dungeontrain.worldgen.legacy.beta;

import games.brennan.dungeontrain.worldgen.legacy.noise.LegacyMath;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarvedPumpkinBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.Direction;
import net.minecraft.world.level.material.Fluids;

import java.util.Random;

/**
 * Beta 1.7.3's small decorators, in Beta coordinates on a {@link BetaWorld}: lakes, ore/dirt/gravel
 * veins and clay patches, flowers and grass, dead bushes, reeds, pumpkins, cacti and springs. Dungeons
 * live in {@link BetaDungeon}; trees in {@link BetaTrees}.
 */
final class BetaFeatures {

    private BetaFeatures() {}

    // ---- lakes ------------------------------------------------------------------------

    /** A water or lava lake blob in a 16×8×16 box around {@code (x, y, z)}. */
    static boolean lake(BetaWorld world, Random rand, int x, int y, int z, BlockState fluid) {
        x -= 8;
        z -= 8;
        while (y > 0 && world.isAir(x, y, z)) y--;
        y -= 4;
        boolean[] shape = new boolean[2048];
        int blobs = rand.nextInt(4) + 4;
        for (int i = 0; i < blobs; i++) {
            double sx = rand.nextDouble() * 6.0D + 3.0D;
            double sy = rand.nextDouble() * 4.0D + 2.0D;
            double sz = rand.nextDouble() * 6.0D + 3.0D;
            double cx = rand.nextDouble() * (16.0D - sx - 2.0D) + 1.0D + sx / 2.0D;
            double cy = rand.nextDouble() * (8.0D - sy - 4.0D) + 2.0D + sy / 2.0D;
            double cz = rand.nextDouble() * (16.0D - sz - 2.0D) + 1.0D + sz / 2.0D;
            for (int lx = 1; lx < 15; lx++) {
                for (int lz = 1; lz < 15; lz++) {
                    for (int ly = 1; ly < 7; ly++) {
                        double dx = (lx - cx) / (sx / 2.0D);
                        double dy = (ly - cy) / (sy / 2.0D);
                        double dz = (lz - cz) / (sz / 2.0D);
                        if (dx * dx + dy * dy + dz * dz < 1.0D) shape[(lx * 16 + lz) * 8 + ly] = true;
                    }
                }
            }
        }
        boolean lava = fluid.is(Blocks.LAVA);
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                for (int ly = 0; ly < 8; ly++) {
                    if (!lakeEdge(shape, lx, ly, lz)) continue;
                    if (ly >= 4 && world.isLiquid(x + lx, y + ly, z + lz)) return false;
                    if (ly < 4 && !world.isSolid(x + lx, y + ly, z + lz)
                            && !world.get(x + lx, y + ly, z + lz).is(fluid.getBlock())) return false;
                }
            }
        }
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                for (int ly = 0; ly < 8; ly++) {
                    if (shape[(lx * 16 + lz) * 8 + ly]) {
                        world.set(x + lx, y + ly, z + lz, ly >= 4 ? Blocks.AIR.defaultBlockState() : fluid);
                    }
                }
            }
        }
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                for (int ly = 4; ly < 8; ly++) {
                    if (shape[(lx * 16 + lz) * 8 + ly] && world.is(x + lx, y + ly - 1, z + lz, Blocks.DIRT)) {
                        world.set(x + lx, y + ly - 1, z + lz, Blocks.GRASS_BLOCK.defaultBlockState());
                    }
                }
            }
        }
        if (lava) {
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    for (int ly = 0; ly < 8; ly++) {
                        if (lakeEdge(shape, lx, ly, lz) && (ly < 4 || rand.nextInt(2) != 0)
                                && world.isSolid(x + lx, y + ly, z + lz)) {
                            world.set(x + lx, y + ly, z + lz, Blocks.STONE.defaultBlockState());
                        }
                    }
                }
            }
        }
        return true;
    }

    private static boolean lakeEdge(boolean[] s, int x, int y, int z) {
        if (s[(x * 16 + z) * 8 + y]) return false;
        return x < 15 && s[((x + 1) * 16 + z) * 8 + y] || x > 0 && s[((x - 1) * 16 + z) * 8 + y]
                || z < 15 && s[(x * 16 + z + 1) * 8 + y] || z > 0 && s[(x * 16 + (z - 1)) * 8 + y]
                || y < 7 && s[(x * 16 + z) * 8 + y + 1] || y > 0 && s[(x * 16 + z) * 8 + (y - 1)];
    }

    // ---- veins ------------------------------------------------------------------------

    /** A vein of {@code size} blocks of {@code ore} replacing {@code target}. */
    static void vein(BetaWorld world, Random rand, int x, int y, int z, int size, BlockState ore, Block target) {
        float angle = rand.nextFloat() * 3.141593F;
        double x0 = (float) (x + 8) + LegacyMath.sin(angle) * size / 8.0F;
        double x1 = (float) (x + 8) - LegacyMath.sin(angle) * size / 8.0F;
        double z0 = (float) (z + 8) + LegacyMath.cos(angle) * size / 8.0F;
        double z1 = (float) (z + 8) - LegacyMath.cos(angle) * size / 8.0F;
        double y0 = y + rand.nextInt(3) - 2;
        double y1 = y + rand.nextInt(3) - 2;
        for (int i = 0; i <= size; i++) {
            double cx = x0 + (x1 - x0) * i / size;
            double cy = y0 + (y1 - y0) * i / size;
            double cz = z0 + (z1 - z0) * i / size;
            double spread = rand.nextDouble() * size / 16.0D;
            double rh = (double) (LegacyMath.sin(i * 3.141593F / size) + 1.0F) * spread + 1.0D;
            double rv = (double) (LegacyMath.sin(i * 3.141593F / size) + 1.0F) * spread + 1.0D;
            int minX = LegacyMath.floor(cx - rh / 2.0D);
            int minY = LegacyMath.floor(cy - rv / 2.0D);
            int minZ = LegacyMath.floor(cz - rh / 2.0D);
            int maxX = LegacyMath.floor(cx + rh / 2.0D);
            int maxY = LegacyMath.floor(cy + rv / 2.0D);
            int maxZ = LegacyMath.floor(cz + rh / 2.0D);
            for (int bx = minX; bx <= maxX; bx++) {
                double dx = (bx + 0.5D - cx) / (rh / 2.0D);
                if (dx * dx >= 1.0D) continue;
                for (int by = minY; by <= maxY; by++) {
                    double dy = (by + 0.5D - cy) / (rv / 2.0D);
                    if (dx * dx + dy * dy >= 1.0D) continue;
                    for (int bz = minZ; bz <= maxZ; bz++) {
                        double dz = (bz + 0.5D - cz) / (rh / 2.0D);
                        if (dx * dx + dy * dy + dz * dz < 1.0D && world.is(bx, by, bz, target)) {
                            world.set(bx, by, bz, ore);
                        }
                    }
                }
            }
        }
    }

    /** A clay patch: only starts in water, and turns sand to clay. */
    static void clay(BetaWorld world, Random rand, int x, int y, int z, int size) {
        if (!world.is(x, y, z, Blocks.WATER)) return;
        vein(world, rand, x, y, z, size, Blocks.CLAY.defaultBlockState(), Blocks.SAND);
    }

    // ---- plants -----------------------------------------------------------------------

    private static boolean soil(BlockState s) {
        return s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT) || s.is(Blocks.FARMLAND);
    }

    /** Flowers (on grass/dirt) and mushrooms (on any solid block, shaded): 64 tries around the start. */
    static void flowers(BetaWorld world, Random rand, int x, int y, int z, BlockState plant) {
        boolean mushroom = plant.is(Blocks.BROWN_MUSHROOM) || plant.is(Blocks.RED_MUSHROOM);
        for (int i = 0; i < 64; i++) {
            int px = x + rand.nextInt(8) - rand.nextInt(8);
            int py = y + rand.nextInt(4) - rand.nextInt(4);
            int pz = z + rand.nextInt(8) - rand.nextInt(8);
            if (!world.isAir(px, py, pz)) continue;
            boolean stays = mushroom
                    ? world.isOpaque(px, py - 1, pz) && py < world.heightValue(px, pz)
                    : soil(world.get(px, py - 1, pz));
            if (stays) world.set(px, py, pz, plant);
        }
    }

    /** Tall grass / ferns: settle onto the ground, then 128 tries. */
    static void tallGrass(BetaWorld world, Random rand, int x, int y, int z, BlockState plant) {
        while (y > 0 && world.isAirOrLeaves(x, y, z)) y--;
        for (int i = 0; i < 128; i++) {
            int px = x + rand.nextInt(8) - rand.nextInt(8);
            int py = y + rand.nextInt(4) - rand.nextInt(4);
            int pz = z + rand.nextInt(8) - rand.nextInt(8);
            if (world.isAir(px, py, pz) && soil(world.get(px, py - 1, pz))) world.set(px, py, pz, plant);
        }
    }

    /** Dead bushes on sand: settle onto the ground, then 4 tries. */
    static void deadBush(BetaWorld world, Random rand, int x, int y, int z) {
        while (y > 0 && world.isAirOrLeaves(x, y, z)) y--;
        for (int i = 0; i < 4; i++) {
            int px = x + rand.nextInt(8) - rand.nextInt(8);
            int py = y + rand.nextInt(4) - rand.nextInt(4);
            int pz = z + rand.nextInt(8) - rand.nextInt(8);
            if (world.isAir(px, py, pz) && world.is(px, py - 1, pz, Blocks.SAND)) {
                world.set(px, py, pz, Blocks.DEAD_BUSH.defaultBlockState());
            }
        }
    }

    /** Sugar cane beside water: 20 tries, each a stalk of 2–4. */
    static void reeds(BetaWorld world, Random rand, int x, int y, int z) {
        for (int i = 0; i < 20; i++) {
            int px = x + rand.nextInt(4) - rand.nextInt(4);
            int pz = z + rand.nextInt(4) - rand.nextInt(4);
            if (!world.isAir(px, y, pz)) continue;
            if (!(world.is(px - 1, y - 1, pz, Blocks.WATER) || world.is(px + 1, y - 1, pz, Blocks.WATER)
                    || world.is(px, y - 1, pz - 1, Blocks.WATER) || world.is(px, y - 1, pz + 1, Blocks.WATER))) {
                continue;
            }
            int h = 2 + rand.nextInt(rand.nextInt(3) + 1);
            for (int k = 0; k < h; k++) {
                BlockState below = world.get(px, y + k - 1, pz);
                boolean stays = k == 0
                        ? below.is(Blocks.GRASS_BLOCK) || below.is(Blocks.DIRT) || below.is(Blocks.SAND)
                        : below.is(Blocks.SUGAR_CANE);
                if (stays && world.isAir(px, y + k, pz)) world.set(px, y + k, pz, Blocks.SUGAR_CANE.defaultBlockState());
            }
        }
    }

    /** A pumpkin patch on grass: 64 tries, random facing. */
    static void pumpkins(BetaWorld world, Random rand, int x, int y, int z) {
        for (int i = 0; i < 64; i++) {
            int px = x + rand.nextInt(8) - rand.nextInt(8);
            int py = y + rand.nextInt(4) - rand.nextInt(4);
            int pz = z + rand.nextInt(8) - rand.nextInt(8);
            if (world.isAir(px, py, pz) && world.is(px, py - 1, pz, Blocks.GRASS_BLOCK)) {
                Direction facing = Direction.from2DDataValue(rand.nextInt(4));
                world.set(px, py, pz, Blocks.CARVED_PUMPKIN.defaultBlockState().setValue(CarvedPumpkinBlock.FACING, facing));
            }
        }
    }

    /** Cacti on sand with open sides: 10 tries, each 1–3 tall. */
    static void cactus(BetaWorld world, Random rand, int x, int y, int z) {
        for (int i = 0; i < 10; i++) {
            int px = x + rand.nextInt(8) - rand.nextInt(8);
            int py = y + rand.nextInt(4) - rand.nextInt(4);
            int pz = z + rand.nextInt(8) - rand.nextInt(8);
            if (!world.isAir(px, py, pz)) continue;
            int h = 1 + rand.nextInt(rand.nextInt(3) + 1);
            for (int k = 0; k < h; k++) {
                int cy = py + k;
                BlockState below = world.get(px, cy - 1, pz);
                boolean open = !world.isSolid(px - 1, cy, pz) && !world.isSolid(px + 1, cy, pz)
                        && !world.isSolid(px, cy, pz - 1) && !world.isSolid(px, cy, pz + 1);
                if (open && world.isAir(px, cy, pz) && (below.is(Blocks.SAND) || below.is(Blocks.CACTUS))) {
                    world.set(px, cy, pz, Blocks.CACTUS.defaultBlockState());
                }
            }
        }
    }

    // ---- springs ------------------------------------------------------------------------

    /** A one-block spring in a stone wall with exactly one open side. */
    static void spring(BetaWorld world, int x, int y, int z, BlockState fluid) {
        if (!world.is(x, y + 1, z, Blocks.STONE) || !world.is(x, y - 1, z, Blocks.STONE)) return;
        if (!world.isAir(x, y, z) && !world.is(x, y, z, Blocks.STONE)) return;
        int stone = 0;
        int air = 0;
        int[][] sides = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        for (int[] s : sides) {
            if (world.is(x + s[0], y, z + s[1], Blocks.STONE)) stone++;
            if (world.isAir(x + s[0], y, z + s[1])) air++;
        }
        if (stone == 3 && air == 1) {
            world.set(x, y, z, fluid);
            world.tickFluid(x, y, z, fluid.is(Blocks.LAVA) ? Fluids.LAVA : Fluids.WATER);
        }
    }
}
