package games.brennan.dungeontrain.difficulty;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ToolTiers}: each break tier's spawn chance starts at 2% at its unlock level
 * (50 / 100 / 150 / 200), passes 20% 100 levels later and never reaches 100%; mobs roll the highest
 * tier first; floors are never breakable.
 */
final class ToolTiersTest {

    private static final double EPS = 1e-9;

    @Test
    @DisplayName("no tools below level 50")
    void noneBelowStart() {
        assertEquals(0.0, ToolTiers.toolChance(0));
        assertEquals(0.0, ToolTiers.toolChance(49));
    }

    @Test
    @DisplayName("tier 1: 2% at level 50 and 20% at level 150")
    void anchorPoints() {
        assertEquals(0.02, ToolTiers.tierChance(1, 50), EPS);
        assertEquals(0.20, ToolTiers.tierChance(1, 150), EPS);
        assertEquals(0.02, ToolTiers.toolChance(50), EPS);
    }

    @Test
    @DisplayName("each tier's rate starts again at 2% when it unlocks, 20% 100 levels on")
    void eachTierRestarts() {
        int[] unlocks = {50, 100, 150, 200};
        for (int tier = 1; tier <= 4; tier++) {
            int unlock = unlocks[tier - 1];
            assertEquals(0.0, ToolTiers.tierChance(tier, unlock - 1), "tier " + tier);
            assertEquals(0.02, ToolTiers.tierChance(tier, unlock), EPS, "tier " + tier);
            assertEquals(0.20, ToolTiers.tierChance(tier, unlock + 100), EPS, "tier " + tier);
        }
    }

    @Test
    @DisplayName("mobs roll the highest unlocked tier first; the first pass wins")
    void rollHighestFirst() {
        // Level 100: tier 2 at 2%, tier 1 ~10.6%.
        assertEquals(2, ToolTiers.rollTier(100, () -> 0.01));
        double[] rolls = {0.5, 0.05};
        int[] i = {0};
        assertEquals(1, ToolTiers.rollTier(100, () -> rolls[i[0]++]));
        assertEquals(ToolTiers.NO_TIER, ToolTiers.rollTier(100, () -> 0.5));
        assertEquals(ToolTiers.NO_TIER, ToolTiers.rollTier(49, () -> 0.0));
        // Overall odds combine the tiers: 1 - (1 - 0.02)(1 - t1).
        double t1 = ToolTiers.tierChance(1, 100);
        assertEquals(1 - 0.98 * (1 - t1), ToolTiers.toolChance(100), EPS);
    }

    @Test
    @DisplayName("the chance keeps climbing but never reaches 100%")
    void climbsBelowOne() {
        double previous = 0.0;
        // Up to 3000: deeper still, four curves this close to 1 combine to 1.0 in double precision.
        for (int level = 50; level <= 3000; level += 50) {
            double chance = ToolTiers.toolChance(level);
            assertTrue(chance > previous, "level " + level);
            assertTrue(chance < 1.0, "level " + level);
            previous = chance;
        }
        assertTrue(ToolTiers.toolChance(100_000) <= 1.0);
    }

    @Test
    @DisplayName("break tiers unlock at 50 / 100 / 150 / 200")
    void tierThresholds() {
        assertEquals(ToolTiers.NO_TIER, ToolTiers.breakTier(49));
        assertEquals(1, ToolTiers.breakTier(50));
        assertEquals(1, ToolTiers.breakTier(99));
        assertEquals(2, ToolTiers.breakTier(100));
        assertEquals(2, ToolTiers.breakTier(149));
        assertEquals(3, ToolTiers.breakTier(150));
        assertEquals(3, ToolTiers.breakTier(199));
        assertEquals(4, ToolTiers.breakTier(200));
        assertEquals(4, ToolTiers.breakTier(10_000));
    }

    @Test
    @DisplayName("tier rules: what each tier may break")
    void tierRules() {
        // tier 1: player-placed AND right tool
        assertTrue(ToolTiers.mayBreak(1, true, true, false));
        assertFalse(ToolTiers.mayBreak(1, true, false, false));
        assertFalse(ToolTiers.mayBreak(1, false, true, false));
        // tier 2: any player-placed
        assertTrue(ToolTiers.mayBreak(2, true, false, false));
        assertFalse(ToolTiers.mayBreak(2, false, true, false));
        // tier 3: player-placed OR right tool
        assertTrue(ToolTiers.mayBreak(3, true, false, false));
        assertTrue(ToolTiers.mayBreak(3, false, true, false));
        assertFalse(ToolTiers.mayBreak(3, false, false, false));
        // tier 4: anything
        assertTrue(ToolTiers.mayBreak(4, false, false, false));
        // no tier: nothing
        assertFalse(ToolTiers.mayBreak(ToolTiers.NO_TIER, true, true, false));
    }

    @Test
    @DisplayName("floors are never breakable at any tier")
    void neverFloors() {
        for (int tier = 0; tier <= 4; tier++) {
            assertFalse(ToolTiers.mayBreak(tier, true, true, true), "tier " + tier);
        }
    }

    @Test
    @DisplayName("the tool pool holds pickaxes, shovels and axes, pickaxes most common")
    void toolPoolShape() {
        var pool = ToolTiers.toolPool(150);
        int pick = 0, shovel = 0, axe = 0;
        for (DifficultyTier.WeightedItem wi : pool) {
            String path = wi.item().getPath();
            if (path.endsWith("_pickaxe")) pick += wi.weight();
            else if (path.endsWith("_shovel")) shovel += wi.weight();
            else if (path.endsWith("_axe")) axe += wi.weight();
            else throw new AssertionError("unexpected tool " + path);
        }
        assertTrue(pick > shovel && shovel > axe && axe > 0);
    }
}
