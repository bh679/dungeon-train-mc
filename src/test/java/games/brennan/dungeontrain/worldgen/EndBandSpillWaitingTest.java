package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link EndBandSpillWaiting} — spill waits for its chunk, one per neighbour, bounded, handed over once. */
class EndBandSpillWaitingTest {

    private static final long TARGET = 100L;
    private static final long WEST = 1L, EAST = 2L, NORTH = 3L;

    @Test
    @DisplayName("everything held for a chunk is taken exactly once, oldest neighbour first")
    void takeOnce() {
        EndBandSpillWaiting<String> w = new EndBandSpillWaiting<>(8);
        w.hold(TARGET, WEST, "west");
        w.hold(TARGET, EAST, "east");
        assertTrue(w.has(TARGET));
        assertEquals(List.of("west", "east"), w.take(TARGET));
        assertFalse(w.has(TARGET));
        assertEquals(List.of(), w.take(TARGET));
    }

    @Test
    @DisplayName("a re-sampled neighbour's spill replaces its earlier one instead of piling up")
    void repeatFromSameNeighbourReplaces() {
        EndBandSpillWaiting<String> w = new EndBandSpillWaiting<>(8);
        w.hold(TARGET, WEST, "west v1");
        w.hold(TARGET, NORTH, "north");
        w.hold(TARGET, WEST, "west v2");
        w.hold(TARGET, WEST, "west v3");
        assertEquals(List.of("west v3", "north"), w.take(TARGET));
    }

    @Test
    @DisplayName("a chunk never holds more than one spill per neighbour, however many times they arrive")
    void atMostEightNeighbours() {
        EndBandSpillWaiting<String> w = new EndBandSpillWaiting<>(8);
        for (int round = 0; round < 5; round++) {
            for (long source = 0; source < 8; source++) w.hold(TARGET, source, "s" + source + "r" + round);
        }
        List<String> held = w.take(TARGET);
        assertEquals(8, held.size());
        assertTrue(held.stream().allMatch(s -> s.endsWith("r4")), "only the newest from each neighbour");
    }

    @Test
    @DisplayName("past the chunk cap the oldest waiting chunk's spill is dropped")
    void evictsOldestChunk() {
        EndBandSpillWaiting<String> w = new EndBandSpillWaiting<>(2);
        w.hold(1L, WEST, "a");
        w.hold(2L, WEST, "b");
        w.hold(3L, WEST, "c");
        assertEquals(2, w.size());
        assertFalse(w.has(1L));
        assertTrue(w.has(2L));
        assertTrue(w.has(3L));
    }

    @Test
    @DisplayName("clear empties it")
    void clear() {
        EndBandSpillWaiting<String> w = new EndBandSpillWaiting<>(4);
        w.hold(TARGET, WEST, "a");
        w.clear();
        assertEquals(0, w.size());
        assertFalse(w.has(TARGET));
    }
}
