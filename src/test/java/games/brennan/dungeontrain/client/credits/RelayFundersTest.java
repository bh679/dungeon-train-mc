package games.brennan.dungeontrain.client.credits;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Funders card reads the relay's donor list as-is: its order, its flags, its ranks. */
final class RelayFundersTest {

    @Test
    @DisplayName("keeps the relay's order and 1-based rank; a nameless, unflagged row is nobody")
    void parsesList() {
        List<RelayFunders.Funder> out = RelayFunders.parse("{\"ok\":true,\"rows\":["
            + "{\"name\":\"Ada\",\"amountAud\":120},{\"name\":\"\",\"amountAud\":90},"
            + "{\"name\":\"Grace\",\"amountAud\":46},{\"amountAud\":5},\"junk\"]}");
        assertEquals(List.of(new RelayFunders.Funder("Ada", 120, false, false, 1),
            new RelayFunders.Funder("Grace", 46, false, false, 3)), out);
        assertTrue(RelayFunders.parse("nope").isEmpty());
        assertTrue(RelayFunders.parse("{\"rows\":{}}").isEmpty());
        assertTrue(RelayFunders.parse(null).isEmpty());
    }

    @Test
    @DisplayName("anonymous keeps the figure; amountHidden (or a null amount) keeps the name; both together keep the row")
    void flags() {
        List<RelayFunders.Funder> out = RelayFunders.parse("{\"rows\":["
            + "{\"name\":\"\",\"amountAud\":90,\"anonymous\":true},"
            + "{\"name\":\"Bea\",\"amountAud\":null,\"amountHidden\":true},"
            + "{\"name\":\"Cy\",\"amountAud\":null},"
            + "{\"name\":\"\",\"amountAud\":null,\"anonymous\":true,\"amountHidden\":true}]}");
        assertEquals(List.of(
            new RelayFunders.Funder("", 90, true, false, 1),
            new RelayFunders.Funder("Bea", 0, false, true, 2),
            new RelayFunders.Funder("Cy", 0, false, true, 3),
            new RelayFunders.Funder("", 0, true, true, 4)), out);
        // The cache stores the rank explicitly, so a re-read after drops still matches a standing.
        assertEquals(4, RelayFunders.parse("{\"rows\":[{\"name\":\"\",\"anonymous\":true,\"amountHidden\":true,\"rank\":4}]}").get(0).rank());
    }

    @Test
    @DisplayName("the you block is the caller's standing; absent, unranked or junk is null")
    void standing() {
        assertEquals(new RelayFunders.Standing(2), RelayFunders.parseStanding("{\"rows\":[],\"you\":{\"rank\":2}}"));
        assertNull(RelayFunders.parseStanding("{\"rows\":[],\"you\":{\"rank\":0}}"));
        assertNull(RelayFunders.parseStanding("{\"rows\":[],\"you\":null}"));
        assertNull(RelayFunders.parseStanding("{\"rows\":[]}"));
        assertNull(RelayFunders.parseStanding("junk"));
    }
}
