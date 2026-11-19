package games.brennan.dungeontrain.difficulty;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Pure stock math for {@link MendingTradeStock#limitStock(int, boolean, IntSupplier)}. No registry / ItemStack needed. */
class MendingTradeStockTest {

    private static final IntSupplier NEVER = () -> fail("rolled a stock that was already in range");

    @Test
    @DisplayName("A fresh Mending trade takes the rolled stock")
    void mendingRolled() {
        assertEquals(1, MendingTradeStock.limitStock(12, true, () -> 1));
        assertEquals(2, MendingTradeStock.limitStock(12, true, () -> 2));
        assertEquals(3, MendingTradeStock.limitStock(4, true, () -> 3));
    }

    @Test
    @DisplayName("A stock already in range is kept, so reloads and syncs never re-roll")
    void inRangeKept() {
        assertEquals(1, MendingTradeStock.limitStock(1, true, NEVER));
        assertEquals(2, MendingTradeStock.limitStock(2, true, NEVER));
        assertEquals(3, MendingTradeStock.limitStock(3, true, NEVER));
        assertEquals(0, MendingTradeStock.limitStock(0, true, NEVER));
    }

    @Test
    @DisplayName("Trades without Mending keep their stock")
    void otherTradesUntouched() {
        assertEquals(12, MendingTradeStock.limitStock(12, false, NEVER));
        assertEquals(16, MendingTradeStock.limitStock(16, false, NEVER));
    }

    @Test
    @DisplayName("The roll stays in 1-3 and reaches every value")
    void rollRange() {
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            int stock = MendingTradeStock.rollStock();
            assertTrue(stock >= MendingTradeStock.MIN_STOCK && stock <= MendingTradeStock.MAX_STOCK, "stock " + stock);
            seen.add(stock);
        }
        assertEquals(Set.of(1, 2, 3), seen);
    }
}
