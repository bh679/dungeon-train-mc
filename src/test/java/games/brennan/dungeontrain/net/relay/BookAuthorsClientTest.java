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
    @DisplayName("The host locale rides along, so a room is stocked from somebody its readers can read")
    void theQueryCarriesTheLocale() {
        String q = BookAuthorsClient.query("player", 10, 50, null, false, "zh_cn");
        assertTrue(q.contains("&lang=zh_cn"), q);
        assertTrue(q.startsWith("/books/authors?kind=player"), q);
        assertTrue(q.contains("&min=10"), q);
        assertTrue(q.contains("&max=50"), q);
    }

    @Test
    @DisplayName("No locale sends no lang — which is the relay's own count-every-language path")
    void noLocaleSendsNoLangAtAll() {
        assertFalse(BookAuthorsClient.query("player", 10, 0, null, false, null).contains("lang="));
        assertFalse(BookAuthorsClient.query("player", 10, 0, null, false, "  ").contains("lang="));
    }

    @Test
    @DisplayName("An underscored locale survives encoding intact")
    void localeIsEncodedNotMangled() {
        assertTrue(BookAuthorsClient.query("signature", 0, 0, null, true, "pt_br")
            .contains("&lang=pt_br"));
    }

    @Test
    @DisplayName("A well-formed reply parses into authors, newest fields and all")
    void parsesAuthors() {
        List<BookAuthorsClient.Author> authors = BookAuthorsClient.parse(
            "{\"ok\":true,\"authors\":[" +
            "{\"token\":\"p0123456789abcdef\",\"name\":\"Faulthurst\",\"count\":12}," +
            "{\"token\":\"sfedcba9876543210\",\"name\":\"The Conductor\",\"count\":31}]}", false).authors();

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
            "{\"token\":\"pgood\",\"name\":\"Fine\",\"count\":9}]}", false).authors();

        assertEquals(1, authors.size());
        assertEquals("pgood", authors.get(0).token());
    }

    @Test
    @DisplayName("Missing or junk optional fields degrade, never throw")
    void toleratesPartialEntries() {
        List<BookAuthorsClient.Author> authors = BookAuthorsClient.parse(
            "{\"ok\":true,\"authors\":[{\"token\":\"pabc\"}]}", false).authors();
        assertEquals(1, authors.size());
        assertEquals("", authors.get(0).name(), "an unnamed author is still a usable one");
        assertEquals(0, authors.get(0).count());

        // A negative count would make a nonsense of "more than N" — clamped, not carried.
        List<BookAuthorsClient.Author> negative = BookAuthorsClient.parse(
            "{\"ok\":true,\"authors\":[{\"token\":\"pabc\",\"name\":\"X\",\"count\":-4}]}", false).authors();
        assertEquals(0, negative.get(0).count());
    }

    @Test
    @DisplayName("A not-ok or malformed reply is NOT an answer — it must never read as 'nobody'")
    void badRepliesAreNotAnswers() {
        // The distinction this record exists for. A reply we could not read says nothing about the
        // corpus; caching it as "nobody qualifies" is what left whole worlds with bare shelves.
        for (String body : new String[] {
            "{\"ok\":false}",
            "{\"ok\":true}",
            "{\"ok\":true,\"authors\":\"nope\"}",
            "[]",
            "\"just a string\"",
        }) {
            BookAuthorsClient.Page page = BookAuthorsClient.parse(body, false);
            assertFalse(page.answered(), body);
            assertTrue(page.authors().isEmpty(), body);
        }
    }

    @Test
    @DisplayName("An empty list from a well-formed reply IS an answer — the relay said nobody")
    void anEmptyListIsStillAnAnswer() {
        BookAuthorsClient.Page page = BookAuthorsClient.parse("{\"ok\":true,\"authors\":[]}", false);
        assertTrue(page.answered());
        assertTrue(page.authors().isEmpty());
        assertFalse(page.relaxed());
    }

    @Test
    @DisplayName("`relaxed` carries the relay's own 'nobody was in band, so I went below it'")
    void relaxedIsCarried() {
        String body = "{\"ok\":true,\"relaxed\":true,\"authors\":"
            + "[{\"token\":\"pabc\",\"name\":\"Wren\",\"count\":3}]}";
        assertTrue(BookAuthorsClient.parse(body, false).relaxed());
    }

    @Test
    @DisplayName("A relay too old to know `relaxed` reads as not relaxed, never as junk")
    void absentRelaxedIsFalse() {
        String body = "{\"ok\":true,\"authors\":[{\"token\":\"pabc\",\"name\":\"Wren\",\"count\":3}]}";
        assertFalse(BookAuthorsClient.parse(body, false).relaxed());
        // ...and a garbled value is not a crash either: this runs on a network response.
        assertFalse(BookAuthorsClient.parse(
            "{\"ok\":true,\"relaxed\":7,\"authors\":[{\"token\":\"pabc\"}]}", false).relaxed());
        assertFalse(BookAuthorsClient.parse(
            "{\"ok\":true,\"relaxed\":null,\"authors\":[{\"token\":\"pabc\"}]}", false).relaxed());
    }

    @Test
    @DisplayName("A failed fetch is an empty page that is explicitly not an answer")
    void failedIsNotAnswered() {
        BookAuthorsClient.Page failed = BookAuthorsClient.Page.failed();
        assertFalse(failed.answered());
        assertFalse(failed.relaxed());
        assertTrue(failed.authors().isEmpty());
    }

    @Test
    @DisplayName("Only a kind=self page may mark its entries as the reader's own")
    void mineIsStampedFromTheKindThatWasAsked() {
        String body = "{\"ok\":true,\"authors\":[{\"token\":\"pabc\",\"name\":\"Me\",\"count\":3}]}";
        // The very same token, from the two directories it can arrive through. The `player` page
        // contains every author INCLUDING the reader, so the token alone cannot tell them apart —
        // only the request that produced it can, which is why `mine` is stamped here and nowhere else.
        assertTrue(BookAuthorsClient.parse(body, true).authors().get(0).mine());
        assertFalse(BookAuthorsClient.parse(body, false).authors().get(0).mine());
    }
}
