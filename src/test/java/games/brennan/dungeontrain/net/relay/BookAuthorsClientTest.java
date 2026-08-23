package games.brennan.dungeontrain.net.relay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parsing the relay's author directory.
 *
 * <p>The properties worth pinning are all about a malformed or hostile reply: this runs on a network
 * response, and a room that throws here would take a book pickup down with it.</p>
 */
class BookAuthorsClientTest {

    @Test
    @DisplayName("A well-formed reply parses into authors, newest fields and all")
    void parsesAuthors() {
        List<BookAuthorsClient.Author> authors = BookAuthorsClient.parse(
            "{\"ok\":true,\"authors\":[" +
            "{\"token\":\"p0123456789abcdef\",\"name\":\"Faulthurst\",\"count\":12}," +
            "{\"token\":\"sfedcba9876543210\",\"name\":\"The Conductor\",\"count\":31}]}", false);

        assertEquals(2, authors.size());
        assertEquals("p0123456789abcdef", authors.get(0).token());
        assertEquals("Faulthurst", authors.get(0).name());
        assertEquals(12, authors.get(0).count());
        assertEquals(31, authors.get(1).count());
    }

    @Test
    @DisplayName("An author with no usable token is dropped rather than half-built")
    void dropsTokenlessEntries() {
        List<BookAuthorsClient.Author> authors = BookAuthorsClient.parse(
            "{\"ok\":true,\"authors\":[" +
            "{\"name\":\"No Token\",\"count\":9}," +
            "{\"token\":\"\",\"name\":\"Blank\",\"count\":9}," +
            "{\"token\":\"pgood\",\"name\":\"Fine\",\"count\":9}]}", false);

        assertEquals(1, authors.size());
        assertEquals("pgood", authors.get(0).token());
    }

    @Test
    @DisplayName("Missing or junk optional fields degrade, never throw")
    void toleratesPartialEntries() {
        List<BookAuthorsClient.Author> authors = BookAuthorsClient.parse(
            "{\"ok\":true,\"authors\":[{\"token\":\"pabc\"}]}", false);
        assertEquals(1, authors.size());
        assertEquals("", authors.get(0).name(), "an unnamed author is still a usable one");
        assertEquals(0, authors.get(0).count());

        // A negative count would make a nonsense of "more than N" — clamped, not carried.
        List<BookAuthorsClient.Author> negative = BookAuthorsClient.parse(
            "{\"ok\":true,\"authors\":[{\"token\":\"pabc\",\"name\":\"X\",\"count\":-4}]}", false);
        assertEquals(0, negative.get(0).count());
    }

    @Test
    @DisplayName("A not-ok, malformed or empty reply yields no authors instead of blowing up")
    void badRepliesAreEmpty() {
        assertTrue(BookAuthorsClient.parse("{\"ok\":false}", false).isEmpty());
        assertTrue(BookAuthorsClient.parse("{\"ok\":true}", false).isEmpty());
        assertTrue(BookAuthorsClient.parse("{\"ok\":true,\"authors\":\"nope\"}", false).isEmpty());
        assertTrue(BookAuthorsClient.parse("[]", false).isEmpty());
        assertTrue(BookAuthorsClient.parse("\"just a string\"", false).isEmpty());
    }

    @Test
    @DisplayName("Only a kind=self page may mark its entries as the reader's own")
    void mineIsStampedFromTheKindThatWasAsked() {
        String body = "{\"ok\":true,\"authors\":[{\"token\":\"pabc\",\"name\":\"Me\",\"count\":3}]}";
        // The very same token, from the two directories it can arrive through. The `player` page
        // contains every author INCLUDING the reader, so the token alone cannot tell them apart —
        // only the request that produced it can, which is why `mine` is stamped here and nowhere else.
        assertTrue(BookAuthorsClient.parse(body, true).get(0).mine());
        assertFalse(BookAuthorsClient.parse(body, false).get(0).mine());
    }
}
