package games.brennan.dungeontrain.world;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.LongPredicate;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link FluidInteractionDeferral} — the Minecraft-free store behind
 * {@code FluidInteractionNoLoadMixin}: which neighbour chunks a border block can reach, and when a
 * deferred fluid-interaction check becomes ready, expires, or is dropped.
 *
 * <p>Pure-logic coverage in the bootstrap-free style of {@code ServerStallWatchdogTest}: positions and
 * chunks are the packed longs the store works in, and "is this chunk loaded" is a predicate the test
 * controls. The chunk-key packing is asserted against the vanilla {@code ChunkPos.asLong} formula so
 * the mixin's {@code getChunkNow(chunkX(key), chunkZ(key))} round-trips.</p>
 */
final class FluidInteractionDeferralTest {

    private static final LongPredicate NOTHING_LOADED = key -> false;
    private static final LongPredicate EVERYTHING_LOADED = key -> true;

    private static long chunk(int cx, int cz) {
        return FluidInteractionDeferral.chunkKey(cx, cz);
    }

    private static Set<Long> asSet(long[] keys) {
        return LongStream.of(keys).boxed().collect(Collectors.toSet());
    }

    // ---- chunk key packing -------------------------------------------------------------------

    @Test
    void chunkKeyMatchesVanillaAsLongAndRoundTrips() {
        int[][] samples = {{0, 0}, {1, -1}, {-1, 1}, {123456, -654321}, {Integer.MIN_VALUE >> 4, Integer.MAX_VALUE >> 4}};
        for (int[] s : samples) {
            long expected = (s[0] & 0xFFFFFFFFL) | ((s[1] & 0xFFFFFFFFL) << 32); // ChunkPos.asLong
            long key = chunk(s[0], s[1]);
            assertEquals(expected, key);
            assertEquals(s[0], FluidInteractionDeferral.chunkX(key));
            assertEquals(s[1], FluidInteractionDeferral.chunkZ(key));
        }
    }

    // ---- border neighbour chunks -------------------------------------------------------------

    @Test
    void interiorBlockReachesNoOtherChunk() {
        assertEquals(0, FluidInteractionDeferral.borderNeighbourChunks(5, 9).length);
        assertEquals(0, FluidInteractionDeferral.borderNeighbourChunks(17, 30).length);
        assertEquals(0, FluidInteractionDeferral.borderNeighbourChunks(-7, -7).length); // local 9,9
    }

    @Test
    void edgeBlockReachesExactlyTheChunkAcrossThatEdge() {
        assertArrayEquals(new long[]{chunk(-1, 0)}, FluidInteractionDeferral.borderNeighbourChunks(0, 8));
        assertArrayEquals(new long[]{chunk(1, 0)}, FluidInteractionDeferral.borderNeighbourChunks(15, 8));
        assertArrayEquals(new long[]{chunk(0, -1)}, FluidInteractionDeferral.borderNeighbourChunks(8, 0));
        assertArrayEquals(new long[]{chunk(0, 1)}, FluidInteractionDeferral.borderNeighbourChunks(8, 15));
    }

    @Test
    void cornerBlockReachesTwoChunksButNotTheDiagonal() {
        // Fluid interactions look at the six face neighbours only, so the diagonal chunk is never read.
        assertEquals(Set.of(chunk(-1, 0), chunk(0, -1)),
                asSet(FluidInteractionDeferral.borderNeighbourChunks(0, 0)));
        assertEquals(Set.of(chunk(1, 0), chunk(0, 1)),
                asSet(FluidInteractionDeferral.borderNeighbourChunks(15, 15)));
    }

    @Test
    void negativeCoordinatesUseFloorChunkMaths() {
        // x = -16 is local 0 of chunk -1 → its west neighbour is chunk -2.
        assertArrayEquals(new long[]{chunk(-2, 0)}, FluidInteractionDeferral.borderNeighbourChunks(-16, 3));
        // x = -1 is local 15 of chunk -1 → its east neighbour is chunk 0.
        assertArrayEquals(new long[]{chunk(0, 0)}, FluidInteractionDeferral.borderNeighbourChunks(-1, 3));
    }

    // ---- defer / drain -----------------------------------------------------------------------

