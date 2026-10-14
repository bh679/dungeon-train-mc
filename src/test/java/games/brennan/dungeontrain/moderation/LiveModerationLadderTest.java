package games.brennan.dungeontrain.moderation;

import games.brennan.dungeontrain.moderation.LiveModerationLadder.Action;
import games.brennan.dungeontrain.moderation.LiveModerationLadder.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The relay's verdict is read exactly; anything else is silence, not punishment. */
final class LiveModerationLadderTest {

    @Test
    @DisplayName("a strike answer parses to its action and counts")
    void parsesStrike() {
        Verdict v = LiveModerationLadder.parse("{\"ok\":true,\"strikes\":2,\"removals\":0,\"action\":\"warn\",\"banned\":false}");
        assertNotNull(v);
        assertEquals(2, v.strikes());
        assertEquals(Action.WARN, v.action());
        assertTrue(v.mayStream(0, 0));
    }

    @Test
    @DisplayName("a ban answer carries its remaining time, measured from arrival")
    void parsesBan() {
        Verdict v = LiveModerationLadder.parse("{\"ok\":true,\"strikes\":0,\"removals\":3,\"action\":\"ban\",\"banned\":true,\"remainingSec\":100,"
            + "\"evidence\":[{\"id\":9,\"text\":\"you zorp\",\"terms\":[\"zorp\"]},{\"id\":\"bad\"},{\"id\":8,\"text\":null,\"terms\":[]}]}");
        assertNotNull(v);
        assertEquals(Action.BAN, v.action());
        assertEquals(2, v.evidence().size());
        assertEquals(9, v.evidence().get(0).id());
        assertEquals("you zorp", v.evidence().get(0).text());
        assertEquals("", v.evidence().get(1).text());
        assertFalse(v.mayStream(50_000, 0));
        assertTrue(v.mayStream(100_000, 0));
        assertEquals(50, v.remainingAt(50_000, 0));
    }

    @Test
    @DisplayName("a permanent ban never lapses")
    void permanent() {
        Verdict v = LiveModerationLadder.parse("{\"ok\":true,\"strikes\":0,\"removals\":5,\"banned\":true,\"permanent\":true}");
        assertNotNull(v);
        assertFalse(v.mayStream(Long.MAX_VALUE / 2, 0));
    }

    @Test
    @DisplayName("not ok, missing strikes, or junk → null")
    void rejectsMalformed() {
        assertNull(LiveModerationLadder.parse(null));
        assertNull(LiveModerationLadder.parse(""));
        assertNull(LiveModerationLadder.parse("nope"));
        assertNull(LiveModerationLadder.parse("{\"ok\":false,\"strikes\":1}"));
        assertNull(LiveModerationLadder.parse("{\"ok\":true}"));
        assertNull(LiveModerationLadder.parse("[]"));
    }

    @Test
    @DisplayName("the local guess warns until the ladder's removal step")
    void localGuess() {
        LiveWordList.Ladder ladder = new LiveWordList.Ladder(4, 3, java.util.List.of(30), 30);
        assertEquals(Action.WARN, LiveModerationLadder.decideLocally(1, ladder));
        assertEquals(Action.WARN, LiveModerationLadder.decideLocally(3, ladder));
        assertEquals(Action.REMOVE, LiveModerationLadder.decideLocally(4, ladder));
    }
}
