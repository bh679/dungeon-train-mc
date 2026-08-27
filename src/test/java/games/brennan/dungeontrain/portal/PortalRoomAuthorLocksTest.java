package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.net.relay.BookAuthorsClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    @DisplayName("Two languages are two directory pages — the same band asked in Chinese and in French")
    void theLocaleIsPartOfTheDirectoryKey() {
        java.util.UUID holder = java.util.UUID.randomUUID();
        PortalRoomBooks books = new PortalRoomBooks(PortalRoomBooks.Kind.MIX, 1, 1, 1, 10, 50);
        String zh = PortalRoomAuthorLocks.directoryKey("player", holder, books, "zh_cn");
        String fr = PortalRoomAuthorLocks.directoryKey("player", holder, books, "fr_fr");
        assertFalse(zh.equals(fr), "one page must not stand in for the other language's answer");
        assertEquals(zh, PortalRoomAuthorLocks.directoryKey("player", holder, books, "zh_cn"));
    }

    @Test
    @DisplayName("The self page is keyed on the holder alone — your own shelf is not language-scoped")
    void theSelfKeyIgnoresTheLocale() {
        java.util.UUID holder = java.util.UUID.randomUUID();
        PortalRoomBooks books = new PortalRoomBooks(PortalRoomBooks.Kind.MIX, 1, 1, 1, 10, 50);
        assertEquals("self:" + holder, PortalRoomAuthorLocks.directoryKey("self", holder, books, "zh_cn"));
        assertEquals(PortalRoomAuthorLocks.directoryKey("self", holder, books, null),
            PortalRoomAuthorLocks.directoryKey("self", holder, books, "fr_fr"));
    }

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
        assertEquals(PortalRoomBooks.Share.values().length, seen.size(),
            "an even roll should reach every share across 60 rooms — the tally included");
    }

    // ---- whose shelf a Self room is allowed to ask for ----

    @Test
    @DisplayName("A room asks for the reader's own shelf only when they granted network access")
    void selfDirectoryNeedsConsent() {
        // The self page is the one directory call that names a person: it goes out as &uuid=.
        assertTrue(PortalRoomAuthorLocks.useSelfDirectory(PortalRoomBooks.Share.SELF, true));
        assertFalse(PortalRoomAuthorLocks.useSelfDirectory(PortalRoomBooks.Share.SELF, false));

        // The other two name nobody, so consent is not what gates them.
        for (PortalRoomBooks.Share share :
                List.of(PortalRoomBooks.Share.PLAYER, PortalRoomBooks.Share.SIGNATURE)) {
            assertFalse(PortalRoomAuthorLocks.useSelfDirectory(share, true), share + " is not a shelf");
            assertFalse(PortalRoomAuthorLocks.useSelfDirectory(share, false), share + " is not a shelf");
        }
        assertFalse(PortalRoomAuthorLocks.useSelfDirectory(null, true), "a missing share asks for nothing");
    }

    @Test
    @DisplayName("Without consent a Self room is not wedged waiting on a page it will never ask for")
    void deniedSelfRoomIsNotStuckPending() {
        // The whole point of the gate: no self fetch is kicked, so waiting on one would pin the room
        // at PENDING for as long as it stands. The random-player page is the only answer it needs.
        assertTrue(PortalRoomAuthorLocks.answered(false, false, true));
    }

    @Test
    @DisplayName("With consent a Self room still waits for its own page before giving up")
    void consentedSelfRoomWaitsForItsOwnPage() {
        assertFalse(PortalRoomAuthorLocks.answered(true, false, true));
        assertTrue(PortalRoomAuthorLocks.answered(true, true, true));
    }

    @Test
    @DisplayName("Nobody resolves until the random-player page lands, consent either way")
    void thePoolPageIsAlwaysWaitedOn() {
        assertFalse(PortalRoomAuthorLocks.answered(false, true, false));
        assertFalse(PortalRoomAuthorLocks.answered(true, true, false));
    }

    // ---- what a cached directory page is worth ----
    //
    // One page serves every room asking the same question, so these three states decide whether a
    // world has libraries at all. Conflating them is what once let a single relay timeout leave every
    // author room in a session with bare shelves until the server restarted.

    private static PortalRoomAuthorLocks.CachedPage page(BookAuthorsClient.Page p, long atMs) {
        return new PortalRoomAuthorLocks.CachedPage(p, atMs);
    }

    private static final BookAuthorsClient.Page NAMED =
        BookAuthorsClient.Page.of(List.of(author("a")), false);
    private static final BookAuthorsClient.Page NOBODY = BookAuthorsClient.Page.of(List.of(), false);

    @Test
    @DisplayName("A relaxed page is taken as-is — re-checking the band would re-empty the room")
    void aRelaxedPageBypassesTheRangeCheck() {
        // A room wanting 11+ books, and a relay that found nobody in the reader's language who
        // reaches that, so it went below the floor rather than serve an empty room. Filtering that
        // answer by the very band it was excused from is how the fix would be undone.
        PortalRoomBooks books = new PortalRoomBooks(PortalRoomBooks.Kind.MIX, 1, 1, 1,
            10, PortalRoomBooks.NO_MAXIMUM);
        List<BookAuthorsClient.Author> shortShelf =
            List.of(BookAuthorsClient.Author.other("p1", "Wren", 3));

        assertEquals(1, PortalRoomAuthorLocks.eligible(
            BookAuthorsClient.Page.of(shortShelf, true), books, true).size());
        // ...and an ORDINARY page is still filtered, so a page outliving an edit to the range cannot
        // hand back somebody the room has since stopped accepting.
        assertTrue(PortalRoomAuthorLocks.eligible(
            BookAuthorsClient.Page.of(shortShelf, false), books, true).isEmpty());
    }

    @Test
    @DisplayName("The reader's own shelf is exempt from the range whether relaxed or not")
    void theSelfPageIsNeverRangeFiltered() {
        PortalRoomBooks books = new PortalRoomBooks(PortalRoomBooks.Kind.MIX, 1, 1, 1,
            10, PortalRoomBooks.NO_MAXIMUM);
        List<BookAuthorsClient.Author> mine =
            List.of(BookAuthorsClient.Author.other("p1", "Me", 2));
        // Finding YOUR two books in a room is the point of the Self share; a floor meant for
        // strangers does not get to veto it.
        assertEquals(1, PortalRoomAuthorLocks.eligible(
            BookAuthorsClient.Page.of(mine, false), books, false).size());
    }

    @Test
    @DisplayName("A failed fetch is never an answer, however recent — a timeout is not 'nobody'")
    void aFailureIsNotAnAnswer() {
        long now = 1_000_000L;
        assertFalse(PortalRoomAuthorLocks.usable(page(BookAuthorsClient.Page.failed(), now), now));
        assertFalse(PortalRoomAuthorLocks.usable(null, now), "nothing cached is not an answer either");
    }

    @Test
    @DisplayName("A failed fetch backs off, then is retried rather than left to rot")
    void aFailureIsRetriedAfterItsBackoff() {
        long now = 1_000_000L;
        PortalRoomAuthorLocks.CachedPage failed = page(BookAuthorsClient.Page.failed(), now);
        assertFalse(PortalRoomAuthorLocks.shouldRefetch(failed, now),
            "one request per pending room per tick against a relay that has just failed");
        assertTrue(PortalRoomAuthorLocks.shouldRefetch(
            failed, now + PortalRoomAuthorLocks.FAILED_RETRY_MS));
        assertTrue(PortalRoomAuthorLocks.shouldRefetch(null, now), "nothing cached is always fetched");
    }

    @Test
    @DisplayName("A page that named somebody stands for the session — a room's library does not churn")
    void aNamedPageIsKept() {
        long now = 1_000_000L;
        long muchLater = now + PortalRoomAuthorLocks.EMPTY_PAGE_TTL_MS * 100;
        assertTrue(PortalRoomAuthorLocks.usable(page(NAMED, now), muchLater));
        assertFalse(PortalRoomAuthorLocks.shouldRefetch(page(NAMED, now), muchLater));
    }

    @Test
    @DisplayName("An empty answer is believed for a while, then asked again — the corpus grows")
    void anEmptyPageExpires() {
        long now = 1_000_000L;
        // Believed at first: the relay really did say nobody is inside this room's band.
        assertTrue(PortalRoomAuthorLocks.usable(page(NOBODY, now), now));
        assertFalse(PortalRoomAuthorLocks.shouldRefetch(page(NOBODY, now), now));

        // ...but not forever. Cached for the session it would keep a whole world bare, because this
        // page is shared by every room asking the same question.
        long expired = now + PortalRoomAuthorLocks.EMPTY_PAGE_TTL_MS;
        assertFalse(PortalRoomAuthorLocks.usable(page(NOBODY, now), expired));
        assertTrue(PortalRoomAuthorLocks.shouldRefetch(page(NOBODY, now), expired));
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
