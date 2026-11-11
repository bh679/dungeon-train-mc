package games.brennan.dungeontrain.difficulty;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoatRamScalingTest {

    /** The shipped order: first BoP overworld stretch at X 21,194, base period 101,492, anchor 0. */
    private static final GoatRamScaling.Layout SHIPPED = GoatRamScaling.Layout.of(21_194L, 0L, 101_492L);
    private static final long CYCLE_2 = 101_492L;
    private static final long CYCLE_3 = 304_476L;
    private static final long CYCLE_4 = 710_444L;

    private static void assertNear(double expected, double actual, String what) {
        assertEquals(expected, actual, expected * 0.05, what);
    }

    @Test
    @DisplayName("before the BoP stretch goats are vanilla: 165 s average, 1 heart, not scaled")
    void vanillaBeforeBop() {
        assertFalse(GoatRamScaling.isScaled(21_193L, SHIPPED));
        assertFalse(GoatRamScaling.isScaled(-500L, SHIPPED));
        assertEquals(165.0, GoatRamScaling.averageWaitSeconds(0L, SHIPPED), 1e-9);
        assertEquals(1.0, GoatRamScaling.hearts(21_193L, SHIPPED), 1e-9);
        assertEquals(0.0, GoatRamScaling.bonusDamage(21_193L, SHIPPED), 1e-9);
    }

    @Test
    @DisplayName("the agreed table: 9.4 s at cycle 2, 4 s at cycle 3, ~2.15 s at cycle 4")
    void agreedWaits() {
        assertNear(165.0, GoatRamScaling.averageWaitSeconds(21_194L, SHIPPED), "BoP start");
        assertNear(29.0, GoatRamScaling.averageWaitSeconds(6_800L * 9, SHIPPED), "carriage 6,800");
        assertNear(9.4, GoatRamScaling.averageWaitSeconds(CYCLE_2, SHIPPED), "cycle 2");
        assertNear(4.0, GoatRamScaling.averageWaitSeconds(CYCLE_3, SHIPPED), "cycle 3");
        assertNear(2.15, GoatRamScaling.averageWaitSeconds(CYCLE_4, SHIPPED), "cycle 4");
    }

    @Test
    @DisplayName("the wait only ever falls, joins the gentle tail at 10 s without a jump, never below 2 s")
    void waitMonotonicAndFloored() {
        double prev = Double.MAX_VALUE;
        for (long x = 21_194L; x < 3_000_000L; x += 500L) {
            double w = GoatRamScaling.averageWaitSeconds(x, SHIPPED);
            assertTrue(w <= prev + 1e-9, "rose at x=" + x);
            assertTrue(w > GoatRamScaling.FLOOR_SECONDS, "hit the floor at x=" + x);
            assertTrue(prev - w < 1.0 || prev > 20.0, "jumped near the 10 s join at x=" + x);
            prev = w;
        }
    }

    @Test
    @DisplayName("damage: 1 → 4 hearts across cycle 1 from BoP, then +3 per cycle, no cap")
    void heartsPerCycle() {
        assertEquals(4.0, GoatRamScaling.hearts(CYCLE_2, SHIPPED), 1e-6);
        assertEquals(7.0, GoatRamScaling.hearts(CYCLE_3, SHIPPED), 1e-6);
        assertEquals(10.0, GoatRamScaling.hearts(CYCLE_4, SHIPPED), 1e-6);
        assertNear(2.5, GoatRamScaling.hearts((21_194L + CYCLE_2) / 2, SHIPPED), "halfway to cycle 2");
        assertEquals(6.0, GoatRamScaling.bonusDamage(CYCLE_2, SHIPPED), 1e-6);
        double prev = 0.0;
        for (long x = 21_194L; x < 3_000_000L; x += 997L) {
            double h = GoatRamScaling.hearts(x, SHIPPED);
            assertTrue(h >= prev - 1e-9, "fell at x=" + x);
            prev = h;
        }
    }

    @Test
    @DisplayName("cooldown range is ±25% of the average, never under 1.5 s")
    void cooldownRange() {
        assertEquals(150, GoatRamScaling.minCooldownTicks(200));
        assertEquals(250, GoatRamScaling.maxCooldownTicks(200));
        assertEquals(GoatRamScaling.MIN_COOLDOWN_TICKS, GoatRamScaling.minCooldownTicks(40));
        assertEquals(50, GoatRamScaling.maxCooldownTicks(40));
    }

    @Test
    @DisplayName("no usable BoP stretch → no layout, goats stay vanilla")
    void noBop() {
        assertNull(GoatRamScaling.Layout.of(200_000L, 0L, 101_492L));
        assertNull(GoatRamScaling.Layout.of(5L, 0L, 0L));
        assertFalse(GoatRamScaling.isScaled(500_000L, null));
        assertEquals(1.0, GoatRamScaling.hearts(500_000L, null), 1e-9);
    }
}
