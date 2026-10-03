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
}
