package games.brennan.dungeontrain.narrative;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-author catalogue cache behind an author-locked portal room.
 *
 * <p>The load-bearing property is the one about emptiness: "this author has nothing" and "this author
 * has not been asked yet" must be distinguishable, or a room either waits forever on a catalogue that
 * is never coming or re-asks the relay on every pickup.</p>
 */
class AuthorBookPoolTest {

    @AfterEach
    void reset() {
        AuthorBookPool.clear();
    }

    @Test
    @DisplayName("A catalogue parses into the same shape an ordinary pool book has")
    void parsesIntoPoolBooks() {
        List<SharedBookPool.PoolBook> books = AuthorBookPool.parse(
            "{\"ok\":true,\"total\":2,\"books\":[" +
            "{\"id\":7,\"title\":\"One\",\"author\":\"Faulthurst\",\"pages\":[\"a\",\"b\"],\"lang\":\"en_us\"}," +
            "{\"id\":9,\"title\":\"Two\",\"author\":\"Faulthurst\",\"pages\":[\"c\"]}]}");

        assertEquals(2, books.size());
        assertEquals(7, books.get(0).id());
        assertEquals("Faulthurst", books.get(0).author());
        assertEquals(List.of("a", "b"), books.get(0).pages());
        assertEquals("en_us", books.get(0).lang());
        // An untagged book keeps a null lang, which LanguageFamily reads as English — same as the
        // shared pool, because it IS the shared pool's parse.
        assertEquals(null, books.get(1).lang());
    }

    @Test
    @DisplayName("A well-formed reply that says nothing useful yields no books")
    void unusableRepliesAreEmpty() {
        assertTrue(AuthorBookPool.parse("{\"ok\":false}").isEmpty());
        assertTrue(AuthorBookPool.parse("{\"ok\":true}").isEmpty());
        assertTrue(AuthorBookPool.parse("{\"ok\":true,\"books\":{}}").isEmpty());
        assertTrue(AuthorBookPool.parse("{}").isEmpty());
        assertTrue(AuthorBookPool.parse("[]").isEmpty());
    }

