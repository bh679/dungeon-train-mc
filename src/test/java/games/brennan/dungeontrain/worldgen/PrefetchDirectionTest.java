package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link PrefetchDirection} — the End-band prefetch strip goes the way the player is heading. */
class PrefetchDirectionTest {

    @Test
    @DisplayName("moving players prefetch the way they move, wherever they are")
    void followsMovement() {
        assertEquals(1, PrefetchDirection.pick(30.0, false));
        assertEquals(1, PrefetchDirection.pick(30.0, true));     // behind spawn, heading back to it
        assertEquals(-1, PrefetchDirection.pick(-12.0, true));   // walking the reversed cycle
        assertEquals(-1, PrefetchDirection.pick(-12.0, false));
    }

    @Test
    @DisplayName("standing still falls back to the way the cycle is walked where the player stands")
    void standingStillUsesCycleSide() {
        assertEquals(1, PrefetchDirection.pick(0.2, false));
        assertEquals(-1, PrefetchDirection.pick(0.2, true));
        assertEquals(1, PrefetchDirection.pick(Double.NaN, false));
        assertEquals(-1, PrefetchDirection.pick(Double.NaN, true));
    }
}
