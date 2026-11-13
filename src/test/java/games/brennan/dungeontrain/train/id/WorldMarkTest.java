package games.brennan.dungeontrain.train.id;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Stability and spread of {@link WorldMark}. */
final class WorldMarkTest {

    @Test
    @DisplayName("a mark is four capital letters")
    void mark_shape() {
        for (long seed : new long[] {0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 8061837562118490811L}) {
            assertTrue(WorldMark.of(seed).matches("[A-Z]{4}"), WorldMark.of(seed));
        }
    }

    @Test
    @DisplayName("the mark of a seed never changes")
    void mark_pinned() {
        // Pinned: a change here breaks every id players have already written down.
        assertEquals(WorldMark.of(8061837562118490811L), WorldMark.of(8061837562118490811L));
        assertEquals("SEWK", WorldMark.of(8061837562118490811L));
    }

    @Test
    @DisplayName("neighbouring seeds get different marks, and marks spread out")
    void mark_spread() {
        assertNotEquals(WorldMark.of(1L), WorldMark.of(2L));
        Set<String> seen = new HashSet<>();
        for (long seed = 0; seed < 2000; seed++) seen.add(WorldMark.of(seed));
        assertTrue(seen.size() > 1990, "only " + seen.size() + " distinct marks in 2000 seeds");
    }
}
