package games.brennan.dungeontrain.perks.effects.trader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Reroll Trades' charge and price maths (design 2026-10-28). The roll itself needs a villager, so it is covered in-game. */
final class RerollTradesTest {

    @Test
    @DisplayName("one reroll per point, none at 0 points")
    void chargesScaleWithRank() {
        assertEquals(0, RerollTrades.charges(0));
        assertEquals(0, RerollTrades.charges(-1));
        assertEquals(1, RerollTrades.charges(1));
        assertEquals(10, RerollTrades.charges(10));
    }

    @Test
    @DisplayName("one free reroll per ten points")
    void freeRerolls() {
        assertEquals(0, RerollTrades.free(9));
        assertEquals(1, RerollTrades.free(10));
        assertEquals(1, RerollTrades.free(19));
        assertEquals(2, RerollTrades.free(20));
    }

    @Test
    @DisplayName("at 10 points: free, then 1, 2, 4, ... 256 emeralds")
    void priceSequenceAtTen() {
        List<Integer> prices = new ArrayList<>();
        int used = 0, paid = 0;
        while (RerollTrades.remaining(10, used) > 0) {
            int price = RerollTrades.nextPrice(10, used, paid);
            prices.add(price);
            used++;
            if (price > 0) paid++;
        }
        assertEquals(List.of(0, 1, 2, 4, 8, 16, 32, 64, 128, 256), prices);
    }

    @Test
    @DisplayName("a free reroll gained later is used before the next paid price, which keeps doubling")
    void freeGainedLater() {
        // Three paid rerolls at 9 points, then the tenth point adds a free one.
        assertEquals(0, RerollTrades.nextPrice(10, 3, 3));
        assertEquals(8, RerollTrades.nextPrice(10, 4, 3));
    }

    @Test
    @DisplayName("the doubling never overflows")
    void noOverflow() {
        assertEquals(RerollTrades.MAX_PRICE, RerollTrades.nextPrice(100, 70, 60));
    }

    @Test
    @DisplayName("remaining never goes negative")
    void remainingNeverNegative() {
        assertEquals(0, RerollTrades.remaining(2, 2));
        assertEquals(0, RerollTrades.remaining(1, 5), "a rank drop mid-life leaves no debt");
    }

    @Test
    @DisplayName("its charges are kept apart from Restock Villager's")
    void ownStore() {
        assertNotEquals(RestockVillager.STORE_KEY, RerollTrades.STORE_KEY);
    }
}
