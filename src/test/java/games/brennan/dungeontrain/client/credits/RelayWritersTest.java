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
        assertEquals(List.of(new RelayWriters.Writer("Ada", 120), new RelayWriters.Writer("Grace", 20)), out);
        assertTrue(RelayWriters.parse("nope").isEmpty());
        assertTrue(RelayWriters.parse("{\"rows\":{}}").isEmpty());
    }
}
