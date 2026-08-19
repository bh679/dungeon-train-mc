package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.net.relay.BookAuthorsClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether a library room may be drawn yet.
 *
 * <p>The property that matters is asymmetric: being wrong in one direction shows a player a hall of
 * bare shelves, and being wrong in the other only means a room appears a few seconds later than it
 * could have. So "not answered yet" and "answered with nobody" must both read as not-ready, and only
 * a real answer holding a real candidate may open the gate.</p>
 */
class PortalLibraryReadinessTest {

    @AfterEach
    void reset() {
        PortalRoomAuthorLocks.clear();
    }

    private static PortalRoomBooks band(int min, int max) {
        return new PortalRoomBooks(PortalRoomBooks.Kind.MIX, 1, 1, 1, min, max);
    }

    private static BookAuthorsClient.Author author(String name, int count) {
        return new BookAuthorsClient.Author("p" + name, name, count);
    }

    @Test
    @DisplayName("A room that stocks nobody is always drawable — it needs no relay at all")
    void unlockedRoomsAreAlwaysReady() {
        assertTrue(PortalRoomAuthorLocks.canFill(PortalRoomBooks.DEFAULT));
        assertTrue(PortalRoomAuthorLocks.canFill(null));
    }

    @Test
    @DisplayName("Before the relay answers, a library room is not drawable")
    void unansweredIsNotReady() {
        assertFalse(PortalRoomAuthorLocks.canFill(band(10, 24)),
            "no page has been fetched, so nothing is known about who could fill this");
    }

    @Test
    @DisplayName("An answer of nobody is still not drawable — the room would stand empty")
    void answeredWithNobodyIsNotReady() {
        PortalRoomBooks books = band(10, 24);
        for (String kind : PortalRoomAuthorLocks.directoryKindsFor(books)) {
            PortalRoomAuthorLocks.seedDirectory(PortalRoomAuthorLocks.directoryKey(kind, books), List.of());
        }
        assertFalse(PortalRoomAuthorLocks.canFill(books));
    }

    @Test
    @DisplayName("One real candidate inside the range opens the gate")
    void aCandidateInRangeIsReady() {
        PortalRoomBooks books = band(10, 24);
        List<String> kinds = PortalRoomAuthorLocks.directoryKindsFor(books);
        for (String kind : kinds) {
            PortalRoomAuthorLocks.seedDirectory(PortalRoomAuthorLocks.directoryKey(kind, books),
                List.of(author("Faulthurst", 12)));
        }
        assertTrue(PortalRoomAuthorLocks.canFill(books));
    }

    @Test
    @DisplayName("Authors outside the range do not count as an answer worth drawing on")
    void candidatesOutsideTheRangeDoNotCount() {
        PortalRoomBooks books = band(24, 120);
        for (String kind : PortalRoomAuthorLocks.directoryKindsFor(books)) {
            // Answered, and everybody on the page is too prolific or not prolific enough.
            PortalRoomAuthorLocks.seedDirectory(PortalRoomAuthorLocks.directoryKey(kind, books),
                List.of(author("Wren", 3), author("Emberwrit", 138)));
        }
        assertFalse(PortalRoomAuthorLocks.canFill(books));
    }

    @Test
    @DisplayName("A room only waits on the pages its weights can actually reach")
    void onlyReachablePagesAreWaitedOn() {
        // Weighted entirely to signatures: the player page is never consulted, so waiting on one
        // would keep this room out of the pool for ever.
        PortalRoomBooks signatureOnly = new PortalRoomBooks(PortalRoomBooks.Kind.MIX, 0, 0, 1, 10, 0);
        assertEquals(List.of(PortalRoomBooks.Share.SIGNATURE.directoryKind()),
            PortalRoomAuthorLocks.directoryKindsFor(signatureOnly));

        PortalRoomAuthorLocks.seedDirectory(
            PortalRoomAuthorLocks.directoryKey(PortalRoomBooks.Share.SIGNATURE.directoryKind(), signatureOnly),
            List.of(author("The Conductor", 27)));
        assertTrue(PortalRoomAuthorLocks.canFill(signatureOnly),
            "the one page it consults has answered with a candidate");
    }

    @Test
    @DisplayName("A Self weight still needs the player page, since Self falls back to it")
    void selfWeightWaitsOnThePlayerPage() {
        PortalRoomBooks selfOnly = new PortalRoomBooks(PortalRoomBooks.Kind.MIX, 1, 0, 0, 10, 0);
        assertTrue(PortalRoomAuthorLocks.directoryKindsFor(selfOnly)
                .contains(PortalRoomBooks.Share.PLAYER.directoryKind()),
            "a reader who has written nothing rotates into the player pool, so that page is needed");
    }
}
