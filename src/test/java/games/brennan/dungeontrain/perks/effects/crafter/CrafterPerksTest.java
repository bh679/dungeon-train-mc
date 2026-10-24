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
    @DisplayName("Enchant Cost: any rank lifts the anvil's Too Expensive cap, rank 0 keeps it")
    void ignoresAnvilCap() {
        assertFalse(CrafterPerks.ignoresAnvilCap(0));
        assertTrue(CrafterPerks.ignoresAnvilCap(1));
        assertTrue(CrafterPerks.ignoresAnvilCap(3));
    }

    @Test
    @DisplayName("Cooking Time: 20% faster per rank, floor 25% of base, never below one tick")
    void cookTicks() {
        assertEquals(200, CrafterPerks.cookTicks(200, 0));
        assertEquals(160, CrafterPerks.cookTicks(200, 1));
        assertEquals(120, CrafterPerks.cookTicks(200, 2));
        assertEquals(80, CrafterPerks.cookTicks(200, 3));
        assertEquals(50, CrafterPerks.cookTicks(200, 4));   // 20% would be 40; floor 25%
        assertEquals(50, CrafterPerks.cookTicks(200, 99));
        assertEquals(80, CrafterPerks.cookTicks(100, 1));
        assertEquals(1, CrafterPerks.cookTicks(1, 3));
        assertEquals(0, CrafterPerks.cookTicks(0, 3));
    }

    @Test
    @DisplayName("Enchant Max: only a max-level roll climbs, by rank levels")
    void rolledLevel() {
        assertEquals(5, CrafterPerks.rolledLevel(5, 5, 0));
        assertEquals(6, CrafterPerks.rolledLevel(5, 5, 1));
        assertEquals(7, CrafterPerks.rolledLevel(5, 5, 2));
        assertEquals(4, CrafterPerks.rolledLevel(4, 5, 2));
        assertEquals(0, CrafterPerks.maxLevelBonus(-1));
        assertEquals(2, CrafterPerks.maxLevelBonus(2));
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
    @DisplayName("Enchant Bonus: +1 while active, 0 otherwise")
    void bonusLevels() {
        assertEquals(0, CrafterPerks.bonusLevels(0));
        assertEquals(1, CrafterPerks.bonusLevels(1));
        assertEquals(1, CrafterPerks.bonusLevels(3));
    }

    @Test
    @DisplayName("Re-enchant: rolled candidates that clash with an existing enchantment are dropped")
    void compatibleWithExisting() {
        assertTrue(CrafterPerks.allowsReenchant(1));
        assertFalse(CrafterPerks.allowsReenchant(0));

        List<String> rolled = List.of("smite", "unbreaking", "sharpness");
        Set<String> existing = Set.of("sharpness");
        List<String> kept = CrafterPerks.compatibleWithExisting(rolled, existing,
            (candidate, have) -> !(candidate.equals("smite") && have.equals("sharpness")) && !candidate.equals(have));
        assertEquals(List.of("unbreaking"), kept);

        assertEquals(rolled, CrafterPerks.compatibleWithExisting(rolled, Set.of(), (a, b) -> false));
        assertEquals(List.of(), CrafterPerks.compatibleWithExisting(null, existing, (a, b) -> true));
    }
}
