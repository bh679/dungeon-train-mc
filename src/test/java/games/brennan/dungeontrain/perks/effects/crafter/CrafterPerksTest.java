package games.brennan.dungeontrain.perks.effects.crafter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure numbers behind the Crafter perks; no registry or ItemStack needed. */
class CrafterPerksTest {

    @Test
    @DisplayName("Enchant Cost: one level less per rank, floor 1, zero stays zero")
    void reducedCost() {
        assertEquals(30, CrafterPerks.reducedCost(30, 0));
        assertEquals(29, CrafterPerks.reducedCost(30, 1));
        assertEquals(28, CrafterPerks.reducedCost(30, 2));
        assertEquals(1, CrafterPerks.reducedCost(2, 5));
        assertEquals(1, CrafterPerks.reducedCost(1, 1));
        assertEquals(0, CrafterPerks.reducedCost(0, 3));
        assertEquals(-1, CrafterPerks.reducedCost(-1, 3));
    }

    @Test
    @DisplayName("Enchant Cost: Too Expensive lifts only when the discount brings a 40+ price under 40")
    void liftsTooExpensive() {
        assertTrue(CrafterPerks.liftsTooExpensive(41, 2));    // 41 -> 39
        assertTrue(CrafterPerks.liftsTooExpensive(40, 1));    // 40 -> 39
        assertFalse(CrafterPerks.liftsTooExpensive(45, 2));   // 45 -> 43, still Too Expensive
        assertFalse(CrafterPerks.liftsTooExpensive(41, 1));   // 41 -> 40, still at the cap
        assertFalse(CrafterPerks.liftsTooExpensive(39, 2));   // under the cap: ordinary combine
        assertFalse(CrafterPerks.liftsTooExpensive(41, 0));   // no perk
        assertFalse(CrafterPerks.liftsTooExpensive(0, 3));    // no result
    }

    @Test
    @DisplayName("Cooking Time: 80% at rank 1, each rank keeps 88% of the last, no floor, never below one tick")
    void cookTicks() {
        assertEquals(200, CrafterPerks.cookTicks(200, 0));
        assertEquals(160, CrafterPerks.cookTicks(200, 1));
        assertEquals(141, CrafterPerks.cookTicks(200, 2));  // 70.4%
        assertEquals(124, CrafterPerks.cookTicks(200, 3));  // 62%
        assertEquals(50, CrafterPerks.cookTicks(200, 10), 1); // about 25%
        assertTrue(CrafterPerks.cookTicks(200, 20) < CrafterPerks.cookTicks(200, 10), "keeps shrinking");
        assertEquals(1, CrafterPerks.cookTicks(1, 3));
        assertEquals(0, CrafterPerks.cookTicks(0, 3));
        for (int r = 2; r < 60; r++) assertTrue(CrafterPerks.cookShare(r) < CrafterPerks.cookShare(r - 1));
        assertTrue(CrafterPerks.cookShare(200) > 0);
    }

    @Test
    @DisplayName("Enchant Max: only a max-level roll climbs, by rank levels")
    void rolledLevel() {
        assertEquals(5, CrafterPerks.rolledLevel(5, 5, 0));
        assertEquals(6, CrafterPerks.rolledLevel(5, 5, 1));
        assertEquals(7, CrafterPerks.rolledLevel(5, 5, 2));
        assertEquals(4, CrafterPerks.rolledLevel(4, 5, 2));
    }

    @Test
    @DisplayName("Enchant Max in the anvil: cap is vanilla + bonus, never below what an input carries")
    void anvilLevelCap() {
        assertEquals(5, CrafterPerks.anvilLevelCap(5, 0, 0));
        assertEquals(6, CrafterPerks.anvilLevelCap(5, 0, 6));   // no perk, VI survives
        assertEquals(7, CrafterPerks.anvilLevelCap(5, 2, 6));
        assertEquals(8, CrafterPerks.anvilLevelCap(5, 1, 8));
    }

    @Test
    @DisplayName("Enchant Max/Bonus: each point opens one group at its band's level, a band is 10 points")
    void groupBonus() {
        int protection = 0, melee = 1, thorns = 9;
        assertEquals(0, CrafterPerks.groupBonus(0, protection));
        assertEquals(0, CrafterPerks.groupBonus(5, -1), "an enchantment in no group never moves");
        assertEquals(1, CrafterPerks.groupBonus(1, protection));
        assertEquals(0, CrafterPerks.groupBonus(1, melee));
        assertEquals(1, CrafterPerks.groupBonus(2, melee));
        assertEquals(0, CrafterPerks.groupBonus(9, thorns));
        assertEquals(1, CrafterPerks.groupBonus(10, thorns));
        assertEquals(2, CrafterPerks.groupBonus(11, protection));
        assertEquals(1, CrafterPerks.groupBonus(11, melee));
        assertEquals(3, CrafterPerks.groupBonus(30, thorns));
        assertEquals(1, CrafterPerks.topLevel(10));
        assertEquals(2, CrafterPerks.topLevel(11));
        assertEquals(1, CrafterPerks.groupsAtTop(11));
        assertEquals(10, CrafterPerks.groupsAtTop(20));
        for (int rank = 1; rank < 50; rank++) {
            for (int g = 0; g < CrafterPerks.GROUPS; g++) {
                assertTrue(CrafterPerks.groupBonus(rank, g) >= CrafterPerks.groupBonus(rank - 1, g), "never drops");
            }
        }
    }

    @Test
    @DisplayName("Re-enchant: on hold, so off at every rank")
    void reenchantOnHold() {
        assertTrue(CrafterPerks.REENCHANT_ON_HOLD);
        assertFalse(CrafterPerks.allowsReenchant(0));
        assertFalse(CrafterPerks.allowsReenchant(1));
        assertFalse(CrafterPerks.allowsReenchant(3));
    }

    @Test
    @DisplayName("Re-enchant filter: rolled candidates that clash with an existing enchantment are dropped")
    void compatibleWithExisting() {

        List<String> rolled = List.of("smite", "unbreaking", "sharpness");
        Set<String> existing = Set.of("sharpness");
        List<String> kept = CrafterPerks.compatibleWithExisting(rolled, existing,
            (candidate, have) -> !(candidate.equals("smite") && have.equals("sharpness")) && !candidate.equals(have));
        assertEquals(List.of("unbreaking"), kept);

        assertEquals(rolled, CrafterPerks.compatibleWithExisting(rolled, Set.of(), (a, b) -> false));
        assertEquals(List.of(), CrafterPerks.compatibleWithExisting(null, existing, (a, b) -> true));
    }
}
