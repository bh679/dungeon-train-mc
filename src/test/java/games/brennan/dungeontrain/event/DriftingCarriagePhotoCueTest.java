package games.brennan.dungeontrain.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The throttle behind the {@code BUILDING} ride-photo cue. Building a drifting carriage sets blocks
 * several times a second and every one of them reaches {@code creditEdit}; without this gap each
 * block would put a packet on the wire, even though the client's own cooldown would ignore nearly
 * all of them.
 */
class DriftingCarriagePhotoCueTest {

    private static final long T0 = 1_000_000L;

    @Test
    @DisplayName("a player who has never been cued is due")
    void firstEditIsAlwaysDue() {
        assertTrue(SharedCarriageAdvancementEvents.cueDue(null, T0));
    }

    @Test
    @DisplayName("blocks set inside the window do not each earn a cue")
    void editsInsideTheWindowAreHeldOff() {
        assertFalse(SharedCarriageAdvancementEvents.cueDue(T0, T0));
        assertFalse(SharedCarriageAdvancementEvents.cueDue(T0, T0 + 1));
        assertFalse(SharedCarriageAdvancementEvents.cueDue(
            T0, T0 + SharedCarriageAdvancementEvents.CUE_THROTTLE_MS - 1));
    }

    @Test
    @DisplayName("the window is inclusive at its far edge")
    void theWindowEndsInclusively() {
        assertTrue(SharedCarriageAdvancementEvents.cueDue(
            T0, T0 + SharedCarriageAdvancementEvents.CUE_THROTTLE_MS));
        assertTrue(SharedCarriageAdvancementEvents.cueDue(
            T0, T0 + SharedCarriageAdvancementEvents.CUE_THROTTLE_MS * 10));
    }

    @Test
    @DisplayName("a wall clock that moved backwards counts as due, not as a lockout")
    void aBackwardsClockDoesNotStrandThePlayer() {
        // System.currentTimeMillis can go back (NTP, a manual clock change). Treating that as "not
        // due" would silently stop cueing this player until real time caught up.
        assertTrue(SharedCarriageAdvancementEvents.cueDue(T0, T0 - 60_000L));
    }
}
