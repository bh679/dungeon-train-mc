package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static games.brennan.dungeontrain.portal.PortalWalkThrough.Decision;
import static games.brennan.dungeontrain.portal.PortalWalkThrough.OPEN_AFTER_TICKS;
import static games.brennan.dungeontrain.portal.PortalWalkThrough.REOPEN_PERIOD_TICKS;
import static games.brennan.dungeontrain.portal.PortalWalkThrough.STREAK_GAP_TICKS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rule that turns a run of refused swaps into an opened centre-wall plate. */
final class PortalWalkThroughTest {

    private static final int CARRIAGE = 30;

    @BeforeEach
    void reset() {
        PortalWalkThrough.clear();
    }

    /** Refuse every tick from {@code from} to {@code to} inclusive, returning the last decision. */
    private static Decision refuse(int carriage, long from, long to) {
        Decision last = Decision.NONE;
        for (long t = from; t <= to; t++) {
            last = PortalWalkThrough.noteTick(carriage, t, true);
        }
        return last;
    }

    @Test
    @DisplayName("the plate opens on the first tick the streak reaches OPEN_AFTER_TICKS, and logs once")
    void opensAfterTwoSecondsOfRefusals() {
        for (long t = 0; t < OPEN_AFTER_TICKS; t++) {
            assertEquals(Decision.NONE, PortalWalkThrough.noteTick(CARRIAGE, t, true), "tick " + t);
        }
        assertEquals(Decision.OPEN_AND_LOG, PortalWalkThrough.noteTick(CARRIAGE, OPEN_AFTER_TICKS, true));
        assertTrue(PortalWalkThrough.isOpen(CARRIAGE));
    }

    @Test
    @DisplayName("a working corridor never opens: no refusals, no streak")
    void quietTicksNeverOpen() {
        for (long t = 0; t < 10 * OPEN_AFTER_TICKS; t++) {
            assertEquals(Decision.NONE, PortalWalkThrough.noteTick(CARRIAGE, t, false));
        }
        assertFalse(PortalWalkThrough.isOpen(CARRIAGE));
    }

    @Test
    @DisplayName("a short silence — a glance back toward the train — does not reset the streak")
    void shortGapKeepsTheStreak() {
        refuse(CARRIAGE, 0, 10);
        // Quiet for exactly STREAK_GAP_TICKS ticks after the last refusal (ticks 11..30): the
        // gap the rule tolerates, not one more.
        for (long t = 11; t < 10 + STREAK_GAP_TICKS; t++) {
            assertEquals(Decision.NONE, PortalWalkThrough.noteTick(CARRIAGE, t, false));
        }
        // Resume on the last tolerated tick: the streak still dates from tick 0, so it opens at
        // OPEN_AFTER_TICKS.
        assertEquals(Decision.OPEN_AND_LOG,
            refuse(CARRIAGE, 10 + STREAK_GAP_TICKS, OPEN_AFTER_TICKS));
    }

    @Test
    @DisplayName("a longer silence ends the streak, and the next one starts from scratch")
    void longGapResets() {
        refuse(CARRIAGE, 0, 10);
        long resume = 11 + STREAK_GAP_TICKS + 1;
        assertEquals(Decision.NONE, PortalWalkThrough.noteTick(CARRIAGE, resume - 1, false));
        assertEquals(Decision.NONE, refuse(CARRIAGE, resume, resume + OPEN_AFTER_TICKS - 1));
        assertEquals(Decision.OPEN_AND_LOG,
            PortalWalkThrough.noteTick(CARRIAGE, resume + OPEN_AFTER_TICKS, true));
    }

    @Test
    @DisplayName("once open, the plate is re-asserted quietly every REOPEN_PERIOD_TICKS")
    void reopensQuietlyOnAPeriod() {
        refuse(CARRIAGE, 0, OPEN_AFTER_TICKS);
        for (long t = OPEN_AFTER_TICKS + 1; t < OPEN_AFTER_TICKS + REOPEN_PERIOD_TICKS; t++) {
            assertEquals(Decision.NONE, PortalWalkThrough.noteTick(CARRIAGE, t, true), "tick " + t);
        }
        assertEquals(Decision.OPEN_QUIET,
            PortalWalkThrough.noteTick(CARRIAGE, OPEN_AFTER_TICKS + REOPEN_PERIOD_TICKS, true));
        assertEquals(Decision.NONE,
            PortalWalkThrough.noteTick(CARRIAGE, OPEN_AFTER_TICKS + REOPEN_PERIOD_TICKS + 1, true));
    }

    @Test
    @DisplayName("forget ends the episode, so the next one logs again")
    void forgetRearmsTheLog() {
        refuse(CARRIAGE, 0, OPEN_AFTER_TICKS);
        assertTrue(PortalWalkThrough.isOpen(CARRIAGE));
        PortalWalkThrough.forget(CARRIAGE);
        assertFalse(PortalWalkThrough.isOpen(CARRIAGE));
        long later = 1000;
        assertEquals(Decision.OPEN_AND_LOG, refuse(CARRIAGE, later, later + OPEN_AFTER_TICKS));
    }

    @Test
    @DisplayName("corridors are independent of one another")
    void corridorsAreIndependent() {
        refuse(CARRIAGE, 0, OPEN_AFTER_TICKS);
        assertTrue(PortalWalkThrough.isOpen(CARRIAGE));
        assertFalse(PortalWalkThrough.isOpen(CARRIAGE + 2));
        assertEquals(Decision.NONE, PortalWalkThrough.noteTick(CARRIAGE + 2, OPEN_AFTER_TICKS, true));
    }
}
