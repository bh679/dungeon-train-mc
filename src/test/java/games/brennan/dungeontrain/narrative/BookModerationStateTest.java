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
    @DisplayName("An unknown status fails OPEN — an ordinary book is never tinted or greeted")
    void unknownStatusFailsOpen() {
        // The opposite of how kid-safety resolves an unknown, and on purpose: guessing wrong here
        // means telling a reader the train dislikes a perfectly ordinary community book.
        for (String s : new String[] {null, "", "   ", "nonsense", "APPROVED_MAYBE"}) {
            assertEquals(BookModerationState.APPROVED, BookModerationState.fromStatus(s),
                "unknown status '" + s + "' must read as released");
        }
    }

    @Test
    @DisplayName("Flags are matched case- and whitespace-insensitively")
    void flagsAreNormalised() {
        assertEquals(BookModerationState.DISLIKED, BookModerationState.fromStatus("  Rejected "));
        assertEquals(BookModerationState.READING, BookModerationState.fromStatus("PENDING"));
    }

    @Test
    @DisplayName("Only a withheld state has anything to say")
    void onlyWithheldStatesAreShown() {
        assertFalse(BookModerationState.APPROVED.isWithheld());
        assertNull(BookModerationState.APPROVED.messageKey(), "a released book says nothing about itself");

        for (BookModerationState s : new BookModerationState[] {
                BookModerationState.READING, BookModerationState.UNDECIDED, BookModerationState.DISLIKED}) {
            assertTrue(s.isWithheld());
            assertNotNull(s.messageKey(), s + " must have a message set");
        }
    }
}
