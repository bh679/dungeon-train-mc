package games.brennan.dungeontrain.difficulty;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pure stock math for {@link MendingTradeStock#limitStock(int, boolean)}. No registry / ItemStack needed. */
class MendingTradeStockTest {

    @Test
    @DisplayName("A Mending trade's stock drops to 1")
    void mendingCapped() {
        assertEquals(1, MendingTradeStock.limitStock(12, true));
        assertEquals(1, MendingTradeStock.limitStock(3, true));
        assertEquals(1, MendingTradeStock.limitStock(1, true));
    }

    @Test
    @DisplayName("The cap never raises stock")
    void neverRaises() {
        assertEquals(0, MendingTradeStock.limitStock(0, true));
    }

    @Test
    @DisplayName("Trades without Mending keep their stock")
    void otherTradesUntouched() {
        assertEquals(12, MendingTradeStock.limitStock(12, false));
        assertEquals(16, MendingTradeStock.limitStock(16, false));
    }
}
