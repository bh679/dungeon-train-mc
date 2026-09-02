package games.brennan.dungeontrain.net.relay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parsing the author-only {@code /books/stats} reply.
 *
 * <p>The reception numbers are the old half and are simple. The two fields that matter here are
 * {@code status} and {@code private}, which tell a writer holding a LOOT copy of their own book that
 * it is theirs — and so decide whether the vote page offers them the padlock and their tallies, or a
 * stranger's thumbs and a report control aimed at themselves.</p>
 *
 * <p><b>Absent must stay absent.</b> A relay too old to answer them omits both, and the mod's
 * response to that is to stamp nothing at all, leaving whatever the author's-own-shelf pool path
 * already put on the stack. That only works if an absent {@code status} parses to null rather than
 * to some default state, which is what most of this pins.</p>
 */
class BookStatsClientParseTest {

    private static final String NUMBERS =
        "\"held\":2,\"completers\":1,\"opens\":3,\"rereads\":1,\"longestReadMs\":9000,"
            + "\"longestPageMs\":7000,\"longestPageIndex\":1,\"pageTurns\":1,"
            + "\"votesUp\":4,\"votesDown\":1";

    @Test
    @DisplayName("A released book of your own reports its status, and no withdrawal")
    void releasedOwnBook() {
        BookStatsClient.Stats s = BookStatsClient.parse(
            "{\"ok\":true,\"isAuthor\":true," + NUMBERS + ",\"status\":\"approved\"}");
        assertTrue(s.isAuthor());
        assertEquals("approved", s.status());
        assertFalse(s.isPrivate(), "the relay omits private rather than sending false");
        assertEquals(4, s.votesUp());
        assertEquals(1, s.votesDown());
    }

    @Test
    @DisplayName("A withdrawn book reports both — the padlock has to draw the state it is really in")
    void withdrawnOwnBook() {
        BookStatsClient.Stats s = BookStatsClient.parse(
            "{\"ok\":true,\"isAuthor\":true," + NUMBERS + ",\"status\":\"approved\",\"private\":true}");
        assertEquals("approved", s.status());
        assertTrue(s.isPrivate());
    }

    @Test
    @DisplayName("An older relay says neither, and that is NOT 'approved, in circulation'")
    void olderRelaySaysNothing() {
        BookStatsClient.Stats s = BookStatsClient.parse("{\"ok\":true,\"isAuthor\":true," + NUMBERS + "}");
        assertTrue(s.isAuthor());
        assertNull(s.status(), "absent must be null — it is what tells the greeter to stamp nothing");
        assertFalse(s.isPrivate());
    }

    @Test
    @DisplayName("A blank, null or non-string status is 'the relay did not say', not a state")
    void garbledStatusReadsAsAbsent() {
        for (String raw : new String[] {"\"status\":\"\"", "\"status\":null", "\"status\":{}", "\"status\":7"}) {
            BookStatsClient.Stats s = BookStatsClient.parse(
                "{\"ok\":true,\"isAuthor\":true," + NUMBERS + "," + raw + "}");
            assertNull(s.status(), raw + " must not become a moderation state");
        }
    }

    @Test
    @DisplayName("A non-author gets nothing to stamp")
    void nonAuthorCarriesNoState() {
        BookStatsClient.Stats s = BookStatsClient.parse("{\"ok\":true,\"isAuthor\":false}");
        assertFalse(s.isAuthor());
        assertNull(s.status());
        assertFalse(s.isPrivate());
    }

    @Test
    @DisplayName("A body that isn't an ok reply parses to null rather than throwing")
    void malformedRepliesAreNull() {
        assertNull(BookStatsClient.parse("{\"ok\":false}"));
        assertNull(BookStatsClient.parse("[]"));
        assertNull(BookStatsClient.parse("{}"));
    }
}
