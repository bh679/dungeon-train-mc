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

/**
 * The rule that turns a run of refused swaps into an opened centre-wall plate.
 *
 * <p>Keyed on the <b>pair</b>, not on a corridor: the refusals that feed it are about one role's own
 * destination, so per corridor this let a pair's entrance give up while its exit went on taking
 * people in.</p>
 */
final class PortalWalkThroughTest {

    /** A group anchor, which is what a pair is keyed on. */
    private static final int PAIR = 30;

    /** The next group along — a different portal, which must not be dragged into this one's episode. */
    private static final int OTHER_PAIR = PAIR + 3;

    @BeforeEach
    void reset() {
        PortalWalkThrough.clear();
    }

    /** Refuse every tick from {@code from} to {@code to} inclusive, returning the last decision. */
    private static Decision refuse(int pairKey, long from, long to) {
        Decision last = Decision.NONE;
        for (long t = from; t <= to; t++) {
            last = PortalWalkThrough.noteTick(pairKey, t, true);
        }
        return last;
    }

    @Test
    @DisplayName("the plate opens on the first tick the streak reaches OPEN_AFTER_TICKS, and logs once")
    void opensAfterTwoSecondsOfRefusals() {
        for (long t = 0; t < OPEN_AFTER_TICKS; t++) {
            assertEquals(Decision.NONE, PortalWalkThrough.noteTick(PAIR, t, true), "tick " + t);
        }
        assertEquals(Decision.OPEN_AND_LOG, PortalWalkThrough.noteTick(PAIR, OPEN_AFTER_TICKS, true));
        assertTrue(PortalWalkThrough.isOpen(PAIR));
    }

    @Test
    @DisplayName("a working corridor never opens: no refusals, no streak")
    void quietTicksNeverOpen() {
        for (long t = 0; t < 10 * OPEN_AFTER_TICKS; t++) {
            assertEquals(Decision.NONE, PortalWalkThrough.noteTick(PAIR, t, false));
        }
        assertFalse(PortalWalkThrough.isOpen(PAIR));
    }

    @Test
    @DisplayName("a short silence — a glance back toward the train — does not reset the streak")
    void shortGapKeepsTheStreak() {
        refuse(PAIR, 0, 10);
        // Quiet for exactly STREAK_GAP_TICKS ticks after the last refusal (ticks 11..30): the
        // gap the rule tolerates, not one more.
        for (long t = 11; t < 10 + STREAK_GAP_TICKS; t++) {
            assertEquals(Decision.NONE, PortalWalkThrough.noteTick(PAIR, t, false));
        }
        // Resume on the last tolerated tick: the streak still dates from tick 0, so it opens at
        // OPEN_AFTER_TICKS.
        assertEquals(Decision.OPEN_AND_LOG,
            refuse(PAIR, 10 + STREAK_GAP_TICKS, OPEN_AFTER_TICKS));
    }

    @Test
    @DisplayName("a longer silence ends the streak, and the next one starts from scratch")
    void longGapResets() {
        refuse(PAIR, 0, 10);
        long resume = 11 + STREAK_GAP_TICKS + 1;
        assertEquals(Decision.NONE, PortalWalkThrough.noteTick(PAIR, resume - 1, false));
        assertEquals(Decision.NONE, refuse(PAIR, resume, resume + OPEN_AFTER_TICKS - 1));
        assertEquals(Decision.OPEN_AND_LOG,
            PortalWalkThrough.noteTick(PAIR, resume + OPEN_AFTER_TICKS, true));
    }

