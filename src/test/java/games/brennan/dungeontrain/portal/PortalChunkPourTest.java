package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The row bookkeeping of a pour. What the rows write needs a world and is tested in game. */
class PortalChunkPourTest {

    private static final int HEIGHT = 32;

    @Test
    @DisplayName("A 32-tall box takes eight ticks, and every row is written exactly once, bottom up")
    void everyRowOnce() {
        PortalChunkPour pour = PortalChunkPour.of(null);
        int expectedFrom = 0;
        int ticks = 0;
        while (!pour.rowsDone(HEIGHT)) {
            assertEquals(expectedFrom, pour.nextRow());
            expectedFrom = pour.rowsEnd(HEIGHT);
            pour = pour.advanced(HEIGHT, 1L);
            ticks++;
        }
        assertEquals(8, ticks);
        assertEquals(HEIGHT, pour.nextRow());
        assertEquals(8, pour.ticks());
    }

    @Test
    @DisplayName("A box that does not divide evenly stops at its top row rather than past it")
    void shortLastStep() {
        PortalChunkPour pour = new PortalChunkPour(null, 8, 2, 0L, 0L);
        assertEquals(10, pour.rowsEnd(10));
        assertTrue(pour.advanced(10, 1L).rowsDone(10));
        assertFalse(pour.rowsDone(10));
    }

    @Test
    @DisplayName("A newer sample starts again from the bottom and keeps the timings")
    void restartKeepsTimings() {
        PortalChunkPour pour = PortalChunkPour.of(null).advanced(HEIGHT, 5L).advanced(HEIGHT, 3L);
        PortalChunkPour restarted = pour.restartedWith(null);

        assertEquals(0, restarted.nextRow());
        assertEquals(2, restarted.ticks());
        assertEquals(8L, restarted.totalNanos());
        assertEquals(5L, restarted.maxNanos());
    }
}
