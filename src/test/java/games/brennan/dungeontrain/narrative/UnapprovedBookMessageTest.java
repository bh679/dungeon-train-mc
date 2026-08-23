package games.brennan.dungeontrain.narrative;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The three sets of "where your book stands" lines shown on the vote page. */
class UnapprovedBookMessageTest {

    private static String keyOf(Component line) {
        return assertInstanceOf(TranslatableContents.class, line.getContents()).getKey();
    }

    @Test
    @DisplayName("Somebody else's book gets the train's usual question, not a line about its state")
    void publicProducesNoLine() {
        assertNull(UnapprovedBookMessage.forBook(BookModerationState.PUBLIC, "shared:1"));
        assertNull(UnapprovedBookMessage.forBook(null, "shared:1"));
    }

    @Test
    @DisplayName("A released book of your OWN does get a line — its page has no question and no thumbs")
    void ownApprovedProducesALine() {
        // Without this the page would be a button and no words: the thumbs come off every book you
        // wrote, and the question goes with them.
        assertTrue(keyOf(UnapprovedBookMessage.forBook(BookModerationState.APPROVED, "shared:3"))
            .startsWith("gui.dungeontrain.book_vote.status.released."));
    }

    @Test
    @DisplayName("One book always says the same thing — the vote page redraws every frame")
    void selectionIsStablePerBook() {
        // A random pick here would flicker through all ten lines at the refresh rate.
        String first = keyOf(UnapprovedBookMessage.forBook(BookModerationState.READING, "shared:41"));
        for (int i = 0; i < 100; i++) {
            assertEquals(first, keyOf(UnapprovedBookMessage.forBook(BookModerationState.READING, "shared:41")));
        }
    }

    @Test
    @DisplayName("Every own-book state reaches all LINE_COUNT of its own keys across books")
    void everyStateCoversItsWholeSet() {
        for (BookModerationState state : new BookModerationState[] {BookModerationState.APPROVED,
                BookModerationState.READING, BookModerationState.UNDECIDED, BookModerationState.DISLIKED}) {
            Set<String> seen = new HashSet<>();
            for (int id = 0; id < 4000; id++) {
                seen.add(keyOf(UnapprovedBookMessage.forBook(state, "shared:" + id)));
            }
            assertEquals(UnapprovedBookMessage.LINE_COUNT, seen.size(),
                state + " must draw from exactly LINE_COUNT distinct lines");
            for (int n = 1; n <= UnapprovedBookMessage.LINE_COUNT; n++) {
                assertTrue(seen.contains("gui.dungeontrain.book_vote.status." + state.messageKey() + "." + n),
                    "missing line " + n + " for " + state);
            }
        }
    }

    @Test
    @DisplayName("A null seed is tolerated rather than thrown on")
    void nullSeedStillProducesALine() {
        assertTrue(keyOf(UnapprovedBookMessage.forBook(BookModerationState.DISLIKED, null))
            .startsWith("gui.dungeontrain.book_vote.status.disliked."));
    }

    @Test
    @DisplayName("The three sets are distinct — a rejection never reads as 'still reading'")
    void setsDoNotOverlap() {
        assertEquals("reading", BookModerationState.READING.messageKey());
        assertEquals("undecided", BookModerationState.UNDECIDED.messageKey());
        assertEquals("disliked", BookModerationState.DISLIKED.messageKey());
    }
}
