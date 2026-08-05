package games.brennan.dungeontrain.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The "left something behind" rule for {@code drift_gift_left}: a drifting-carriage container counts
 * as a gift only when it holds MORE at close than it did at open.
 *
 * <p>The one-directional part matters beyond the achievement — the same predicate decides whether the
 * cell is queued for upload, and queuing on a REMOVAL would make ordinary looting dirty (and re-upload)
 * every carriage a player empties.</p>
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
}
