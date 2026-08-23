package games.brennan.dungeontrain.narrative;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The 👍/👎 tally a writer's own book carries. */
class BookVoteCountsTagTest {

    @Test
    @DisplayName("A tally round-trips")
    void roundTrips() {
        ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);
        BookVoteCountsTag.stamp(stack, 7, 2);
        assertTrue(BookVoteCountsTag.has(stack));
        assertEquals(7, BookVoteCountsTag.up(stack));
        assertEquals(2, BookVoteCountsTag.down(stack));
    }

    @Test
    @DisplayName("ZERO votes is a real answer, and distinguishable from no answer")
    void zeroIsNotAbsent() {
        // This is the whole reason has() exists. "Nobody has voted on this yet" is worth showing;
        // "the relay never told us" must show nothing at all. Both read 0 through up()/down().
        ItemStack unstamped = new ItemStack(Items.WRITTEN_BOOK);
        assertFalse(BookVoteCountsTag.has(unstamped));
        assertEquals(0, BookVoteCountsTag.up(unstamped));

        ItemStack stamped = new ItemStack(Items.WRITTEN_BOOK);
        BookVoteCountsTag.stamp(stamped, 0, 0);
        assertTrue(BookVoteCountsTag.has(stamped), "a reported zero is still a report");
        assertEquals(0, BookVoteCountsTag.up(stamped));
        assertEquals(0, BookVoteCountsTag.down(stamped));
    }

    @Test
    @DisplayName("Re-stamping overwrites — the tally refreshes on every pickup")
    void reStampingRefreshes() {
        ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);
        BookVoteCountsTag.stamp(stack, 1, 0);
        BookVoteCountsTag.stamp(stack, 4, 3);
        assertEquals(4, BookVoteCountsTag.up(stack));
        assertEquals(3, BookVoteCountsTag.down(stack));
    }

    @Test
    @DisplayName("Negative counts are clamped rather than carried")
    void negativesAreClamped() {
        ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);
        BookVoteCountsTag.stamp(stack, -5, -1);
        assertEquals(0, BookVoteCountsTag.up(stack));
        assertEquals(0, BookVoteCountsTag.down(stack));
    }

    @Test
    @DisplayName("An empty stack is inert")
    void emptyStackIsInert() {
        BookVoteCountsTag.stamp(ItemStack.EMPTY, 3, 1);
        assertFalse(BookVoteCountsTag.has(ItemStack.EMPTY));
        assertEquals(0, BookVoteCountsTag.up(ItemStack.EMPTY));
    }
}
