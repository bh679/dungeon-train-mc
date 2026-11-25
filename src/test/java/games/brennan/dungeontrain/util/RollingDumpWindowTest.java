package games.brennan.dungeontrain.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static games.brennan.dungeontrain.util.RollingDumpWindow.WINDOW_MS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The rolling-hour short-dump budget both stall watchdogs share. */
final class RollingDumpWindowTest {

    private static final long T = 10L * WINDOW_MS;

    @Test
    @DisplayName("An empty window counts nothing and is open now")
    void emptyWindow() {
        RollingDumpWindow w = new RollingDumpWindow(10);
        assertEquals(0, w.countWithin(T));
        assertEquals(T, w.reopensAt(T));
    }

    @Test
    @DisplayName("Ten dumps inside an hour fill it; the slot reopens an hour after the oldest")
    void tenInAnHourFill() {
        RollingDumpWindow w = new RollingDumpWindow(10);
        for (int i = 0; i < 10; i++) w.record(T + i * 60_000L);
        long now = T + 9 * 60_000L;
        assertEquals(10, w.countWithin(now));
        assertEquals(T + WINDOW_MS, w.reopensAt(now));
    }

    @Test
    @DisplayName("Dumps older than an hour stop counting, so a long session never goes blind")
    void oldDumpsAgeOut() {
        RollingDumpWindow w = new RollingDumpWindow(10);
        for (int i = 0; i < 10; i++) w.record(T + i);
        assertEquals(10, w.countWithin(T + WINDOW_MS - 1));
        assertEquals(0, w.countWithin(T + WINDOW_MS + 10));
    }

    @Test
    @DisplayName("Once the ring is full a new dump replaces the oldest")
    void ringOverwritesOldest() {
        RollingDumpWindow w = new RollingDumpWindow(2);
        w.record(T);
        w.record(T + 1_000);
        w.record(T + WINDOW_MS);
        long now = T + WINDOW_MS;
        assertEquals(2, w.countWithin(now));
        assertEquals(T + 1_000 + WINDOW_MS, w.reopensAt(now));
    }

    @Test
    @DisplayName("A window needs room for at least one dump")
    void rejectsZeroCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new RollingDumpWindow(0));
    }
}
