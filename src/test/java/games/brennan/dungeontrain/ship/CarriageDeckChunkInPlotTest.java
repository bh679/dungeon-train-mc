package games.brennan.dungeontrain.ship;

import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link CarriageDeck#chunkInPlot} is a keyed read of Sable's plot chunk array. This pins it against the
 * linear scan of {@code getLoadedChunks()} it replaced, on the real {@link LevelPlot}: the same chunk
 * object for every cell of every loaded chunk (and non-null there, so a wrong coordinate space fails here
 * instead of reading as air in-game), and {@code null} for unloaded plot chunks, cells outside the plot
 * and a removed plot.
 *
 * <p>Sable's {@link PlotChunkHolder} constructor needs a built {@link LevelChunk}, and a chunk needs a
 * {@code Level}, which a unit test cannot make. Both lookups only read {@code holder.getChunk()} and
 * {@code chunk.getPos()}, plain field reads, so {@link #shell} allocates the two objects without running
 * their constructors and sets just those fields. Test-only; it names two fields and fails loudly if either
 * moves.</p>
 */
final class CarriageDeckChunkInPlotTest {

    /** Loaded chunk pattern inside a plot, as (local x, local z) offsets from the plot's min chunk. */
    private static final int[][] LOADED = {{0, 0}, {1, 0}, {0, 1}, {2, 2}, {3, 1}, {1, 3}, {3, 3}, {2, 0}, {0, 3}};

    private static final class TestPlot extends LevelPlot {
        TestPlot(int plotX, int plotZ, int logSize) {
            super(null, plotX, plotZ, logSize, null);
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return null;
        }

        @Override
        protected void onRemoveChunkHolder(LevelChunk chunk) {}

        /** Sable's version dereferences the sub-level; the index does not depend on the bounding box. */
        @Override
        public void updateBoundingBox() {}
    }

    /** Verbatim copy of the scan {@code chunkInPlot} used before the keyed lookup. */
    private static LevelChunk scan(LevelPlot plot, BlockPos shipLocal) {
        long chunkKey = ChunkPos.asLong(shipLocal.getX() >> 4, shipLocal.getZ() >> 4);
        for (PlotChunkHolder holder : plot.getLoadedChunks()) {
            LevelChunk chunk = holder.getChunk();
            if (chunk != null && chunk.getPos().toLong() == chunkKey) return chunk;
        }
        return null;
    }

    private static <T> T shell(Class<T> type) throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) f.get(null);
        return type.cast(unsafe.allocateInstance(type));
    }

    private static void set(Object target, Class<?> declaring, String field, Object value) throws Exception {
        Field f = declaring.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** Inserts a chunk the way Sable's {@code newChunk} does: keyed at {@code toLocal(global)}, pos = global. */
    private static LevelChunk addChunk(LevelPlot plot, ChunkPos global) throws Exception {
        LevelChunk chunk = shell(LevelChunk.class);
        set(chunk, ChunkAccess.class, "chunkPos", global);
        PlotChunkHolder holder = shell(PlotChunkHolder.class);
        set(holder, PlotChunkHolder.class, "chunk", chunk);
        plot.addChunkHolder(plot.toLocal(global), holder, false);
        return chunk;
    }

    private static List<ChunkPos> loadedGlobals(LevelPlot plot, int logSize) {
        ChunkPos min = plot.getChunkMin();
        int side = 1 << logSize;
        List<ChunkPos> out = new ArrayList<>();
        for (int[] lz : LOADED) {
            if (lz[0] < side && lz[1] < side) out.add(new ChunkPos(min.x + lz[0], min.z + lz[1]));
        }
        return out;
    }

    private static void assertSameAsScan(LevelPlot plot, BlockPos pos) {
        LevelChunk expected = scan(plot, pos);
        LevelChunk actual = CarriageDeck.chunkInPlot(plot, pos);
        assertSame(expected, actual, () -> "chunkInPlot diverged from the scan at " + pos
                + " (plot min " + plot.getChunkMin() + ", max " + plot.getChunkMax() + ")");
    }

    @Test
    @DisplayName("every cell of every loaded chunk: the same chunk as the scan, never null")
    void loadedCellsMatchScan() throws Exception {
        for (int[] plotPos : new int[][]{{0, 0}, {37, 12}, {-5, -9}, {-1, 3}}) {
            for (int logSize = 0; logSize <= 3; logSize++) {
                TestPlot plot = new TestPlot(plotPos[0], plotPos[1], logSize);
                List<ChunkPos> globals = loadedGlobals(plot, logSize);
                for (ChunkPos g : globals) addChunk(plot, g);
                assertEquals(globals.size(), plot.getLoadedChunks().size());

                for (ChunkPos g : globals) {
                    for (int x = g.getMinBlockX(); x <= g.getMaxBlockX(); x++) {
                        for (int z = g.getMinBlockZ(); z <= g.getMaxBlockZ(); z++) {
                            BlockPos pos = new BlockPos(x, (x + z) % 7 - 3, z);
                            LevelChunk chunk = CarriageDeck.chunkInPlot(plot, pos);
                            assertNotNull(chunk, () -> "loaded cell " + pos + " read as unloaded (air)");
                            assertEquals(g, chunk.getPos());
                            assertSameAsScan(plot, pos);
                        }
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("unloaded plot chunks and cells outside the plot: null from both")
    void missingCellsMatchScan() throws Exception {
        for (int[] plotPos : new int[][]{{0, 0}, {37, 12}, {-5, -9}}) {
            for (int logSize = 1; logSize <= 3; logSize++) {
                TestPlot plot = new TestPlot(plotPos[0], plotPos[1], logSize);
                for (ChunkPos g : loadedGlobals(plot, logSize)) addChunk(plot, g);
                ChunkPos min = plot.getChunkMin();
                ChunkPos max = plot.getChunkMax();

                // Every chunk from one chunk outside the plot on each side to one outside on the other.
                for (int cx = min.x - 1; cx <= max.x + 1; cx++) {
                    for (int cz = min.z - 1; cz <= max.z + 1; cz++) {
                        ChunkPos c = new ChunkPos(cx, cz);
                        for (BlockPos pos : new BlockPos[]{
                                new BlockPos(c.getMinBlockX(), 0, c.getMinBlockZ()),
                                new BlockPos(c.getMaxBlockX(), 5, c.getMaxBlockZ()),
                                new BlockPos(c.getMiddleBlockX(), -2, c.getMiddleBlockZ())}) {
                            assertSameAsScan(plot, pos);
                            if (!plot.contains(c)) {
                                assertNull(CarriageDeck.chunkInPlot(plot, pos), () -> "outside the plot: " + pos);
                            }
                        }
                    }
                }
                // Far away, both signs.
                for (int far : new int[]{-100_000, 100_000}) {
                    assertNull(CarriageDeck.chunkInPlot(plot, new BlockPos(far, 0, far)));
                    assertNull(CarriageDeck.chunkInPlot(plot, new BlockPos(far, 0, -far)));
                }
            }
        }
    }

    @Test
    @DisplayName("an empty or removed plot answers null for every cell")
    void emptyAndRemovedPlot() throws Exception {
        TestPlot plot = new TestPlot(4, -2, 2);
        BlockPos inside = new BlockPos(plot.getChunkMin().getMinBlockX() + 3, 1, plot.getChunkMin().getMinBlockZ() + 3);
        assertNull(CarriageDeck.chunkInPlot(plot, inside));
        assertNull(scan(plot, inside));

        for (ChunkPos g : loadedGlobals(plot, 2)) addChunk(plot, g);
        assertNotNull(CarriageDeck.chunkInPlot(plot, inside));

        plot.onRemove();
        assertNull(scan(plot, inside), "sanity: the scan saw the cleared list");
        assertNull(CarriageDeck.chunkInPlot(plot, inside), "a removed plot must still read as nothing");
    }
}
