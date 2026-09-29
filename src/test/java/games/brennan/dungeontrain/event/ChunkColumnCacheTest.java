package games.brennan.dungeontrain.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for {@link ChunkColumnCache}, the non-blocking chunk resolver behind
 * {@code TrainTickEvents.sweepFootprint}. The resolver stands in for {@code getChunkNow}: chunks in
 * {@code loaded} resolve to a string key, everything else to null (not yet FULL).
 */
class ChunkColumnCacheTest {

    private final Set<String> loaded = new HashSet<>();
    private final List<String> calls = new ArrayList<>();

    private ChunkColumnCache<String> cache() {
        return new ChunkColumnCache<>((cx, cz) -> {
            String key = cx + "," + cz;
            calls.add(key);
            return loaded.contains(key) ? key : null;
        });
    }

    @Test
    @DisplayName("a chunk is resolved once however many of its columns are read")
    void memoisesPerChunk() {
        loaded.add("0,0");
        ChunkColumnCache<String> c = cache();

        assertEquals("0,0", c.get(0, 0));
        assertEquals("0,0", c.get(15, 15));
        assertEquals("0,0", c.get(7, 3));
        assertEquals(List.of("0,0"), calls);
    }

    @Test
    @DisplayName("an unloaded chunk is memoised too, so it costs one lookup per sweep")
    void memoisesNull() {
        ChunkColumnCache<String> c = cache();

        assertNull(c.get(3, 3));
        assertNull(c.get(4, 9));
        assertEquals(1, calls.size());
    }

    @Test
    @DisplayName("negative block coordinates map to the chunk below, not chunk 0")
    void negativeCoordinates() {
        loaded.add("-1,-1");
        ChunkColumnCache<String> c = cache();

        assertEquals("-1,-1", c.get(-1, -1));
        assertEquals("-1,-1", c.get(-16, -16));
        assertNull(c.get(-17, -1), "x=-17 lies in chunk -2");
    }

    @Test
    @DisplayName("an interior column needs no neighbour lookup")
    void interiorColumnSkipsNeighbourLookups() {
        loaded.add("0,0");
        ChunkColumnCache<String> c = cache();
        c.get(8, 8);
        calls.clear();

        assertTrue(c.neighboursLoaded(8, 8));
        assertTrue(calls.isEmpty(), "no chunk other than the column's own is involved");
    }

    @Test
    @DisplayName("an edge column is unsafe to write while the chunk across the edge is loading")
    void edgeColumnNeedsNeighbourChunk() {
        loaded.add("0,0");
        ChunkColumnCache<String> c = cache();

        assertFalse(c.neighboursLoaded(0, 8), "west neighbour chunk -1,0 not loaded");
        assertFalse(c.neighboursLoaded(15, 8), "east neighbour chunk 1,0 not loaded");
        assertFalse(c.neighboursLoaded(8, 0), "north neighbour chunk 0,-1 not loaded");
        assertFalse(c.neighboursLoaded(8, 15), "south neighbour chunk 0,1 not loaded");

        loaded.add("-1,0");
        loaded.add("1,0");
        loaded.add("0,-1");
        loaded.add("0,1");
        ChunkColumnCache<String> fresh = cache();
        assertTrue(fresh.neighboursLoaded(0, 8));
        assertTrue(fresh.neighboursLoaded(15, 8));
        assertTrue(fresh.neighboursLoaded(8, 0));
        assertTrue(fresh.neighboursLoaded(8, 15));
    }

    @Test
    @DisplayName("a corner column checks both axes")
    void cornerChecksBothAxes() {
        loaded.add("0,0");
        loaded.add("1,0");
        ChunkColumnCache<String> c = cache();

        assertFalse(c.neighboursLoaded(15, 15), "x neighbour loaded but z neighbour 0,1 is not");

        loaded.add("0,1");
        assertTrue(cache().neighboursLoaded(15, 15));
    }
}
