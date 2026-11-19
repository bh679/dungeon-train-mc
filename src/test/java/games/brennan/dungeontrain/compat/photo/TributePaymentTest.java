package games.brennan.dungeontrain.compat.photo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TributePaymentTest {

    @Test
    @DisplayName("enough loose emeralds breaks no blocks and gives no change")
    void looseEmeraldsCover() {
        assertEquals(0, TributePayment.blocksNeeded(3, 3));
        assertEquals(0, TributePayment.change(3, 3));
    }

    @Test
    @DisplayName("a shortfall breaks just enough blocks and returns the rest")
    void blocksCoverShortfall() {
        assertEquals(1, TributePayment.blocksNeeded(0, 1));
        assertEquals(8, TributePayment.change(0, 1));
        assertEquals(1, TributePayment.blocksNeeded(2, 11));
        assertEquals(0, TributePayment.change(2, 11));
        assertEquals(2, TributePayment.blocksNeeded(2, 12));
        assertEquals(8, TributePayment.change(2, 12));
    }

    @Test
    @DisplayName("each emerald from someone else beyond the first adds one person to a photo's reach")
    void reachGrowsWithTheEmeralds() {
        assertEquals(5, TributePayment.reach(1, 3, false));
        assertEquals(6, TributePayment.reach(2, 5, false));
        assertEquals(11, TributePayment.reach(7, 5, false));
    }

    @Test
    @DisplayName("a cheaper Tribute keeps the reach the photo already earned")
    void reachNeverDrops() {
        assertEquals(9, TributePayment.reach(1, 9, false));
    }

    @Test
    @DisplayName("a photographer's own Tribute earns no reach but keeps what others earned")
    void ownTributeEarnsNoReach() {
        assertEquals(5, TributePayment.reach(7, 3, true));
        assertEquals(9, TributePayment.reach(7, 9, true));
    }
}
