package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveTakeoverTextTest {

    @Test
    @DisplayName("a duration shows in its largest whole unit, floored")
    void units() {
        assertEquals(LiveTakeoverText.Unit.SECONDS, LiveTakeoverText.unitOf(45_000));
        assertEquals(45, LiveTakeoverText.countOf(45_999));
        assertEquals(LiveTakeoverText.Unit.MINUTES, LiveTakeoverText.unitOf(60_000));
        assertEquals(4, LiveTakeoverText.countOf(299_999));
        assertEquals(LiveTakeoverText.Unit.HOURS, LiveTakeoverText.unitOf(3_600_000));
        assertEquals(2, LiveTakeoverText.countOf(7_300_000));
        assertEquals(0, LiveTakeoverText.countOf(-5));
    }

    @Test
    @DisplayName("English ordinals: 1st 2nd 3rd 4th, the teens are th, 21st 22nd 23rd, 111th")
    void englishOrdinals() {
        String[] want = {"th", "st", "nd", "rd", "th", "th"};
        for (int n = 0; n <= 5; n++) assertEquals(want[n], LiveTakeoverText.englishSuffix(n), "n=" + n);
        assertEquals("th", LiveTakeoverText.englishSuffix(11));
        assertEquals("th", LiveTakeoverText.englishSuffix(12));
        assertEquals("th", LiveTakeoverText.englishSuffix(13));
        assertEquals("st", LiveTakeoverText.englishSuffix(21));
        assertEquals("nd", LiveTakeoverText.englishSuffix(22));
        assertEquals("rd", LiveTakeoverText.englishSuffix(23));
        assertEquals("th", LiveTakeoverText.englishSuffix(111));
        assertEquals("st", LiveTakeoverText.englishSuffix(101));
    }

    @Test
    @DisplayName("a vote is yes / no, with an optional leading ! and trailing ! or .; anything else is chat")
    void votes() {
        assertEquals("yes", LiveTakeoverChat.voteOf("yes"));
        assertEquals("yes", LiveTakeoverChat.voteOf(" !YES "));
        assertEquals("no", LiveTakeoverChat.voteOf("No!"));
        assertEquals("no", LiveTakeoverChat.voteOf("no."));
        assertNull(LiveTakeoverChat.voteOf("yess"));
        assertNull(LiveTakeoverChat.voteOf("no way"));
        assertNull(LiveTakeoverChat.voteOf(""));
        assertNull(LiveTakeoverChat.voteOf(null));
    }

    @Test
    @DisplayName("Called It: only a vote cast in this very vote, for the side that won")
    void votedWinner() {
        assertTrue(LiveTakeoverChat.votedWinner("p1", "yes", "p1", true));
        assertTrue(LiveTakeoverChat.votedWinner("p1", "no", "p1", false));
        assertFalse(LiveTakeoverChat.votedWinner("p1", "no", "p1", true));
        assertFalse(LiveTakeoverChat.votedWinner("p1", "yes", "p1", false));
        assertFalse(LiveTakeoverChat.votedWinner("p0", "yes", "p1", true));
        assertFalse(LiveTakeoverChat.votedWinner(null, null, "p1", true));
    }
}
