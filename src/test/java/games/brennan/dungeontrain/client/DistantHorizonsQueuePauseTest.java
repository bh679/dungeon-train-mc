package games.brennan.dungeontrain.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pause DT adds to Distant Horizons' chunk-update queue thread. No DH or Minecraft needed. */
final class DistantHorizonsQueuePauseTest {

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    @DisplayName("seam: only an explicit false turns the pause off")
    void seamValues() {
        assertTrue(DistantHorizonsQueuePause.isEnabled(null));
        assertTrue(DistantHorizonsQueuePause.isEnabled(""));
        assertTrue(DistantHorizonsQueuePause.isEnabled("true"));
        assertFalse(DistantHorizonsQueuePause.isEnabled("false"));
        assertFalse(DistantHorizonsQueuePause.isEnabled(" FALSE "));
    }

    @Test
    @DisplayName("an enabled pause actually waits")
    void enabledPauseWaits() {
        long start = System.nanoTime();
        DistantHorizonsQueuePause.pause(true);
        assertTrue(System.nanoTime() - start >= DistantHorizonsQueuePause.PAUSE_MILLIS * 900_000L);
    }

    @Test
    @DisplayName("an interrupt during the pause is handed back so DH's loop can stop")
    void interruptIsRestored() {
        Thread.currentThread().interrupt();
        DistantHorizonsQueuePause.pause(true);
        assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test
    @DisplayName("a disabled pause neither waits nor touches the interrupt flag")
    void disabledPauseIsANoOp() {
        DistantHorizonsQueuePause.pause(false);
        assertFalse(Thread.currentThread().isInterrupted());
        Thread.currentThread().interrupt();
        DistantHorizonsQueuePause.pause(false);
        assertTrue(Thread.currentThread().isInterrupted());
    }
}
