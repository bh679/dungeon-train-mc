package games.brennan.dungeontrain.wind;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndFrostbiteTest {

    private static final int TICKS_TO_FREEZE = 140;
    private static final long HALF_LIFE = 45_000L;
    private static final double START = EndFrostbite.SECONDS_TO_FREEZE_AT_START;
    private static final double FLOOR = EndFrostbite.SECONDS_TO_FREEZE_FLOOR;

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
    void startsAtTheStartingPace() {
        assertEquals(START, EndFrostbite.secondsToFreeze(0L, HALF_LIFE), 1e-9);
        assertEquals(TICKS_TO_FREEZE / (START * 20.0), EndFrostbite.netRate(0L, HALF_LIFE, TICKS_TO_FREEZE), 1e-9);
    }

    @Test
    void gapToFloorHalvesEveryHalfLife() {
        assertEquals(FLOOR + (START - FLOOR) / 2, EndFrostbite.secondsToFreeze(HALF_LIFE, HALF_LIFE), 1e-9);
        assertEquals(FLOOR + (START - FLOOR) / 4, EndFrostbite.secondsToFreeze(2 * HALF_LIFE, HALF_LIFE), 1e-9);
    }

    @Test
    void approachesButNeverReachesPowderSnowPace() {
        double far = EndFrostbite.netRate(50 * HALF_LIFE, HALF_LIFE, TICKS_TO_FREEZE);
        assertTrue(far > 0.999 && far < 1.0, "net rate " + far);
        assertTrue(EndFrostbite.secondsToFreeze(50 * HALF_LIFE, HALF_LIFE) > FLOOR);
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

    /**
     * The shipped curve on the shipped layout (period 101,492, {@code CycleLayoutTest#shippedGeometry}). Cycle 2 is
     * a doubled run starting one period in, so a base offset {@code u} sits {@code 2u} blocks into the cold: the
     * first End band (u ≈ 12,000) stays near its old ≈ 26 s, BetterEnd (u ≈ 42,500) is down to ≈ 17 s.
     */
    @Test
    void shippedCycleTwoPace() {
        long halfLife = Math.round(101_492L * EndFrostbite.HALF_LIFE_CYCLES);
        assertEquals(26.0, EndFrostbite.secondsToFreeze(2 * 12_000L, halfLife), 1.0);
        assertEquals(17.0, EndFrostbite.secondsToFreeze(2 * 42_500L, halfLife), 1.0);
    }

    @Test
    void noHalfLifeHoldsTheStartingPace() {
        assertEquals(START, EndFrostbite.secondsToFreeze(1_000_000L, 0L), 1e-9);
    }

    @Test
    void fullSkyOutpacesVanillaThawAndHalfSkyThaws() {
        assertTrue(EndFrostbite.frostRate(1.0, 1L, 0L, HALF_LIFE, TICKS_TO_FREEZE) > EndFrostbite.VANILLA_THAW);
        assertTrue(EndFrostbite.frostRate(0.5, 1L, 0L, HALF_LIFE, TICKS_TO_FREEZE) < EndFrostbite.VANILLA_THAW);
    }

    @Test
    void leatherDoublesTimePerPiece() {
        assertEquals(1.0, EndFrostbite.leatherFactor(0));
        assertEquals(2.0, EndFrostbite.leatherFactor(1));
        assertEquals(4.0, EndFrostbite.leatherFactor(2));
        assertEquals(8.0, EndFrostbite.leatherFactor(3));
        assertEquals(16.0, EndFrostbite.leatherFactor(4));
        assertEquals(16.0, EndFrostbite.leatherFactor(9), "clamped at four armour slots");
        assertEquals(1.0, EndFrostbite.leatherFactor(-1));
        for (int pieces = 0; pieces <= 4; pieces++) {
            assertEquals(START * (1 << pieces), EndFrostbite.secondsToFreeze(0L, HALF_LIFE, pieces), 1e-9);
            assertEquals(EndFrostbite.netRate(HALF_LIFE, HALF_LIFE, TICKS_TO_FREEZE) / (1 << pieces),
                    EndFrostbite.netRate(HALF_LIFE, HALF_LIFE, TICKS_TO_FREEZE, pieces), 1e-12);
        }
        assertEquals(EndFrostbite.secondsToFreeze(HALF_LIFE, HALF_LIFE),
                EndFrostbite.secondsToFreeze(HALF_LIFE, HALF_LIFE, 0), 1e-12);
    }

    @Test
    void leatherNeverStopsTheFrost() {
        double full = EndFrostbite.frostRate(1.0, 1L, 0L, HALF_LIFE, TICKS_TO_FREEZE, 4);
        assertTrue(full > EndFrostbite.VANILLA_THAW, "rate " + full);
        assertTrue(full < EndFrostbite.frostRate(1.0, 1L, 0L, HALF_LIFE, TICKS_TO_FREEZE, 0));
        assertEquals(0.0, EndFrostbite.frostRate(1.0, 0L, 0L, HALF_LIFE, TICKS_TO_FREEZE, 4));
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