    @Test
    @DisplayName("once open, the plate is re-asserted quietly every REOPEN_PERIOD_TICKS")
    void reopensQuietlyOnAPeriod() {
        refuse(PAIR, 0, OPEN_AFTER_TICKS);
        for (long t = OPEN_AFTER_TICKS + 1; t < OPEN_AFTER_TICKS + REOPEN_PERIOD_TICKS; t++) {
            assertEquals(Decision.NONE, PortalWalkThrough.noteTick(PAIR, t, true), "tick " + t);
        }
        assertEquals(Decision.OPEN_QUIET,
            PortalWalkThrough.noteTick(PAIR, OPEN_AFTER_TICKS + REOPEN_PERIOD_TICKS, true));
        assertEquals(Decision.NONE,
            PortalWalkThrough.noteTick(PAIR, OPEN_AFTER_TICKS + REOPEN_PERIOD_TICKS + 1, true));
    }

    @Test
    @DisplayName("forget ends the episode, so the next one logs again")
    void forgetRearmsTheLog() {
        refuse(PAIR, 0, OPEN_AFTER_TICKS);
        assertTrue(PortalWalkThrough.isOpen(PAIR));
        PortalWalkThrough.forget(PAIR);
        assertFalse(PortalWalkThrough.isOpen(PAIR));
        long later = 1000;
        assertEquals(Decision.OPEN_AND_LOG, refuse(PAIR, later, later + OPEN_AFTER_TICKS));
    }

    @Test
    @DisplayName("pairs are independent of one another")
    void pairsAreIndependent() {
        refuse(PAIR, 0, OPEN_AFTER_TICKS);
        assertTrue(PortalWalkThrough.isOpen(PAIR));
        assertFalse(PortalWalkThrough.isOpen(OTHER_PAIR));
        assertEquals(Decision.NONE, PortalWalkThrough.noteTick(OTHER_PAIR, OPEN_AFTER_TICKS, true));
    }

    /**
     * The half-working portal this keying exists to prevent: an entry corridor refusing every swap
     * while the exit at the other end of the same group is perfectly able to take somebody in.
     *
     * <p>The caller reports one bit for the pair, folded from both ends, so a stretch of ticks in
     * which the entry is refused and the exit is not still builds one continuous episode — and when
     * it opens, {@code PortalCarriageEvents} closes both ends to entry off this one answer.</p>
     */
    @Test
    @DisplayName("either end's refusals build the pair's one episode, and it answers for both")
    void bothEndsFeedOneEpisode() {
        // Ticks alternate between the two corridors doing the refusing: whichever end a player is
        // walking into as they turn about in the group.
        for (long t = 0; t < OPEN_AFTER_TICKS; t++) {
            assertEquals(Decision.NONE, PortalWalkThrough.noteTick(PAIR, t, true), "tick " + t);
        }
        assertEquals(Decision.OPEN_AND_LOG, PortalWalkThrough.noteTick(PAIR, OPEN_AFTER_TICKS, true));

        // One episode, one plate, and one answer to "does this pair still take anyone in" — asked
        // from the exit corridor's index as readily as from the entry's, since both resolve to the
        // group anchor before they ask.
        assertTrue(PortalWalkThrough.isOpen(
            PortalCarriageRole.entryIndexOf(PAIR + PortalCarriageSelection.SLOT_ENTRY, 3)));
        assertTrue(PortalWalkThrough.isOpen(
            PortalCarriageRole.entryIndexOf(PAIR + PortalCarriageSelection.SLOT_EXIT, 3)));
    }

    @Test
    @DisplayName("a pair that stops being refused lets go of its episode")
    void episodeLapsesWhenTheRefusalsStop() {
        refuse(PAIR, 0, OPEN_AFTER_TICKS);
        assertTrue(PortalWalkThrough.isOpen(PAIR));

        // Quiet for longer than the tolerated gap: whatever was refusing has stopped — the twin's
        // chunks caught up, the structure got placed — so the pair is no longer disconnected and the
        // next re-stamp will seal the plate again.
        long quiet = OPEN_AFTER_TICKS + STREAK_GAP_TICKS + 1;
        assertEquals(Decision.NONE, PortalWalkThrough.noteTick(PAIR, quiet, false));
        assertFalse(PortalWalkThrough.isOpen(PAIR));
    }
}
