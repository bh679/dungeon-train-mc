package games.brennan.dungeontrain.event;

import org.junit.jupiter.api.Test;

import games.brennan.dungeontrain.event.SharedCarriageAdvancementEvents.CloseAction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The "left something behind" rule for {@code drift_gift_left}: a drifting-carriage container counts
 * as a gift only when it holds MORE at close than it did at open.
 *
 * <p>Plus {@code closeAction}: a gift is sent at once; any other change is parked locally and travels in
 * one batch when the player leaves or the next upload flushes — see {@code SharedCarriageRegistryTest}.</p>
 */
class DriftingCarriageGiftTest {

    @Test
    void addingItemsIsAGift() {
        assertTrue(SharedCarriageAdvancementEvents.isGift(0, 1));   // left something in an empty chest
        assertTrue(SharedCarriageAdvancementEvents.isGift(3, 4));
        assertTrue(SharedCarriageAdvancementEvents.isGift(3, 64));
    }

    @Test
    void lootingIsNotAGift() {
        assertFalse(SharedCarriageAdvancementEvents.isGift(4, 3));
        assertFalse(SharedCarriageAdvancementEvents.isGift(9, 0));  // emptied it completely
    }

    @Test
    void anEvenSwapIsNotAGift() {
        // Took one item out, put a different one in: the carriage changed, but nothing was ADDED, and
        // the coarse count is all this rule looks at.
        assertFalse(SharedCarriageAdvancementEvents.isGift(5, 5));
        assertFalse(SharedCarriageAdvancementEvents.isGift(0, 0));
    }

    @Test
    void noRecordedOpenIsNeverAGift() {
        // Close with nothing snapshotted (opened before the world loaded this carriage, or a menu we
        // never saw opened) — we cannot claim the player put anything there.
        assertFalse(SharedCarriageAdvancementEvents.isGift(null, 7));
    }

    // ---------------- Close action ----------------

    private static final long SIG_A = 111L;
    private static final long SIG_B = 222L;

    @Test
    void aGiftIsSentAtOnce() {
        assertEquals(CloseAction.SEND_NOW, SharedCarriageAdvancementEvents.closeAction(0, 1, SIG_A, SIG_B));
    }

    @Test
    void takingItemsIsParkedNotSent() {
        assertEquals(CloseAction.PARK, SharedCarriageAdvancementEvents.closeAction(4, 3, SIG_A, SIG_B));
        assertEquals(CloseAction.PARK, SharedCarriageAdvancementEvents.closeAction(9, 0, SIG_A, SIG_B));
    }

    @Test
    void anEvenSwapOrRearrangeIsParked() {
        assertEquals(CloseAction.PARK, SharedCarriageAdvancementEvents.closeAction(5, 5, SIG_A, SIG_B));
    }

    @Test
    void openingAndClosingWithoutChangeDoesNothing() {
        assertEquals(CloseAction.NONE, SharedCarriageAdvancementEvents.closeAction(5, 5, SIG_A, SIG_A));
    }

    @Test
    void noRecordedOpenDoesNothing() {
        assertEquals(CloseAction.NONE, SharedCarriageAdvancementEvents.closeAction(null, 7, SIG_A, SIG_B));
    }
}
