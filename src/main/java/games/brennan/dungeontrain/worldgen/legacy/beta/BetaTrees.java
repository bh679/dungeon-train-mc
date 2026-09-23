package games.brennan.dungeontrain.worldgen.legacy.beta;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Random;

/**
 * Beta 1.7.3's small trees: the round oak, the taller birch, and the two taiga conifers (the layered
 * spruce and the bare-trunked pine). Each generator checks its space, turns the ground under it to
 * dirt, and lays leaves before the trunk — as Beta did. Coordinates are Beta coordinates on a
 * {@link BetaWorld}.
 */
public final class BetaTrees {

    /** Which small tree to grow; {@link #FANCY} hands off to {@link BetaBigTree}. */
    public enum Kind { OAK, BIRCH, SPRUCE, PINE, FANCY }

    private static final BlockState OAK_LOG = Blocks.OAK_LOG.defaultBlockState();
    private static final BlockState BIRCH_LOG = Blocks.BIRCH_LOG.defaultBlockState();
    private static final BlockState SPRUCE_LOG = Blocks.SPRUCE_LOG.defaultBlockState();
    static final BlockState OAK_LEAVES = leaves(Blocks.OAK_LEAVES.defaultBlockState());
    private static final BlockState BIRCH_LEAVES = leaves(Blocks.BIRCH_LEAVES.defaultBlockState());
    private static final BlockState SPRUCE_LEAVES = leaves(Blocks.SPRUCE_LEAVES.defaultBlockState());

    private BetaTrees() {}

    /** Natural (decaying) leaves that start attached, like freshly generated vanilla trees. */
    private static BlockState leaves(BlockState s) {
        return s.setValue(LeavesBlock.DISTANCE, 1);
    }

    /** Beta's per-biome tree pick ({@code getRandomWorldGenForTrees}). */
    public static Kind pick(BetaBiome biome, Random rand) {
        return switch (biome) {
            case FOREST -> rand.nextInt(5) == 0 ? Kind.BIRCH : rand.nextInt(3) == 0 ? Kind.FANCY : Kind.OAK;
            case RAINFOREST -> rand.nextInt(3) == 0 ? Kind.FANCY : Kind.OAK;
            case TAIGA -> rand.nextInt(3) == 0 ? Kind.PINE : Kind.SPRUCE;
            default -> rand.nextInt(10) == 0 ? Kind.FANCY : Kind.OAK;
        };
    }

    public static boolean grow(Kind kind, BetaWorld world, Random rand, int x, int y, int z) {
        return switch (kind) {
            case OAK -> roundTree(world, rand, x, y, z, rand.nextInt(3) + 4, OAK_LOG, OAK_LEAVES);
            case BIRCH -> roundTree(world, rand, x, y, z, rand.nextInt(3) + 5, BIRCH_LOG, BIRCH_LEAVES);
            case SPRUCE -> spruce(world, rand, x, y, z);
            case PINE -> pine(world, rand, x, y, z);
            case FANCY -> BetaBigTree.grow(world, rand, x, y, z);
        };
    }

    private static boolean onSoil(BetaWorld world, int x, int y, int z, int height) {
        if (y < 1 || y + height + 1 > BetaTerrain.HEIGHT) return false;
        BlockState below = world.get(x, y - 1, z);
        return (below.is(Blocks.GRASS_BLOCK) || below.is(Blocks.DIRT)) && y < BetaTerrain.HEIGHT - height - 1;
    }

