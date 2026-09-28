package games.brennan.dungeontrain.client.credits;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Community card reads the relay's Value Adders list as-is: its order, its levels, its ranks. */
final class RelayCommunityTest {

    @Test
    @DisplayName("keeps the relay's order and 1-based rank; a nameless, unflagged row is nobody")
    void parsesList() {
        List<RelayCommunity.Member> out = RelayCommunity.parse("{\"ok\":true,\"rows\":["
            + "{\"name\":\"Ten\",\"level\":30},{\"name\":\"\",\"level\":29},"
            + "{\"name\":\"Twelve\",\"level\":2},{\"level\":1},\"junk\"]}");
        assertEquals(List.of(new RelayCommunity.Member("Ten", 30, false, 1),
            new RelayCommunity.Member("Twelve", 2, false, 3)), out);
        assertTrue(RelayCommunity.parse("nope").isEmpty());
        assertTrue(RelayCommunity.parse("{\"rows\":{}}").isEmpty());
        assertTrue(RelayCommunity.parse(null).isEmpty());
    }

    @Test
    @DisplayName("a row flagged anonymous is kept unnamed with its position; the cache round-trips the rank")
    void anonymousRows() {
        List<RelayCommunity.Member> out = RelayCommunity.parse("{\"rows\":["
            + "{\"name\":\"Ten\",\"level\":30},{\"name\":\"\",\"level\":20,\"anonymous\":true}]}");
        assertEquals(List.of(new RelayCommunity.Member("Ten", 30, false, 1), new RelayCommunity.Member("", 20, true, 2)), out);
        assertEquals(2, RelayCommunity.parse("{\"rows\":[{\"name\":\"\",\"level\":20,\"anonymous\":true,\"rank\":2}]}").get(0).rank());
    }

    @Test
    @DisplayName("the you block is the caller's standing; absent, unranked (unlinked) or junk is null")
    void standing() {
        assertEquals(new RelayCommunity.Standing(3), RelayCommunity.parseStanding("{\"rows\":[],\"you\":{\"rank\":3}}"));
        assertNull(RelayCommunity.parseStanding("{\"rows\":[],\"you\":{\"rank\":0}}"));
        assertNull(RelayCommunity.parseStanding("{\"rows\":[]}"));
        assertNull(RelayCommunity.parseStanding("junk"));
    }
}
