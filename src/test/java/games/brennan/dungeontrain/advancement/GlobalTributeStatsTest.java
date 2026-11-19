package games.brennan.dungeontrain.advancement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which Tributes are a personal best, and so get shown in the passenger log; and the running total paid to others. */
class GlobalTributeStatsTest {

    @Test
    @DisplayName("a player's first Tribute is always their best")
    void firstTribute() {
        assertTrue(GlobalTributeStats.beats(0, 1));
    }

    @Test
    @DisplayName("paying more than ever before is a new best")
    void higher() {
        assertTrue(GlobalTributeStats.beats(3, 4));
    }

    @Test
    @DisplayName("matching or paying less than the best is not")
    void tieOrLower() {
        assertFalse(GlobalTributeStats.beats(3, 3));
        assertFalse(GlobalTributeStats.beats(3, 1));
    }

    @Test
    @DisplayName("nothing paid is never a best")
    void nothingPaid() {
        assertFalse(GlobalTributeStats.beats(0, 0));
    }

    @Test
    @DisplayName("each Tribute adds its price to the running total")
    void totalAdds() {
        assertEquals(3, GlobalTributeStats.added(0, 3));
        assertEquals(505, GlobalTributeStats.added(498, 7));
    }

    @Test
    @DisplayName("nothing paid adds nothing, and the total never overflows")
    void totalEdges() {
        assertEquals(10, GlobalTributeStats.added(10, 0));
        assertEquals(10, GlobalTributeStats.added(10, -4));
        assertEquals(Integer.MAX_VALUE, GlobalTributeStats.added(Integer.MAX_VALUE - 1, 9));
    }
}
