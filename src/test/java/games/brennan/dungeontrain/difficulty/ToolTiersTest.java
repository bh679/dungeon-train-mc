package games.brennan.dungeontrain.difficulty;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ToolTiers}: the tool chance starts at 2% at level 50, passes 20% at 150 and never
 * reaches 100%; break tiers unlock at 50 / 100 / 250 / 750; floors are never breakable.
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
    @DisplayName("2% at level 50 and 20% at level 150")
    void anchorPoints() {
        assertEquals(0.02, ToolTiers.toolChance(50), EPS);
        assertEquals(0.20, ToolTiers.toolChance(150), EPS);
    }

    @Test
    @DisplayName("the chance keeps climbing but never reaches 100%")
    void climbsBelowOne() {
        double previous = 0.0;
        for (int level = 50; level <= 5000; level += 50) {
            double chance = ToolTiers.toolChance(level);
            assertTrue(chance > previous, "level " + level);
            assertTrue(chance < 1.0, "level " + level);
            previous = chance;
        }
        assertTrue(ToolTiers.toolChance(100_000) <= 1.0);
    }

    @Test
    @DisplayName("break tiers unlock at 50 / 100 / 250 / 750")
    void tierThresholds() {
        assertEquals(ToolTiers.NO_TIER, ToolTiers.breakTier(49));
        assertEquals(1, ToolTiers.breakTier(50));
        assertEquals(1, ToolTiers.breakTier(99));
        assertEquals(2, ToolTiers.breakTier(100));
        assertEquals(2, ToolTiers.breakTier(249));
        assertEquals(3, ToolTiers.breakTier(250));
        assertEquals(3, ToolTiers.breakTier(749));
        assertEquals(4, ToolTiers.breakTier(750));
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
