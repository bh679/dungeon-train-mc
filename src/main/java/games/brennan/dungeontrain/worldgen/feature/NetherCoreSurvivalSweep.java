package games.brennan.dungeontrain.worldgen.feature;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.function.IntPredicate;

/**
 * Removes decoration that can no longer survive where it stands, over a Nether core chunk's 3×3
 * {@code WorldGenRegion}, at the end of that chunk's decoration.
 *
 * <p>A feature placed in one chunk can be undermined by a feature decorated later from a neighbour:
 * a brimstone bud scattered onto a basalt_deltas corridor floor (still netherrack — the skin skips the
 * lane) had that floor turned to basalt by the deltas' {@code basalt_blobs}, leaving a bud that pops on
 * the next block update. A chunk's features only write inside its 3×3 region, so the chunk that
 * undermines a plant is the last to write near it — sweeping the region after every core chunk's
 * decoration therefore catches every case. Vanilla has the same race; its Nether just rarely leaves bare
 * netherrack under plants.</p>
 *
 * <p>Only non-solid, non-air, fluid-free states are tested ({@link #isFragile}), and sections whose
 * palette holds none are skipped. Failing blocks become air with flag 2 (no neighbour updates). Y runs
 * upward so a tall plant's top half is tested after its bottom. Consumes no randomness.</p>
 */
final class NetherCoreSurvivalSweep {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    /** Blocks kept clear of the region edge so {@code canSurvive}'s neighbour reads stay inside it. */
    static final int EDGE_MARGIN = 1;

    private NetherCoreSurvivalSweep() {}

    /** Whether a state might fail {@code canSurvive} — plants, fire, vines, buds; never terrain or fluid. */
    static boolean isFragile(BlockState state) {
        return !state.isAir() && state.getFluidState().isEmpty() && !state.isSolid();
    }

    /** Lowest swept block X (or Z) for a chunk whose min block coordinate is {@code chunkMin}. */
    static int sweepMin(int chunkMin) {
        return chunkMin - 16 + EDGE_MARGIN;
    }

    /** Highest swept block X (or Z) for a chunk whose min block coordinate is {@code chunkMin}. */
    static int sweepMax(int chunkMin) {
        return chunkMin + 31 - EDGE_MARGIN;
    }

    /**
     * Sweep the region around {@code center} between {@code yMin..yMax}, in columns whose world X passes
     * {@code sweptX} (the core), skipping positions {@code skip} (the track). Returns blocks removed.
     */
    static int sweep(WorldGenLevel level, ChunkPos center, int yMin, int yMax,
                     IntPredicate sweptX, PosFilter skip) {
        int removed = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int cx = center.x - 1; cx <= center.x + 1; cx++) {
            for (int cz = center.z - 1; cz <= center.z + 1; cz++) {
                ChunkAccess chunk = level.getChunk(cx, cz);
                int x0 = Math.max(cx << 4, sweepMin(center.getMinBlockX()));
                int x1 = Math.min((cx << 4) + 15, sweepMax(center.getMinBlockX()));
                int z0 = Math.max(cz << 4, sweepMin(center.getMinBlockZ()));
                int z1 = Math.min((cz << 4) + 15, sweepMax(center.getMinBlockZ()));
                removed += sweepChunk(level, chunk, x0, x1, z0, z1, yMin, yMax, sweptX, skip, pos);
            }
        }
        return removed;
    }

    private static int sweepChunk(WorldGenLevel level, ChunkAccess chunk, int x0, int x1, int z0, int z1,
                                  int yMin, int yMax, IntPredicate sweptX, PosFilter skip,
                                  BlockPos.MutableBlockPos pos) {
        int removed = 0;
        int lo = Math.max(yMin, chunk.getMinBuildHeight());
        int hi = Math.min(yMax, chunk.getMaxBuildHeight() - 1);
        for (int sy = lo >> 4; sy <= hi >> 4; sy++) {
            LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sy));
            if (section.hasOnlyAir() || !section.maybeHas(NetherCoreSurvivalSweep::isFragile)) continue;
            int ya = Math.max(lo, sy << 4);
            int yb = Math.min(hi, (sy << 4) + 15);
            for (int y = ya; y <= yb; y++) {
                for (int x = x0; x <= x1; x++) {
                    if (!sweptX.test(x)) continue;
                    for (int z = z0; z <= z1; z++) {
                        BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
                        if (!isFragile(state) || skip.test(x, y, z)) continue;
                        pos.set(x, y, z);
                        if (state.canSurvive(level, pos)) continue;
                        level.setBlock(pos, AIR, 2);
                        removed++;
                    }
                }
            }
        }
        return removed;
    }

    /** Positions the sweep must leave alone. */
    @FunctionalInterface
    interface PosFilter {
        boolean test(int x, int y, int z);
    }
}