    @Test
    @DisplayName("Genuinely malformed JSON throws — and the fetch path is what catches it")
    void malformedJsonThrowsForTheFetchToCatch() {
        // Same contract as SharedBookPool.applyResponse: the parse is strict and refreshAsync's
        // catch-all is where a garbled body stops. Pinned here so nobody calls parse from a
        // synchronous path (a book pickup, say) without a guard of their own.
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
            () -> AuthorBookPool.parse("nonsense json"));
    }

    @Test
    @DisplayName("Fetched-but-empty is not the same as never fetched")
    void emptyCatalogueIsStillAnAnswer() {
        assertFalse(AuthorBookPool.isFetched("pnobody", false));

        // The relay said this author has nothing servable here. Caching that is what lets a room
        // rotate to somebody else instead of asking again on every single pickup.
        AuthorBookPool.put("pnobody", false, List.of());
        assertTrue(AuthorBookPool.isFetched("pnobody", false));
        assertTrue(AuthorBookPool.booksFor("pnobody", false).isEmpty());
    }

    @Test
    @DisplayName("A published catalogue is immutable to its readers")
    void publishedCataloguesAreImmutable() {
        List<SharedBookPool.PoolBook> source = new java.util.ArrayList<>(AuthorBookPool.parse(
            "{\"ok\":true,\"books\":[{\"id\":1,\"title\":\"T\",\"author\":\"A\",\"pages\":[\"p\"]}]}"));
        AuthorBookPool.put("ptoken", false, source);
        source.clear();   // the caller's list moving on must not empty a room's library

        assertEquals(1, AuthorBookPool.booksFor("ptoken", false).size());
    }

    @Test
    @DisplayName("Catalogues are bounded — a long session cannot grow them without limit")
    void cachedCataloguesAreBounded() {
        for (int i = 0; i < AuthorBookPool.MAX_CATALOGUES + 4; i++) {
            AuthorBookPool.put("token-" + i, false, List.of());
        }
        int live = 0;
        for (int i = 0; i < AuthorBookPool.MAX_CATALOGUES + 4; i++) {
            if (AuthorBookPool.isFetched("token-" + i, false)) live++;
        }
        assertEquals(AuthorBookPool.MAX_CATALOGUES, live);
        // The most recent survive; the first rooms visited are the ones dropped.
        assertTrue(AuthorBookPool.isFetched("token-" + (AuthorBookPool.MAX_CATALOGUES + 3), false));
        assertFalse(AuthorBookPool.isFetched("token-0", false));
    }

    @Test
    @DisplayName("An unknown token is empty, never null")
    void unknownTokenIsEmpty() {
        assertTrue(AuthorBookPool.booksFor("nope", false).isEmpty());
        assertTrue(AuthorBookPool.booksFor(null, false).isEmpty());
        assertTrue(AuthorBookPool.booksFor("  ", false).isEmpty());
        assertFalse(AuthorBookPool.isFetching(null, false));
    }

    @Test
    @DisplayName("A writer's own shelf and a stranger's view of it are DIFFERENT catalogues")
    void mineAndPublicDoNotShareACacheEntry() {
        // The relay's `player` directory lists every author, so the same p… token reaches the game
        // both ways: as the reader's own self entry, and as a stranger's shelf another room drew.
        // The two fetches return different books — only the first may contain withheld ones — so one
        // cache entry cannot stand for both. Keyed on the token alone this leaks whichever way the
        // race lands.
        List<SharedBookPool.PoolBook> withheld = AuthorBookPool.parse(
            "{\"ok\":true,\"books\":[" +
            "{\"id\":1,\"title\":\"Mine\",\"author\":\"A\",\"pages\":[\"p\"],\"status\":\"rejected\"}]}");
        AuthorBookPool.put("ptoken", true, withheld);

        // A stranger's room asking about the same author has not fetched anything...
        assertFalse(AuthorBookPool.isFetched("ptoken", false),
            "the writer's own fetch must not answer for a stranger's");
        assertTrue(AuthorBookPool.booksFor("ptoken", false).isEmpty(),
            "and must never hand a stranger's room the withheld books");

        // ...and once it has, the two coexist rather than overwriting each other.
        AuthorBookPool.put("ptoken", false, List.of());
        assertTrue(AuthorBookPool.isFetched("ptoken", true));
        assertEquals(1, AuthorBookPool.booksFor("ptoken", true).size(),
            "the writer's own catalogue survives a stranger's fetch of the same author");
        assertTrue(AuthorBookPool.booksFor("ptoken", false).isEmpty());
    }

    @Test
    @DisplayName("The reverse race too: a stranger's fetch must not starve the writer's own")
    void publicFetchDoesNotSatisfyMine() {
        AuthorBookPool.put("ptoken", false, List.of());
        assertTrue(AuthorBookPool.isFetched("ptoken", false));
        // Without this, isFetched short-circuits the writer's refresh and they never see their own
        // withheld books at all — the same bug, failing quiet instead of loud.
        assertFalse(AuthorBookPool.isFetched("ptoken", true));
    }

    @Test
    @DisplayName("A catalogue carries each book's moderation status through the shared parse")
    void catalogueCarriesStatus() {
        List<SharedBookPool.PoolBook> books = AuthorBookPool.parse(
            "{\"ok\":true,\"books\":[" +
            "{\"id\":1,\"title\":\"A\",\"author\":\"A\",\"pages\":[\"p\"],\"status\":\"pending\"}," +
            "{\"id\":2,\"title\":\"B\",\"author\":\"A\",\"pages\":[\"p\"]}]}");
        assertEquals("pending", books.get(0).status());
        assertTrue(books.get(0).isWithheld());
        // No status field at all — an ordinary community book, which is what every catalogue but the
        // reader's own contains.
        assertEquals(SharedBookPool.STATUS_UNKNOWN, books.get(1).status());
        assertFalse(books.get(1).isWithheld());
        assertFalse(books.get(1).isOwn());
        assertTrue(books.get(0).isOwn());
    }
}
