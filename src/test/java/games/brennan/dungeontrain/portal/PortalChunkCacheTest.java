package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The sample cache drops its least recently used ground by weight, never everything at once. */
class PortalChunkCacheTest {

    /** A value whose weight is its own number, so a test reads its budget at a glance. */
    private record Weighted(long weight) {}

    private static PortalChunkCache<Weighted> cache(long budget) {
        return new PortalChunkCache<>(budget, Weighted::weight);
    }

    @Test
    @DisplayName("Within budget, nothing is dropped")
    void keepsEverythingWithinBudget() {
        PortalChunkCache<Weighted> cache = cache(10);
        cache.put(1, new Weighted(4));
        cache.put(2, new Weighted(6));
        assertEquals(2, cache.size());
        assertEquals(10, cache.weight());
    }

    @Test
    @DisplayName("Over budget, the least recently used goes first, and only as many as it takes")
    void dropsOldestUntilWithinBudget() {
        PortalChunkCache<Weighted> cache = cache(10);
        cache.put(1, new Weighted(4));
        cache.put(2, new Weighted(4));
        cache.put(3, new Weighted(4));
        assertNull(cache.get(1));
        assertTrue(cache.containsKey(2));
        assertTrue(cache.containsKey(3));
        assertEquals(8, cache.weight());
    }

    @Test
    @DisplayName("Reading a room keeps it: a standing room outlives ground sampled after it")
    void readRefreshesRecency() {
        PortalChunkCache<Weighted> cache = cache(10);
        Weighted standing = new Weighted(4);
        cache.put(1, standing);
        cache.put(2, new Weighted(4));
        assertSame(standing, cache.get(1));
        cache.put(3, new Weighted(4));
        assertSame(standing, cache.get(1));
        assertFalse(cache.containsKey(2));
    }

    @Test
    @DisplayName("The entry just put stays even when it alone is over budget")
    void newestSurvivesOversize() {
        PortalChunkCache<Weighted> cache = cache(10);
        cache.put(1, new Weighted(3));
        Weighted huge = new Weighted(25);
        cache.put(2, huge);
        assertSame(huge, cache.get(2));
        assertFalse(cache.containsKey(1));
        assertEquals(25, cache.weight());
    }

    @Test
    @DisplayName("Replacing and removing keep the weight honest")
    void replaceAndRemoveAdjustWeight() {
        PortalChunkCache<Weighted> cache = cache(100);
        Weighted ground = new Weighted(9);
        cache.put(1, ground);
        cache.put(1, new Weighted(5));
        assertEquals(5, cache.weight());
        assertFalse(cache.remove(1, ground), "removal by identity ignores a value since replaced");
        assertEquals(5, cache.weight());
        cache.remove(1);
        assertEquals(0, cache.weight());
        assertEquals(0, cache.size());
    }

    @Test
    @DisplayName("Clear empties it; a budget must be positive")
    void clearAndBudget() {
        PortalChunkCache<Weighted> cache = cache(10);
        cache.put(1, new Weighted(4));
        cache.clear();
        assertEquals(0, cache.size());
        assertEquals(0, cache.weight());
        assertThrows(IllegalArgumentException.class, () -> cache(0));
    }

    @Test
    @DisplayName("The real budget holds 64 3x3 rooms, and a 65th drops only the oldest")
    void realBudgetFitsSixtyFourWideRooms() {
        long wide = 48L * 48 * PortalChunkTerrain.HEIGHT;
        PortalChunkCache<Weighted> cache = new PortalChunkCache<>(PortalChunkTerrain.MAX_CACHED_CELLS, Weighted::weight);
        for (int pair = 0; pair < 64; pair++) cache.put(pair, new Weighted(wide));
        assertEquals(64, cache.size());
        cache.put(64, new Weighted(wide));
        assertEquals(64, cache.size());
        assertFalse(cache.containsKey(0));
        assertTrue(cache.containsKey(1));
    }
}