    /** Every block in the ring {@code ±radius} at {@code y} is air or leaves. */
    private static boolean ringClear(BetaWorld world, int x, int y, int z, int radius) {
        if (y < 0 || y >= BetaTerrain.HEIGHT) return false;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (!world.isAirOrLeaves(x + dx, y, z + dz)) return false;
            }
        }
        return true;
    }

    private static boolean roundTree(BetaWorld world, Random rand, int x, int y, int z, int height,
                                     BlockState log, BlockState leaves) {
        if (y < 1 || y + height + 1 > BetaTerrain.HEIGHT) return false;
        for (int ty = y; ty <= y + 1 + height; ty++) {
            int r = ty == y ? 0 : ty >= y + 1 + height - 2 ? 2 : 1;
            if (!ringClear(world, x, ty, z, r)) return false;
        }
        if (!onSoil(world, x, y, z, height)) return false;
        world.set(x, y - 1, z, Blocks.DIRT.defaultBlockState());
        for (int ly = y - 3 + height; ly <= y + height; ly++) {
            int rel = ly - (y + height);
            int r = 1 - rel / 2;
            for (int lx = x - r; lx <= x + r; lx++) {
                int dx = lx - x;
                for (int lz = z - r; lz <= z + r; lz++) {
                    int dz = lz - z;
                    if ((Math.abs(dx) != r || Math.abs(dz) != r || rand.nextInt(2) != 0 && rel != 0)
                            && !world.isOpaque(lx, ly, lz)) {
                        world.set(lx, ly, lz, leaves);
                    }
                }
            }
        }
        for (int i = 0; i < height; i++) {
            if (world.isAirOrLeaves(x, y + i, z)) world.set(x, y + i, z, log);
        }
        return true;
    }

    private static boolean spruce(BetaWorld world, Random rand, int x, int y, int z) {
        int height = rand.nextInt(4) + 6;
        int bare = 1 + rand.nextInt(2);
        int crown = height - bare;
        int maxRadius = 2 + rand.nextInt(2);
        if (y < 1 || y + height + 1 > BetaTerrain.HEIGHT) return false;
        for (int ty = y; ty <= y + 1 + height; ty++) {
            if (!ringClear(world, x, ty, z, ty - y < bare ? 0 : maxRadius)) return false;
        }
        if (!onSoil(world, x, y, z, height)) return false;
        world.set(x, y - 1, z, Blocks.DIRT.defaultBlockState());
        int r = rand.nextInt(2);
        int step = 1;
        boolean reset = false;
        for (int i = 0; i <= crown; i++) {
            int ly = y + height - i;
            for (int lx = x - r; lx <= x + r; lx++) {
                int dx = lx - x;
                for (int lz = z - r; lz <= z + r; lz++) {
                    int dz = lz - z;
                    if ((Math.abs(dx) != r || Math.abs(dz) != r || r <= 0) && !world.isOpaque(lx, ly, lz)) {
                        world.set(lx, ly, lz, SPRUCE_LEAVES);
                    }
                }
            }
            if (r >= step) {
                r = reset ? 1 : 0;
                reset = true;
                if (++step > maxRadius) step = maxRadius;
            } else {
                r++;
            }
        }
        int topGap = rand.nextInt(3);
        for (int i = 0; i < height - topGap; i++) {
            if (world.isAirOrLeaves(x, y + i, z)) world.set(x, y + i, z, SPRUCE_LOG);
        }
        return true;
    }

    private static boolean pine(BetaWorld world, Random rand, int x, int y, int z) {
        int height = rand.nextInt(5) + 7;
        int bare = height - rand.nextInt(2) - 3;
        int crown = height - bare;
        int maxRadius = 1 + rand.nextInt(crown + 1);
        if (y < 1 || y + height + 1 > BetaTerrain.HEIGHT) return false;
        for (int ty = y; ty <= y + 1 + height; ty++) {
            if (!ringClear(world, x, ty, z, ty - y < bare ? 0 : maxRadius)) return false;
        }
        if (!onSoil(world, x, y, z, height)) return false;
        world.set(x, y - 1, z, Blocks.DIRT.defaultBlockState());
        int r = 0;
        for (int ly = y + height; ly >= y + bare; ly--) {
            for (int lx = x - r; lx <= x + r; lx++) {
                int dx = lx - x;
                for (int lz = z - r; lz <= z + r; lz++) {
                    int dz = lz - z;
                    if ((Math.abs(dx) != r || Math.abs(dz) != r || r <= 0) && !world.isOpaque(lx, ly, lz)) {
                        world.set(lx, ly, lz, SPRUCE_LEAVES);
                    }
                }
            }
            if (r >= 1 && ly == y + bare + 1) r--;
            else if (r < maxRadius) r++;
        }
        for (int i = 0; i < height - 1; i++) {
            if (world.isAirOrLeaves(x, y + i, z)) world.set(x, y + i, z, SPRUCE_LOG);
        }
        return true;
    }
}
