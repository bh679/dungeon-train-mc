package games.brennan.dungeontrain.perks.effects.trader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pure charge maths of Restock Villager. The sold-out check needs item stacks, so it is covered in-game. */
final class RestockVillagerTest {

    @Test
    @DisplayName("one restock per rank, none at rank 0")
    void chargesScaleWithRank() {
        assertEquals(0, RestockVillager.charges(0));
        assertEquals(0, RestockVillager.charges(-1));
        assertEquals(1 * RestockVillager.CHARGES_PER_RANK, RestockVillager.charges(1));
        assertEquals(3 * RestockVillager.CHARGES_PER_RANK, RestockVillager.charges(3));
    }

    @Test
    @DisplayName("remaining counts down and never goes negative")
    void remainingNeverNegative() {
        assertEquals(RestockVillager.charges(2), RestockVillager.remaining(2, 0));
        assertEquals(0, RestockVillager.remaining(2, RestockVillager.charges(2)));
        assertEquals(0, RestockVillager.remaining(1, 5), "a rank drop mid-life leaves no debt");
        assertEquals(RestockVillager.charges(2), RestockVillager.remaining(2, -4));
    }
}
