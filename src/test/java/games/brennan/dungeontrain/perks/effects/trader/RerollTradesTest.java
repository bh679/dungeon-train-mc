package games.brennan.dungeontrain.perks.effects.trader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Pure charge maths of Reroll Trades. The roll itself needs a villager, so it is covered in-game. */
final class RerollTradesTest {

    @Test
    @DisplayName("one reroll per rank, none at rank 0")
    void chargesScaleWithRank() {
        assertEquals(0, RerollTrades.charges(0));
        assertEquals(0, RerollTrades.charges(-1));
        assertEquals(RerollTrades.CHARGES_PER_RANK, RerollTrades.charges(1));
        assertEquals(3 * RerollTrades.CHARGES_PER_RANK, RerollTrades.charges(3));
    }

    @Test
    @DisplayName("remaining counts down and never goes negative")
    void remainingNeverNegative() {
        assertEquals(RerollTrades.charges(2), RerollTrades.remaining(2, 0));
        assertEquals(0, RerollTrades.remaining(2, RerollTrades.charges(2)));
        assertEquals(0, RerollTrades.remaining(1, 5), "a rank drop mid-life leaves no debt");
    }

    @Test
    @DisplayName("its charges are kept apart from Restock Villager's")
    void ownStore() {
        assertNotEquals(RestockVillager.STORE_KEY, RerollTrades.STORE_KEY);
    }
}
