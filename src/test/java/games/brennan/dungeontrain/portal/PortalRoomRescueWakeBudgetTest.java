package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The escalation budget behind {@code wakeATrain}.
 *
 * <p>The rescue asks Sable for a culled carriage group so there is somewhere to land. Left
 * unthrottled that ask repeats every tick, and because {@link PortalCarriageRevival#claimReload}
 * throttles per <i>group</i>, each repeat wakes a <i>different</i> group — so a stranded player
 * un-culls the whole train, one disk load per tick, in the state where the server can least afford
 * it. Two rules stop that: nothing is asked for twice inside
 * {@link PortalRoomRescue#WAKE_INTERVAL_TICKS}, and one stranding never asks for more than
 * {@link PortalRoomRescue#MAX_WAKES} groups in total.</p>
 *
 * <p>Both are pure bookkeeping over the game clock, so they test without a world.</p>
 */
class PortalRoomRescueWakeBudgetTest {

    private static final int PAIR = 24;
    private static final int OTHER_PAIR = 48;

    @BeforeEach
    @AfterEach
    void reset() {
        PortalRoomRescue.clear();
    }

    @Test
    @DisplayName("the first ask of an episode goes straight through")
    void firstAskIsAllowed() {
        assertTrue(PortalRoomRescue.mayWake(PAIR, 1_000L));
    }

    @Test
    @DisplayName("nothing is asked again until the interval has passed")
    void backsOffWithinTheInterval() {
        long now = 1_000L;
        PortalRoomRescue.noteWake(PAIR, now, true);

        assertFalse(PortalRoomRescue.mayWake(PAIR, now));
        assertFalse(PortalRoomRescue.mayWake(PAIR, now + PortalRoomRescue.WAKE_INTERVAL_TICKS - 1));
        assertTrue(PortalRoomRescue.mayWake(PAIR, now + PortalRoomRescue.WAKE_INTERVAL_TICKS));
    }

    @Test
    @DisplayName("a scan that woke nothing still backs off, but does not spend budget")
    void fruitlessScanCostsTimeNotBudget() {
        long now = 1_000L;
        PortalRoomRescue.noteWake(PAIR, now, false);

        assertFalse(PortalRoomRescue.mayWake(PAIR, now + 1));
        assertEquals(0, PortalRoomRescue.wakesUsed(PAIR));

        // …so a group that only reaches holding later is still reachable: the cap is about reloads
        // actually issued, not about how often the room looked.
        for (int i = 1; i <= PortalRoomRescue.MAX_WAKES; i++) {
            long tick = now + (long) i * PortalRoomRescue.WAKE_INTERVAL_TICKS;
            assertTrue(PortalRoomRescue.mayWake(PAIR, tick), "still allowed on scan " + i);
            PortalRoomRescue.noteWake(PAIR, tick, true);
        }
        assertEquals(PortalRoomRescue.MAX_WAKES, PortalRoomRescue.wakesUsed(PAIR));
    }

    @Test
    @DisplayName("one stranding never asks for more than MAX_WAKES groups, however long it lasts")
    void stopsAtTheCap() {
        long tick = 1_000L;
        for (int i = 0; i < PortalRoomRescue.MAX_WAKES; i++) {
            assertTrue(PortalRoomRescue.mayWake(PAIR, tick), "wake " + i + " should be allowed");
            PortalRoomRescue.noteWake(PAIR, tick, true);
            tick += PortalRoomRescue.WAKE_INTERVAL_TICKS;
        }

        assertFalse(PortalRoomRescue.mayWake(PAIR, tick));
        // Not merely "not yet" — no amount of waiting re-opens it.
        assertFalse(PortalRoomRescue.mayWake(PAIR, tick + 100_000L));
    }

    @Test
    @DisplayName("the pair recovering gives the next stranding a full budget")
    void forgetResetsTheEpisode() {
        long tick = 1_000L;
        for (int i = 0; i < PortalRoomRescue.MAX_WAKES; i++) {
            PortalRoomRescue.noteWake(PAIR, tick, true);
            tick += PortalRoomRescue.WAKE_INTERVAL_TICKS;
        }
        assertFalse(PortalRoomRescue.mayWake(PAIR, tick));

        PortalRoomRescue.forget(PAIR);

        assertTrue(PortalRoomRescue.mayWake(PAIR, tick));
        assertEquals(0, PortalRoomRescue.wakesUsed(PAIR));
    }

    @Test
    @DisplayName("budgets are per pair — one room's exhausted escalation does not silence another's")
    void budgetsAreIndependent() {
        long tick = 1_000L;
        for (int i = 0; i < PortalRoomRescue.MAX_WAKES; i++) {
            PortalRoomRescue.noteWake(PAIR, tick, true);
            tick += PortalRoomRescue.WAKE_INTERVAL_TICKS;
        }

        assertFalse(PortalRoomRescue.mayWake(PAIR, tick));
        assertTrue(PortalRoomRescue.mayWake(OTHER_PAIR, tick));
    }

    @Test
    @DisplayName("a dead end is announced once per episode, and again after a recovery")
    void deadEndIsSaidOncePerEpisode() {
        assertTrue(PortalRoomRescue.firstDeadEnd(PAIR));
        assertFalse(PortalRoomRescue.firstDeadEnd(PAIR));

        // Marking it must not have quietly spent a wake, or a room that briefly had nothing in
        // holding would come up short later.
        assertEquals(0, PortalRoomRescue.wakesUsed(PAIR));

        PortalRoomRescue.forget(PAIR);
        assertTrue(PortalRoomRescue.firstDeadEnd(PAIR));
    }

    @Test
    @DisplayName("marking a dead end leaves the pair askable on the very next tick")
    void deadEndMarkDoesNotBlockTheNextAsk() {
        // firstDeadEnd runs before noteWake in both dead-end paths, so the record it creates must not
        // read as "asked just now" — the following noteWake is what sets the clock.
        PortalRoomRescue.firstDeadEnd(PAIR);
        assertTrue(PortalRoomRescue.mayWake(PAIR, PortalRoomRescue.WAKE_INTERVAL_TICKS));
    }
}
