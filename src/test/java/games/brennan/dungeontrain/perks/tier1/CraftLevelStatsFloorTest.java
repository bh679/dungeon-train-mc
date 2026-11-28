package games.brennan.dungeontrain.perks.tier1;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Craft Level Stats never crafts below vanilla, and the result-slot preview's top end. */
class CraftLevelStatsFloorTest {

    private static final double EPS = 1e-9;

    @Test
    void floorKeepsVanillaOnABadRoll() {
        assertEquals(5.0, CraftLevelStats.floorAmount(2.5, 5.0), EPS);
        assertEquals(7.2, CraftLevelStats.floorAmount(7.2, 5.0), EPS);
    }

    @Test
    void floorOnAttackSpeedKeepsTheFasterNegativeModifier() {
        // Vanilla sword speed modifier is -2.4 (1.6 effective); a slow roll of -2.9 is worse.
        assertEquals(-2.4, CraftLevelStats.floorAmount(-2.9, -2.4), EPS);
        assertEquals(-1.6, CraftLevelStats.floorAmount(-1.6, -2.4), EPS);
    }

    @Test
    void maxTierStaysBelowTheLevelTier() {
        assertEquals(0, CraftLevelStats.maxTier(0, 5));
        assertEquals(0, CraftLevelStats.maxTier(1, 10));
        assertEquals(0, CraftLevelStats.maxTier(20, 0));
        for (int level = 1; level <= 200; level++) {
            for (int rank = 1; rank <= 30; rank++) {
                assertTrue(CraftLevelStats.maxTier(level, rank) < level);
            }
        }
    }

    @Test
    void ironSwordAtLevelTwentyRankThree() {
        // ceiling(3) = 0.595 -> best tier floor(20 * 0.595) = 11 -> bonus 0.1 * 11.
        assertEquals(11, CraftLevelStats.maxTier(20, 3));
        double bonus = 0.1 * 11;
        double damage = CraftLevelStats.PLAYER_BASE_ATTACK_DAMAGE
            + CraftLevelStats.maxAmount(CraftLevelStats.PLAYER_BASE_ATTACK_DAMAGE, 5.0, true, bonus);
        assertEquals(10.1, damage, EPS);
        double speed = CraftLevelStats.PLAYER_BASE_ATTACK_SPEED
            + CraftLevelStats.maxAmount(CraftLevelStats.PLAYER_BASE_ATTACK_SPEED, -2.4, false, bonus);
        assertEquals(2.4, speed, EPS);
    }

    @Test
    void maxIsNeverBelowVanilla() {
        assertTrue(CraftLevelStats.maxAmount(0, 0.0, false, 0) >= 0.0);
        assertTrue(CraftLevelStats.maxAmount(4, -3.0, false, 0) >= -3.0);
    }
}
