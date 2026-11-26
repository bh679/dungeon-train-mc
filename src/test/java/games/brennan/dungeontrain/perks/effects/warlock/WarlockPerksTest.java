package games.brennan.dungeontrain.perks.effects.warlock;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Warlock perks' pure maths: {@link WarlockPerks}. */
class WarlockPerksTest {

    @Test
    void everythingIsOffAtRankZero() {
        assertEquals(0f, WarlockPerks.throwDamage(0));
        assertEquals(0, WarlockPerks.throwEffectTicks(0));
        assertEquals(0, WarlockPerks.teleportCooldownTicks(0));
        assertEquals(0f, WarlockPerks.lightningDamage(0));
        assertEquals(0f, WarlockPerks.lightningCost(0));
        assertEquals(0, WarlockPerks.convertTicks(0));
        assertEquals(0, WarlockPerks.convertCooldownTicks(0));
    }

    @Test
    void throwDamageStepsHalfAHeartOnTriangularPoints() {
        // hit points: ½♥ = 1
        int[] expect = {1, 1, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 4, 5};
        for (int r = 1; r <= expect.length; r++) assertEquals(expect[r - 1], WarlockPerks.throwDamage(r), "rank " + r);
        assertEquals(6, WarlockPerks.throwDamage(21));
    }

    @Test
    void throwEffectIsTwoRootPointsSeconds() {
        assertEquals(40, WarlockPerks.throwEffectTicks(1));   // 2.0 s
        assertEquals(56, WarlockPerks.throwEffectTicks(2));   // 2.8 s
        assertEquals(70, WarlockPerks.throwEffectTicks(3));   // 3.5 s
        assertEquals(80, WarlockPerks.throwEffectTicks(4));   // 4.0 s
        assertEquals(126, WarlockPerks.throwEffectTicks(10)); // 6.3 s
        for (int r = 1; r < 100; r++) { // one decimal: past ~100 points a step can round to the same tenth
            assertTrue(WarlockPerks.throwEffectTicks(r + 1) > WarlockPerks.throwEffectTicks(r), "rank " + r);
        }
    }

    @Test
    void teleportCooldownFallsTenPercentAPointAndNeverReachesZero() {
        assertEquals(300 * 20, WarlockPerks.teleportCooldownTicks(1));
        assertEquals(270 * 20, WarlockPerks.teleportCooldownTicks(2));
        assertEquals(243 * 20, WarlockPerks.teleportCooldownTicks(3));
        assertEquals(197 * 20, WarlockPerks.teleportCooldownTicks(5));
        assertEquals(116 * 20, WarlockPerks.teleportCooldownTicks(10));
        assertEquals(41 * 20, WarlockPerks.teleportCooldownTicks(20));
        assertEquals(20, WarlockPerks.teleportCooldownTicks(1000));
    }

    @Test
    void playerMobsComeWhenTheyLikeYouOtherwiseByFriendliness() {
        assertEquals(100, WarlockPerks.playerMobAcceptPercent(true, 0));
        assertEquals(0, WarlockPerks.playerMobAcceptPercent(false, 0));
        assertEquals(40, WarlockPerks.playerMobAcceptPercent(false, 4));
        assertEquals(100, WarlockPerks.playerMobAcceptPercent(false, 10));
        assertEquals(100, WarlockPerks.playerMobAcceptPercent(false, 99));
    }

    @Test
    void lightningFollowsBrennansTable() {
        // hearts, as written: damage - cost
        double[][] table = {
            {5, 4}, {5.5, 4}, {5.5, 3.5}, {6, 3.5}, {6, 3}, {6.5, 3}, {7, 3}, {7, 2.5}, {7.5, 2.5}, {8, 2.5},
            {9, 2.5}, {10, 2.5}, {10, 2}, {10.5, 2}, {11.5, 2}, {12, 2}, {13, 2}};
        for (int r = 1; r <= table.length; r++) {
            assertEquals(table[r - 1][0] * 2, WarlockPerks.lightningDamage(r), "damage at " + r);
            assertEquals(table[r - 1][1] * 2, WarlockPerks.lightningCost(r), "cost at " + r);
        }
        assertEquals(40f, WarlockPerks.lightningDamage(24));
        assertEquals(4f, WarlockPerks.lightningCost(24));
        assertEquals(40f, WarlockPerks.lightningDamage(25));
        assertEquals(3f, WarlockPerks.lightningCost(25));
    }

    @Test
    void lightningCostDropsAtDoubleTheDistanceThenTurnsIntoAChance() {
        assertEquals(3f, WarlockPerks.lightningCost(48));
        assertEquals(2f, WarlockPerks.lightningCost(49));
        assertEquals(1f, WarlockPerks.lightningCost(97));
        assertEquals(100.0, WarlockPerks.lightningCostPercent(192));
        assertEquals(1f, WarlockPerks.lightningCost(193));
        assertEquals(50.0, WarlockPerks.lightningCostPercent(193));
        assertEquals(25.0, WarlockPerks.lightningCostPercent(385));
        assertEquals(12.5, WarlockPerks.lightningCostPercent(769));
    }

    @Test
    void lightningChangesSomethingEveryPoint() {
        for (int r = 1; r < 2000; r++) {
            boolean damage = WarlockPerks.lightningDamage(r + 1) != WarlockPerks.lightningDamage(r);
            boolean cost = WarlockPerks.lightningCost(r + 1) != WarlockPerks.lightningCost(r)
                || WarlockPerks.lightningCostPercent(r + 1) != WarlockPerks.lightningCostPercent(r);
            assertTrue(damage || cost, "rank " + r + " changes nothing");
            assertTrue(WarlockPerks.lightningDamage(r + 1) >= WarlockPerks.lightningDamage(r), "damage falls at " + r);
            assertTrue(WarlockPerks.lightningCost(r + 1) <= WarlockPerks.lightningCost(r), "cost rises at " + r);
        }
        // past the table: 1♥ a point, holding on the drop at 49
        assertEquals(42f, WarlockPerks.lightningDamage(26));
        assertEquals(WarlockPerks.lightningDamage(48), WarlockPerks.lightningDamage(49));
    }

    @Test
    void convertDurationAndCooldownEase() {
        assertEquals(300, WarlockPerks.convertTicks(1));   // 15 s
        assertEquals(424, WarlockPerks.convertTicks(2));   // 21.2 s
        assertEquals(948, WarlockPerks.convertTicks(10));  // 47.4 s
        assertEquals(2400, WarlockPerks.convertCooldownTicks(1));  // 120 s
        assertEquals(2160, WarlockPerks.convertCooldownTicks(2));  // 108 s
        assertEquals(1944, WarlockPerks.convertCooldownTicks(3));  // 97.2 s
        assertEquals(930, WarlockPerks.convertCooldownTicks(10));  // 46.5 s
        assertTrue(WarlockPerks.convertCooldownTicks(10_000) >= 2);
        for (int r = 1; r < 300; r++) {
            assertTrue(WarlockPerks.convertTicks(r + 1) > WarlockPerks.convertTicks(r), "rank " + r);
        }
    }
}
