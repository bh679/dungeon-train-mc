package games.brennan.dungeontrain.event;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PauseSaveThrottle} lets vanilla's save-on-pause run only when no save ran in the last
 * minute, so opening book after book in singleplayer stops saving the whole world each time.
 */
class PauseSaveThrottleTest {

    private static final long MS = 1_000_000L;
    private static final long MIN = PauseSaveThrottle.MIN_INTERVAL_MS;

    @AfterEach
    void clear() {
        PauseSaveThrottle.reset();
    }

    @Test
    @DisplayName("no save yet → pause-save allowed")
    void noBaseline() {
        assertTrue(PauseSaveThrottle.shouldSave(5_000 * MS, 0L, MIN));
    }

    @Test
    @DisplayName("saved 3 s ago (next book) → skipped")
    void recentSaveSkips() {
        assertFalse(PauseSaveThrottle.shouldSave(103_000 * MS, 100_000 * MS, MIN));
    }

    @Test
    @DisplayName("exactly the interval → allowed")
    void atInterval() {
        assertTrue(PauseSaveThrottle.shouldSave((100_000 + MIN) * MS, 100_000 * MS, MIN));
    }

    @Test
    @DisplayName("just under the interval → skipped")
    void justUnder() {
        assertFalse(PauseSaveThrottle.shouldSave((100_000 + MIN - 1) * MS, 100_000 * MS, MIN));
    }

    @Test
    @DisplayName("noteSave arms the cooldown; reset (world stop) clears it")
    void noteAndReset() {
        PauseSaveThrottle.noteSave(100_000 * MS);
        assertFalse(PauseSaveThrottle.allowPauseSave(101_000 * MS));
        PauseSaveThrottle.reset();
        assertTrue(PauseSaveThrottle.allowPauseSave(101_000 * MS));
    }
}
