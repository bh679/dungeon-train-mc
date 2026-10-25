package games.brennan.dungeontrain.perks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class PerkRankCostTest {

    private static int total(int ranks) {
        int sum = 0;
        for (int r = 0; r < ranks; r++) sum += PerkRankCost.nextCost(r);
        return sum;
    }

    @Test
    @DisplayName("runs at each price shrink 10, 9, 8 … 1")
    void shrinkingRuns() {
        assertEquals(1, PerkRankCost.nextCost(0));
        assertEquals(1, PerkRankCost.nextCost(9));
        assertEquals(2, PerkRankCost.nextCost(10));
        assertEquals(2, PerkRankCost.nextCost(18));
        assertEquals(3, PerkRankCost.nextCost(19));
        assertEquals(8, PerkRankCost.nextCost(49));
        assertEquals(9, PerkRankCost.nextCost(52));
        assertEquals(10, PerkRankCost.nextCost(54));
    }

    @Test
    @DisplayName("after the runs each rank jumps by one more than the last")
    void growingJumps() {
        assertEquals(11, PerkRankCost.nextCost(55));
        assertEquals(13, PerkRankCost.nextCost(56));
        assertEquals(16, PerkRankCost.nextCost(57));
        assertEquals(20, PerkRankCost.nextCost(58));
    }

    @Test
    @DisplayName("totals match the agreed curve")
    void totals() {
        assertEquals(10, total(10));
        assertEquals(31, total(20));
        assertEquals(176, total(50));
        assertEquals(220, total(55));
    }

    @Test
    @DisplayName("never overflows on absurd counts")
    void clamps() {
        assertEquals(Integer.MAX_VALUE, PerkRankCost.nextCost(Integer.MAX_VALUE));
    }
}
