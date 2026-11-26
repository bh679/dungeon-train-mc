package games.brennan.dungeontrain.client.crash;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A clean exit forgets the run; the disconnect vanilla's emergency save makes while crashing does not. */
class CrashRunTrackerTest {

    @Test
    void aCleanExitClearsTheRecord() {
        assertTrue(CrashRunTracker.shouldClearOnExit(false));
    }

    @Test
    void aCrashKeepsTheRecord() {
        assertFalse(CrashRunTracker.shouldClearOnExit(true));
    }
}