    @Test
    void deferredPositionIsNotReadyWhileAnyMissingChunkIsUnloaded() {
        FluidInteractionDeferral store = new FluidInteractionDeferral();
        assertTrue(store.defer(100L, new long[]{chunk(1, 0), chunk(0, 1)}, 10));
        assertEquals(1, store.size());

        assertEquals(List.of(), store.drainReady(11, NOTHING_LOADED));
        // Only one of the two neighbours loaded → still waiting.
        assertEquals(List.of(), store.drainReady(12, key -> key == chunk(1, 0)));
        assertEquals(1, store.size());
    }

    @Test
    void deferredPositionIsReadyAndRemovedOnceEveryMissingChunkIsLoaded() {
        FluidInteractionDeferral store = new FluidInteractionDeferral();
        store.defer(100L, new long[]{chunk(1, 0), chunk(0, 1)}, 10);
        store.defer(200L, new long[]{chunk(5, 5)}, 10);

        assertEquals(List.of(100L), store.drainReady(20, key -> key != chunk(5, 5)));
        assertEquals(1, store.size());
        assertEquals(List.of(200L), store.drainReady(21, EVERYTHING_LOADED));
        assertEquals(0, store.size());
        assertEquals(2, store.replayedTotal());
    }

    @Test
    void redeferringTheSamePositionReplacesItsEntry() {
        FluidInteractionDeferral store = new FluidInteractionDeferral();
        store.defer(100L, new long[]{chunk(1, 0)}, 10);
        store.defer(100L, new long[]{chunk(0, 1)}, 11);
        assertEquals(1, store.size());
        // The old missing chunk no longer gates it; the new one does.
        assertEquals(List.of(), store.drainReady(12, key -> key == chunk(1, 0)));
        assertEquals(List.of(100L), store.drainReady(13, key -> key == chunk(0, 1)));
    }

    @Test
    void entriesExpireAfterMaxAgeWithoutBeingReplayed() {
        FluidInteractionDeferral store = new FluidInteractionDeferral();
        store.defer(100L, new long[]{chunk(1, 0)}, 0);
        assertEquals(List.of(), store.drainReady(FluidInteractionDeferral.MAX_AGE_TICKS, NOTHING_LOADED));
        assertEquals(1, store.size(), "still pending at exactly max age");
        assertEquals(List.of(), store.drainReady(FluidInteractionDeferral.MAX_AGE_TICKS + 1, NOTHING_LOADED));
        assertEquals(0, store.size(), "dropped once older than max age");
        assertEquals(1, store.expiredTotal());
        assertEquals(0, store.replayedTotal());
    }

    @Test
    void storeRefusesNewPositionsWhenFull() {
        FluidInteractionDeferral store = new FluidInteractionDeferral();
        for (int i = 0; i < FluidInteractionDeferral.MAX_PENDING; i++) {
            assertTrue(store.defer(i, new long[]{chunk(1, 0)}, 0));
        }
        assertFalse(store.defer(-1L, new long[]{chunk(1, 0)}, 0), "one past the cap is dropped");
        assertEquals(FluidInteractionDeferral.MAX_PENDING, store.size());
        assertEquals(1, store.droppedTotal());
        // Re-deferring a position already inside the store is a replace, not a new entry, so it is allowed.
        assertTrue(store.defer(0L, new long[]{chunk(2, 0)}, 0));
    }

    @Test
    void deferCountsEveryAcceptedCall() {
        FluidInteractionDeferral store = new FluidInteractionDeferral();
        store.defer(1L, new long[]{chunk(1, 0)}, 0);
        store.defer(2L, new long[]{chunk(1, 0)}, 0);
        store.defer(1L, new long[]{chunk(1, 0)}, 1);
        assertEquals(3, store.deferredTotal());
        assertEquals(2, store.size());
    }

    @Test
    void drainReadyPreservesDeferOrder() {
        FluidInteractionDeferral store = new FluidInteractionDeferral();
        store.defer(30L, new long[]{chunk(1, 0)}, 0);
        store.defer(10L, new long[]{chunk(1, 0)}, 0);
        store.defer(20L, new long[]{chunk(1, 0)}, 0);
        assertEquals(List.of(30L, 10L, 20L), store.drainReady(1, EVERYTHING_LOADED));
    }
}
