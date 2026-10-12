package games.brennan.dungeontrain.train;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.brennan.dungeontrain.train.HeldGroupWakeVeto.Window;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HeldGroupWakeVetoTest {

    /** The 2026-10-12 repro after a walk back: window [−8, 36] (front edge 33 + one group of 3). */
    private static final Window WALKED_BACK = new Window(-8, 36, 3, 1000L);

    @Test
    @DisplayName("a group wholly ahead of the window stays held (the front-edge loop)")
    void aheadOfWindowStaysHeld() {
        assertTrue(HeldGroupWakeVeto.leaveHeld(WALKED_BACK, 1005L, 39));
        assertTrue(HeldGroupWakeVeto.leaveHeld(WALKED_BACK, 1005L, 42));
    }

    @Test
    @DisplayName("a group wholly behind the window stays held (the rear sandwich loop)")
    void behindWindowStaysHeld() {
        assertTrue(HeldGroupWakeVeto.leaveHeld(WALKED_BACK, 1005L, -12));
    }

    @Test
    @DisplayName("a group overlapping the window, at either edge, may wake")
    void overlappingGroupMayWake() {
        assertFalse(HeldGroupWakeVeto.leaveHeld(WALKED_BACK, 1005L, -9)); // covers -9..-7
        assertFalse(HeldGroupWakeVeto.leaveHeld(WALKED_BACK, 1005L, 36)); // anchor on the top edge
        assertFalse(HeldGroupWakeVeto.leaveHeld(WALKED_BACK, 1005L, 12));
    }

    @Test
    @DisplayName("a stale, future or missing window never vetoes (Sable decides)")
    void staleWindowStandsAside() {
        assertFalse(HeldGroupWakeVeto.leaveHeld(WALKED_BACK, 1000L + HeldGroupWakeVeto.FRESH_TICKS + 1, 42));
        assertFalse(HeldGroupWakeVeto.leaveHeld(WALKED_BACK, 999L, 42));
        assertFalse(HeldGroupWakeVeto.leaveHeld(null, 1005L, 42));
        assertFalse(HeldGroupWakeVeto.leaveHeld(new Window(5, 4, 3, 1000L), 1005L, 42));
    }

    @Test
    @DisplayName("a sub-level of no train with a window is never vetoed")
    void unknownSubLevelNotVetoed() {
        HeldGroupWakeVeto.clear();
        assertFalse(HeldGroupWakeVeto.shouldLeaveHeld(UUID.randomUUID(), 1005L));
        assertFalse(HeldGroupWakeVeto.shouldLeaveHeld(null, 1005L));
    }
}
