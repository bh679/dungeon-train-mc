package games.brennan.dungeontrain.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The "left something behind" rule for {@code drift_gift_left}: a drifting-carriage container counts
 * as a gift only when it holds MORE at close than it did at open.
 *
 * <p>Plus {@code shouldQueue}, which decides whether the cell is uploaded: a gift always, and any
 * change at all once the carriage has already been changed (on the relay) — but looting a carriage
 * nobody has touched must never turn it into a build.</p>
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

    // ---------------- Upload queue ----------------

    private static final long SIG_A = 111L;
    private static final long SIG_B = 222L;

    @Test
    void lootingAnAlreadyChangedCarriageIsQueued() {
        assertTrue(SharedCarriageAdvancementEvents.shouldQueue(4, 3, SIG_A, SIG_B, true));
        assertTrue(SharedCarriageAdvancementEvents.shouldQueue(9, 0, SIG_A, SIG_B, true));
    }

    @Test
    void lootingAnUntouchedCarriageIsNotQueued() {
        assertFalse(SharedCarriageAdvancementEvents.shouldQueue(4, 3, SIG_A, SIG_B, false));
    }

    @Test
    void anEvenSwapIsQueuedOnlyOnceAlreadyChanged() {
        assertTrue(SharedCarriageAdvancementEvents.shouldQueue(5, 5, SIG_A, SIG_B, true));
        assertFalse(SharedCarriageAdvancementEvents.shouldQueue(5, 5, SIG_A, SIG_B, false));
    }

    @Test
    void openingAndClosingWithoutChangeIsNotQueued() {
        assertFalse(SharedCarriageAdvancementEvents.shouldQueue(5, 5, SIG_A, SIG_A, true));
    }

    @Test
    void aGiftIsQueuedEvenOnAnUntouchedCarriage() {
        assertTrue(SharedCarriageAdvancementEvents.shouldQueue(0, 1, SIG_A, SIG_B, false));
    }

    @Test
    void noRecordedOpenIsNeverQueued() {
        assertFalse(SharedCarriageAdvancementEvents.shouldQueue(null, 7, SIG_A, SIG_B, true));
    }
}
