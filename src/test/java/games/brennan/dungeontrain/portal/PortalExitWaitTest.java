package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.portal.PortalExitWait.Step;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link PortalExitWait}'s rules: what a wait does next, which chunks must load, and what counts as walking out. */
final class PortalExitWaitTest {

    @Test
    @DisplayName("leaving ends a wait first, then ready chunks send the player on, then the timeout gives up")
    void decideOrder() {
        assertEquals(Step.CANCEL, PortalExitWait.decide(false, true, 0L));
        assertEquals(Step.TRAVEL, PortalExitWait.decide(true, true, PortalExitWait.TIMEOUT_TICKS + 1L));
        assertEquals(Step.GIVE_UP, PortalExitWait.decide(true, false, PortalExitWait.TIMEOUT_TICKS));
        assertEquals(Step.WAIT, PortalExitWait.decide(true, false, PortalExitWait.TIMEOUT_TICKS - 1L));
    }

    @Test
    @DisplayName("every chunk within reach of the exit counts, in both directions and across zero")
    void exitChunks() {
        Set<Long> asked = new HashSet<>();
        assertTrue(PortalExitWait.exitChunksPass(0, 0, (cx, cz) -> {
            asked.add(((long) cx << 32) | (cz & 0xFFFFFFFFL));
            return true;
        }));
        assertEquals(16, asked.size(), "x and z each span chunks -2..1");
        assertFalse(PortalExitWait.exitChunksPass(0, 0, (cx, cz) -> !(cx == -2 && cz == 1)), "one missing chunk blocks");
        assertTrue(PortalExitWait.exitChunksPass(8, 8, (cx, cz) -> cx >= -1 && cx <= 1 && cz >= -1 && cz <= 1),
                "the middle of a chunk reaches only its neighbours");
    }

    @Test
    @DisplayName("walking out is judged in all three axes, but not along the track for a frame on a carriage")
    void walkingOut() {
        assertFalse(PortalExitWait.walkedOut(2.0, 0.0, 2.0, false));
        assertTrue(PortalExitWait.walkedOut(7.0, 0.0, 0.0, false));
        assertFalse(PortalExitWait.walkedOut(200.0, 0.0, 1.0, true), "the train carries the player along X");
        assertTrue(PortalExitWait.walkedOut(200.0, 0.0, 7.0, true));
        assertTrue(PortalExitWait.walkedOut(0.0, 7.0, 0.0, true));
    }
}
