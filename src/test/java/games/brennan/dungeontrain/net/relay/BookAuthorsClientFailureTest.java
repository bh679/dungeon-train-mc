package games.brennan.dungeontrain.net.relay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Null is not the same as empty.
 *
 * <p>This distinction is the whole reason a library room can tell "the relay is unreachable" from
 * "the corpus has nobody in range". Collapsing them once already cost a session's worth of bare
 * shelves that blamed their own settings for it, so it is pinned here rather than left to a comment.
 */
class BookAuthorsClientFailureTest {

    @Test
    @DisplayName("A body that is not an answer at all parses to null, not to an empty list")
    void unusableBodiesAreNull() {
        // None of these tell us the corpus has nobody in range. They tell us we did not get an answer.
        assertNull(BookAuthorsClient.parse("[]"), "an array is not the answer shape");
        assertNull(BookAuthorsClient.parse("\"nope\""), "a bare string is not the answer shape");
        assertNull(BookAuthorsClient.parse("{}"), "no ok flag");
        assertNull(BookAuthorsClient.parse("{\"ok\":false}"), "the relay said it failed");
        assertNull(BookAuthorsClient.parse("{\"ok\":true}"), "ok but no authors array");
        assertNull(BookAuthorsClient.parse("{\"ok\":true,\"authors\":\"nope\"}"), "authors is not a list");
    }

    @Test
    @DisplayName("A well-formed answer with nobody in it is an EMPTY list — a real fact about the corpus")
    void emptyAnswerIsEmptyNotNull() {
        List<BookAuthorsClient.Author> out = BookAuthorsClient.parse("{\"ok\":true,\"authors\":[]}");
        assertNotNull(out, "the relay answered; that answer happens to be nobody");
        assertTrue(out.isEmpty());
    }

    @Test
    @DisplayName("A real answer still parses to its authors")
    void realAnswerStillParses() {
        List<BookAuthorsClient.Author> out = BookAuthorsClient.parse(
            "{\"ok\":true,\"authors\":[{\"token\":\"pabc\",\"name\":\"Marrowvane\",\"count\":47}]}");
        assertNotNull(out);
        assertEquals(1, out.size());
        assertEquals("Marrowvane", out.get(0).name());
        assertEquals(47, out.get(0).count());
    }
}
