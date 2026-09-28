package games.brennan.dungeontrain.narrative;

import games.brennan.dungeontrain.net.relay.BookStatsClient;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a LOOT copy of your own book learns about itself when the relay confirms you wrote it.
 *
 * <p>This is the seam that decides which vote page a writer gets. Before it, the author's own state
 * was stamped in one place only — the {@code mine=1} pool fetch behind an author-locked portal room —
 * so a writer who found their own book in a chest was shown a stranger's page: thumbs they could vote
 * themselves up with, a report control aimed at themselves, and no padlock.</p>
 */
class FamiliarBookOwnStateTest {

    private static BookStatsClient.Stats stats(boolean isAuthor, String status, boolean isPrivate) {
        return new BookStatsClient.Stats(isAuthor, 2, 1, 3, 9000L, 7000L, 1, 1, 1, 4, 1,
                status, isPrivate);
    }

    @Test
    @DisplayName("A released book of your own carries its state, its tally and no withdrawal")
    void releasedOwnBook() {
        ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);
        FamiliarBookGreeter.stampOwnState(stack, stats(true, "approved", false));

        assertEquals(BookModerationState.APPROVED, BookModerationTag.read(stack));
        assertTrue(BookModerationTag.read(stack).isOwn(), "this is what the author controls key off");
        assertFalse(BookPrivateTag.isPrivate(stack));
        assertTrue(BookVoteCountsTag.has(stack));
        assertEquals(4, BookVoteCountsTag.up(stack));
        assertEquals(1, BookVoteCountsTag.down(stack));
    }

    @Test
    @DisplayName("A withdrawn book says so — or the padlock would offer to withdraw it twice")
    void withdrawnOwnBook() {
        ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);
        FamiliarBookGreeter.stampOwnState(stack, stats(true, "approved", true));
        assertTrue(BookPrivateTag.isPrivate(stack));
    }

    @Test
    @DisplayName("An older relay's silence stamps no state at all — the tally still lands")
    void silenceStampsNoState() {
        ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);
        FamiliarBookGreeter.stampOwnState(stack, stats(true, null, false));

        assertEquals(BookModerationState.PUBLIC, BookModerationTag.read(stack),
            "an absent status must not be read as a verdict");
        assertTrue(BookVoteCountsTag.has(stack), "the tally is a separate field and still arrived");
    }

    @Test
    @DisplayName("Silence never overwrites a state the author's own shelf already stamped")
    void silenceLeavesAnExistingStateAlone() {
        // The case the explicit null guard exists for: a copy taken off the writer's own shelf inside
        // an author-locked room carries a withheld state, and this endpoint — which may know nothing
        // about it — must not quietly re-label it as an ordinary released book.
        ItemStack shelfCopy = new ItemStack(Items.WRITTEN_BOOK);
        BookModerationTag.stamp(shelfCopy, BookModerationState.DISLIKED);
        BookPrivateTag.stamp(shelfCopy, true);

        FamiliarBookGreeter.stampOwnState(shelfCopy, stats(true, null, false));

        assertEquals(BookModerationState.DISLIKED, BookModerationTag.read(shelfCopy));
        assertTrue(BookPrivateTag.isPrivate(shelfCopy), "nor may it put a withdrawn book back");
    }

    @Test
    @DisplayName("A state this jar has never heard of is an ordinary released book of your own")
    void unknownStatusFailsOpen() {
        ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);
        FamiliarBookGreeter.stampOwnState(stack, stats(true, "some_future_state", false));
        // Fails OPEN, per BookModerationState.fromStatus: it still came back from an author-gated
        // lookup, so the withdraw control belongs on it — but no verdict is claimed.
        assertEquals(BookModerationState.APPROVED, BookModerationTag.read(stack));
    }

    @Test
    @DisplayName("Nothing is stamped for a holder who did not write it")
    void nonAuthorIsNeverStamped() {
        ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);
        FamiliarBookGreeter.stampOwnState(stack, stats(false, null, false));

        assertEquals(BookModerationState.PUBLIC, BookModerationTag.read(stack));
        assertFalse(BookVoteCountsTag.has(stack),
            "a stranger's copy must stay byte-identical — a tally on it would claim it was theirs");
        assertFalse(BookPrivateTag.isPrivate(stack));
    }

    @Test
    @DisplayName("An empty stack and a missing reply are both inert")
    void inertEdges() {
        FamiliarBookGreeter.stampOwnState(ItemStack.EMPTY, stats(true, "approved", true));
        assertFalse(BookPrivateTag.isPrivate(ItemStack.EMPTY));

        ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);
        FamiliarBookGreeter.stampOwnState(stack, null);
        assertEquals(BookModerationState.PUBLIC, BookModerationTag.read(stack));
        assertFalse(BookVoteCountsTag.has(stack));
    }
}
