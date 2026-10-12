package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the pause-hold grace period and its debug readout. */
final class LodGenerationHoldTest {

    @AfterEach
    void reset() {
        LodGenerationHold.markUnavailable();
    }

    @Test
    @DisplayName("Generation is held only once the game has been paused for the full grace period")
    void graceDecision() {
        long start = 1_000_000L;
        assertFalse(LodGenerationHold.shouldHold(-1L, start), "not paused never holds");
        assertFalse(LodGenerationHold.shouldHold(start, start), "a fresh pause doesn't hold");
        assertFalse(LodGenerationHold.shouldHold(start, start + LodGenerationHold.GRACE_MILLIS - 1));
        assertTrue(LodGenerationHold.shouldHold(start, start + LodGenerationHold.GRACE_MILLIS));
        assertTrue(LodGenerationHold.shouldHold(start, start + 8_000_000L), "a two-hour pause holds");
    }

    @Test
    @DisplayName("Status readout tracks idle → holding (with duration) → unavailable")
    void statusReadout() {
        assertTrue(LodGenerationHold.describe(0L).startsWith("unavailable"));

        LodGenerationHold.markIdle();
        assertEquals(LodGenerationHold.Status.IDLE, LodGenerationHold.status());
        assertEquals(0L, LodGenerationHold.holdingSeconds(5_000L));

        LodGenerationHold.markHolding(10_000L);
        assertEquals(LodGenerationHold.Status.HOLDING, LodGenerationHold.status());
        assertEquals(734L, LodGenerationHold.holdingSeconds(744_000L));
        assertEquals("HOLDING for 734s (game paused)", LodGenerationHold.describe(744_000L));
    }
}
