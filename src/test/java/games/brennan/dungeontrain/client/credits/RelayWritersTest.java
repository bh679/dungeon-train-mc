package games.brennan.dungeontrain.client.credits;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Writers card reads the books_written board and keeps only writers past the bar. */
final class RelayWritersTest {

    @Test
    @DisplayName("keeps the board's order, drops those under the bar, the anonymous and the nameless")
    void parsesBoard() {
        List<RelayWriters.Writer> out = RelayWriters.parse("{\"ok\":true,\"cat\":\"books_written\",\"rows\":["
            + "{\"name\":\"Ada\",\"score\":120},{\"name\":\"Anonymous\",\"score\":90},"
            + "{\"name\":\"Grace\",\"score\":20},{\"name\":\"Alan\",\"score\":19},"
            + "{\"name\":\"\",\"score\":50},{\"score\":77},\"junk\"]}");
        assertEquals(List.of(new RelayWriters.Writer("Ada", 120, false, 1), new RelayWriters.Writer("Grace", 20, false, 3)), out);
        assertTrue(RelayWriters.parse("nope").isEmpty());
        assertTrue(RelayWriters.parse("{\"rows\":{}}").isEmpty());
    }

    @Test
    @DisplayName("a row flagged anonymous is kept unnamed with its board position; the cache round-trips it")
    void anonymousRows() {
        List<RelayWriters.Writer> out = RelayWriters.parse("{\"rows\":["
            + "{\"name\":\"Ada\",\"score\":120},{\"name\":\"\",\"score\":90,\"anonymous\":true},"
            + "{\"name\":\"Anonymous\",\"score\":40},{\"name\":\"\",\"score\":5,\"anonymous\":true}]}");
        assertEquals(List.of(new RelayWriters.Writer("Ada", 120, false, 1), new RelayWriters.Writer("", 90, true, 2)), out);
        // The cache stores the rank explicitly, so a re-read after drops still matches a standing.
        List<RelayWriters.Writer> cached = RelayWriters.parse("{\"rows\":[{\"name\":\"\",\"score\":90,\"anonymous\":true,\"rank\":2}]}");
        assertEquals(2, cached.get(0).rank());
    }

    @Test
    @DisplayName("the you block is the caller's standing; absent or unranked is null")
    void standing() {
        assertEquals(new RelayWriters.Standing(2, 90),
            RelayWriters.parseStanding("{\"rows\":[],\"you\":{\"rank\":2,\"score\":90}}"));
        assertEquals(null, RelayWriters.parseStanding("{\"rows\":[],\"you\":null}"));
        assertEquals(null, RelayWriters.parseStanding("{\"rows\":[]}"));
        assertEquals(null, RelayWriters.parseStanding("junk"));
    }
}
