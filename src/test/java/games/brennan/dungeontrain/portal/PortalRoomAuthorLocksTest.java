package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.net.relay.BookAuthorsClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rotation rule behind an author-locked room: which candidate it reaches for next.
 *
 * <p>This is the part with the two ways to go wrong. Refuse a repeat too hard and a small corpus
 * leaves a room with no author at all; refuse too little and every room in a session is the same
 * person's library.</p>
 */
class PortalRoomAuthorLocksTest {

    private static BookAuthorsClient.Author author(String token) {
        return BookAuthorsClient.Author.other(token, token.toUpperCase(java.util.Locale.ROOT), 12);
    }

    private static final List<BookAuthorsClient.Author> THREE =
        List.of(author("a"), author("b"), author("c"));

    /** Always take the first of whatever list the rule offers, so the CHOICE SET is what is asserted. */
    private static final java.util.function.IntUnaryOperator FIRST = size -> 0;

    @Test
    @DisplayName("A candidate this room already tried and found empty is never offered again")
    void rejectedCandidatesAreSkipped() {
        Optional<BookAuthorsClient.Author> pick =
            PortalRoomAuthorLocks.choose(THREE, Set.of("a"), List.of(), FIRST);
        assertEquals("b", pick.orElseThrow().token());

        // Everything tried and found wanting: the room has nowhere left to go and says so, which is
        // what makes it fall back to ordinary books rather than loop.
        assertTrue(PortalRoomAuthorLocks.choose(THREE, Set.of("a", "b", "c"), List.of(), FIRST).isEmpty());
    }

    @Test
    @DisplayName("An author the last few rooms used is passed over while somebody else is free")
    void recentAuthorsArePassedOver() {
        Optional<BookAuthorsClient.Author> pick =
            PortalRoomAuthorLocks.choose(THREE, Set.of(), List.of("a", "b"), FIRST);
        assertEquals("c", pick.orElseThrow().token(), "the one nobody has just read");
    }

    @Test
    @DisplayName("On a small corpus a repeat beats an empty room")
    void recentPreferenceIsSoftNotHard() {
        // Every candidate was used recently. Refusing them all would leave this room bookless.
        Optional<BookAuthorsClient.Author> pick =
            PortalRoomAuthorLocks.choose(THREE, Set.of(), List.of("a", "b", "c"), FIRST);
        assertEquals("a", pick.orElseThrow().token());

        // ...and the rejected set still wins over the recency preference: a candidate this room has
        // already proved empty is no use however long ago somebody else read them.
        Optional<BookAuthorsClient.Author> narrowed =
            PortalRoomAuthorLocks.choose(THREE, Set.of("a"), List.of("a", "b", "c"), FIRST);
        assertEquals("b", narrowed.orElseThrow().token());
    }

    @Test
    @DisplayName("An empty directory offers nobody rather than throwing on a pickup")
    void emptyDirectoryIsEmpty() {
        assertTrue(PortalRoomAuthorLocks.choose(List.of(), Set.of(), List.of(), FIRST).isEmpty());
    }

    // ---- which kind a Random room turns out to be ----

    @Test
    @DisplayName("A room settles on one share and keeps it — a room is not three libraries")
    void theRolledShareIsStablePerRoom() {
        PortalRoomBooks books = new PortalRoomBooks(PortalRoomBooks.Kind.MIX, 2, 3, 4,
            10, PortalRoomBooks.NO_MAXIMUM);
        for (int pair = 0; pair < 40; pair++) {
            PortalRoomBooks.Share first = PortalRoomAuthorLocks.effectiveShare(pair, books);
            // Asked again — after a relocation, a re-stamp or a restart, all of which re-derive
            // rather than remember — the room is the same library it was.
            for (int again = 0; again < 5; again++) {
                assertEquals(first, PortalRoomAuthorLocks.effectiveShare(pair, books));
            }
        }
        // A null setting still answers rather than throwing on the stocking pass.
        assertEquals(PortalRoomBooks.Share.PLAYER, PortalRoomAuthorLocks.effectiveShare(1, null));
    }

    @Test
    @DisplayName("Different rooms roll differently — a world is not one author's shelves throughout")
    void differentRoomsRollDifferently() {
        PortalRoomBooks books = new PortalRoomBooks(PortalRoomBooks.Kind.MIX);
        Set<PortalRoomBooks.Share> seen = new java.util.HashSet<>();
        for (int pair = 0; pair < 60; pair++) {
            seen.add(PortalRoomAuthorLocks.effectiveShare(pair, books));
        }
        assertEquals(3, seen.size(), "an even three-way roll should reach all three across 60 rooms");
    }

    @Test
    @DisplayName("The choice is drawn from the whole eligible set, not just its head")
    void everyEligibleCandidateIsReachable() {
        for (int i = 0; i < THREE.size(); i++) {
            int index = i;
            Optional<BookAuthorsClient.Author> pick =
                PortalRoomAuthorLocks.choose(THREE, Set.of(), List.of(), size -> index);
            assertEquals(THREE.get(i).token(), pick.orElseThrow().token());
        }
    }
}
