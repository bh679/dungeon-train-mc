package games.brennan.dungeontrain.narrative;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When the vote page shows the counters, as a pure predicate.
 *
 * <p>Mirrors the guard in {@code BookVoteClientEvents.renderVoteCounts}. The counters replace the
 * thumbs on a book you wrote — they report rather than ask — so the rule has to cover two things the
 * numbers alone cannot say: whether the relay told us anything, and whether anything could have
 * voted yet.</p>
 */
class BookVoteCountsVisibilityTest {

    /** -1 stands for "the relay never sent a tally", which is not the same as a reported zero. */
    private static boolean shows(BookModerationState state, int up, int down) {
        if (!state.isOwn()) return false;          // the thumbs are still live on somebody else's book
        if (up < 0 || down < 0) return false;      // never told
        return !state.isWithheld() || up + down > 0;
    }

    @Test
    @DisplayName("Somebody else's book never shows counters — it still has the thumbs")
    void notOnStrangersBooks() {
        assertFalse(shows(BookModerationState.PUBLIC, 5, 1));
    }

    @Test
    @DisplayName("Your released book shows them, including an honest 0 / 0")
    void ownReleasedShowsEvenZero() {
        assertTrue(shows(BookModerationState.APPROVED, 5, 1));
        assertTrue(shows(BookModerationState.APPROVED, 0, 0),
            "nobody has voted yet is worth telling its author");
    }

    @Test
    @DisplayName("An absent tally shows nothing — never a fabricated zero")
    void absentTallyShowsNothing() {
        // A relay too old to send the field lands on -1. Rendering that as 0/0 would tell an author
        // nobody cared about their book, which is a different and untrue claim.
        assertFalse(shows(BookModerationState.APPROVED, -1, -1));
        assertFalse(shows(BookModerationState.DISLIKED, -1, -1));
    }

    @Test
    @DisplayName("A withheld book shows counters only if it actually earned some")
    void withheldOnlyWhenVoted() {
        for (BookModerationState s : new BookModerationState[] {
                BookModerationState.READING, BookModerationState.UNDECIDED, BookModerationState.DISLIKED}) {
            assertFalse(shows(s, 0, 0), s + " has never been out — 0/0 says nothing true");
            assertTrue(shows(s, 3, 1), s + " was out once and earned these");
        }
    }
}
