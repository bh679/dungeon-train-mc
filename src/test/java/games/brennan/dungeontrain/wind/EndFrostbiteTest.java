package games.brennan.dungeontrain.wind;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndFrostbiteTest {

    private static final int TICKS_TO_FREEZE = 140;
    private static final long HALF_LIFE = 45_000L;

    @Test
    void firstCycleNeverFreezes() {
        assertEquals(0.0, EndFrostbite.frostRate(1.0, 0L, 0L, HALF_LIFE, TICKS_TO_FREEZE));
        assertEquals(0.0, EndFrostbite.frostRate(1.0, -1L, 500_000L, HALF_LIFE, TICKS_TO_FREEZE));
    }

    @Test
    void noEndSkyNoFrost() {
        assertEquals(0.0, EndFrostbite.frostRate(0.0, 3L, 0L, HALF_LIFE, TICKS_TO_FREEZE));
        assertEquals(0.0, EndFrostbite.frostRate(Double.NaN, 3L, 0L, HALF_LIFE, TICKS_TO_FREEZE));
    }

    @Test
    void startsAtHalfAMinuteToFreeze() {
        assertEquals(30.0, EndFrostbite.secondsToFreeze(0L, HALF_LIFE), 1e-9);
        assertEquals(TICKS_TO_FREEZE / 600.0, EndFrostbite.netRate(0L, HALF_LIFE, TICKS_TO_FREEZE), 1e-9);
    }

    @Test
    void gapToFloorHalvesEveryHalfLife() {
        assertEquals(7.0 + 23.0 / 2, EndFrostbite.secondsToFreeze(HALF_LIFE, HALF_LIFE), 1e-9);
        assertEquals(7.0 + 23.0 / 4, EndFrostbite.secondsToFreeze(2 * HALF_LIFE, HALF_LIFE), 1e-9);
    }

    @Test
    void approachesButNeverReachesPowderSnowPace() {
        double far = EndFrostbite.netRate(50 * HALF_LIFE, HALF_LIFE, TICKS_TO_FREEZE);
        assertTrue(far > 0.999 && far < 1.0, "net rate " + far);
        assertTrue(EndFrostbite.secondsToFreeze(50 * HALF_LIFE, HALF_LIFE) > 7.0);
    }

    @Test
    void rateNeverDecreasesWithDistance() {
        double last = 0.0;
        for (long d = 0; d <= 10 * HALF_LIFE; d += 1_000) {
            double r = EndFrostbite.netRate(d, HALF_LIFE, TICKS_TO_FREEZE);
            assertTrue(r >= last, "at " + d);
            last = r;
        }
    }

    @Test
    void noHalfLifeHoldsTheStartingPace() {
        assertEquals(30.0, EndFrostbite.secondsToFreeze(1_000_000L, 0L), 1e-9);
    }

    @Test
    void fullSkyOutpacesVanillaThawAndHalfSkyThaws() {
        assertTrue(EndFrostbite.frostRate(1.0, 1L, 0L, HALF_LIFE, TICKS_TO_FREEZE) > EndFrostbite.VANILLA_THAW);
        assertTrue(EndFrostbite.frostRate(0.5, 1L, 0L, HALF_LIFE, TICKS_TO_FREEZE) < EndFrostbite.VANILLA_THAW);
    }

    @Test
    void statelessStepAveragesTheRate() {
        for (double rate : new double[] {0.1167, 2.1167, 2.5, 2.9999, 3.0}) {
            long sum = 0;
            for (long t = 1; t <= 10_000; t++) {
                int f = EndFrostbite.frostThisTick(rate, t);
                assertTrue(f >= 0);
                sum += f;
            }
            assertEquals(Math.round(10_000 * rate), sum, 1.0, "rate " + rate);
        }
        assertEquals(0, EndFrostbite.frostThisTick(0.0, 5L));
        assertEquals(0, EndFrostbite.frostThisTick(Double.NaN, 5L));
    }
}
