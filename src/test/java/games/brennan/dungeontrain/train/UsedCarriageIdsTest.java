package games.brennan.dungeontrain.train;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The world's "already placed that one" memory: dedup, newest-first reads, ring cap, round-trip. */
class UsedCarriageIdsTest {

    @Test
    void addReportsOnlyGenuinelyNewIds() {
        UsedCarriageIds used = new UsedCarriageIds();
        assertTrue(used.add(7));
        assertFalse(used.add(7), "a repeat must not report a change — the save stays clean");
        assertEquals(1, used.size());
    }

    @Test
    void recentReturnsNewestFirstAndHonoursTheLimit() {
        UsedCarriageIds used = new UsedCarriageIds();
        for (int id = 1; id <= 5; id++) used.add(id);
        assertEquals(List.of(5, 4, 3, 2, 1), used.recent(10));
        assertEquals(List.of(5, 4, 3), used.recent(3), "the newest ids survive the relay's truncation");
        assertEquals(List.of(), used.recent(0));
    }

    @Test
    void theOldestIdsAreEvictedPastTheCap() {
        UsedCarriageIds used = new UsedCarriageIds();
        for (int id = 1; id <= UsedCarriageIds.MAX_REMEMBERED + 10; id++) used.add(id);
        assertEquals(UsedCarriageIds.MAX_REMEMBERED, used.size());
        assertFalse(used.contains(1), "oldest dropped first");
        assertTrue(used.contains(UsedCarriageIds.MAX_REMEMBERED + 10), "newest kept");
    }

    @Test
    void serialisationRoundTripsOldestFirst() {
        UsedCarriageIds used = new UsedCarriageIds();
        for (int id : new int[] {3, 9, 4}) used.add(id);
        int[] saved = used.toIntArray();
        assertEquals(List.of(3, 9, 4), List.of(saved[0], saved[1], saved[2]), "stored oldest-first");

        UsedCarriageIds restored = new UsedCarriageIds();
        restored.add(99); // whatever was there is replaced by the load
        restored.loadFrom(saved);
        assertEquals(List.of(4, 9, 3), restored.recent(10), "insertion order survives a save/load");
        assertFalse(restored.contains(99));
    }

    @Test
    void loadingNothingLeavesAnEmptyMemory() {
        UsedCarriageIds used = new UsedCarriageIds();
        used.loadFrom(new int[0]); // an absent NBT key reads back as an empty array
        assertEquals(0, used.size());
        used.loadFrom(null);
        assertEquals(0, used.size());
    }
}
