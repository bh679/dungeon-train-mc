package games.brennan.dungeontrain.narrative;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The relay's moderation flag → what the writer is actually shown. */
class BookModerationStateTest {

    @Test
    @DisplayName("Each relay flag maps to the state the writer is shown")
    void flagsMapToStates() {
        assertEquals(BookModerationState.APPROVED, BookModerationState.fromStatus("approved"));
        assertEquals(BookModerationState.READING, BookModerationState.fromStatus("pending"));
        assertEquals(BookModerationState.DISLIKED, BookModerationState.fromStatus("rejected"));
        // Two relay states, one piece of news: both mean "read, no verdict".
        assertEquals(BookModerationState.UNDECIDED, BookModerationState.fromStatus("flagged"));
        assertEquals(BookModerationState.UNDECIDED, BookModerationState.fromStatus("needs_human_review"));
    }

    @Test
    @DisplayName("An absent status is PUBLIC — somebody's book, and we were told nothing")
    void absentStatusIsPublic() {
        // Only the author's own shelf carries a status at all, so "no status" is the ordinary
        // community book every player meets, not a released book of the reader's own.
        for (String s : new String[] {null, "", "   "}) {
            assertEquals(BookModerationState.PUBLIC, BookModerationState.fromStatus(s));
            assertFalse(BookModerationState.fromStatus(s).isOwn());
        }
    }

    @Test
    @DisplayName("An unrecognised status is treated as the reader's own, released — never as a verdict")
    void unknownStatusFailsOpen() {
        // A state a newer relay knows and this jar does not. It still arrived on the reader's own
        // shelf, so the withdraw control belongs on it — but nothing is claimed about a verdict this
        // jar cannot name. Guessing "withheld" here would tell a reader the train dislikes a book
        // when it may have said the opposite.
        for (String s : new String[] {"nonsense", "sixth_state_from_a_newer_relay"}) {
            BookModerationState st = BookModerationState.fromStatus(s);
            assertEquals(BookModerationState.APPROVED, st, "unknown status '" + s + "'");
            assertTrue(st.isOwn());
            assertFalse(st.isWithheld());
        }
    }

    @Test
    @DisplayName("isOwn is what the vote page keys the thumbs off")
    void ownershipIsSeparateFromWithholding() {
        // The thumbs come off ANY book you wrote, released or not — the relay weights selection by
        // votes and never checks authorship, so your own shelf plus a thumbs-up is a self-upvoting
        // machine. isWithheld() alone would have left the released half of that hole open.
        assertFalse(BookModerationState.PUBLIC.isOwn());
        for (BookModerationState s : new BookModerationState[] {BookModerationState.APPROVED,
                BookModerationState.READING, BookModerationState.UNDECIDED, BookModerationState.DISLIKED}) {
            assertTrue(s.isOwn(), s + " is one of the reader's own");
        }
        assertFalse(BookModerationState.APPROVED.isWithheld(), "released is yours but not held back");
    }

    @Test
    @DisplayName("Flags are matched case- and whitespace-insensitively")
    void flagsAreNormalised() {
        assertEquals(BookModerationState.DISLIKED, BookModerationState.fromStatus("  Rejected "));
        assertEquals(BookModerationState.READING, BookModerationState.fromStatus("PENDING"));
    }

    @Test
    @DisplayName("Protest needs a verdict to protest against")
    void protestNeedsAVerdict() {
        // READING is "submitted, nothing has read it yet". There is no decision there to disagree
        // with, so the page offers nothing to press rather than inviting an argument with nobody.
        assertFalse(BookModerationState.READING.canProtest(), "nothing has judged it yet");
        assertTrue(BookModerationState.UNDECIDED.canProtest(), "read and held — that is a call to object to");
        assertTrue(BookModerationState.DISLIKED.canProtest());
        assertFalse(BookModerationState.APPROVED.canProtest(), "nothing to object to when it is released");
        assertFalse(BookModerationState.PUBLIC.canProtest(), "and somebody else's is not yours to protest");
    }

    @Test
    @DisplayName("Every state but PUBLIC has something to say")
    void everyOwnStateHasALine() {
        assertNull(BookModerationState.PUBLIC.messageKey(),
            "somebody else's book gets the train's usual question instead");
        for (BookModerationState s : new BookModerationState[] {BookModerationState.APPROVED,
                BookModerationState.READING, BookModerationState.UNDECIDED, BookModerationState.DISLIKED}) {
            assertNotNull(s.messageKey(), s + " must have a message set");
        }
    }
}
