package games.brennan.dungeontrain.perks.effects.builder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Drift Gift Repaid: 35% at rank 1, +5% per further point, capped, floored per item. */
class DriftGiftPayoutTest {

    private static final double EPS = 1e-9;

    @Test
    void rateStartsAtThirtyFivePercentAndClimbsFivePerPoint() {
        assertEquals(0.0, DriftGiftPayout.rate(0), EPS);
        assertEquals(0.35, DriftGiftPayout.rate(1), EPS);
        assertEquals(0.40, DriftGiftPayout.rate(2), EPS);
        assertEquals(0.60, DriftGiftPayout.rate(6), EPS);
        assertEquals(35, DriftGiftPayout.ratePercent(1));
        assertEquals(40, DriftGiftPayout.ratePercent(2));
    }

    @Test
    void rateIsCapped() {
        assertEquals(DriftGiftPayout.MAX_RATE, DriftGiftPayout.rate(12), EPS);
        assertEquals(DriftGiftPayout.MAX_RATE, DriftGiftPayout.rate(99), EPS);
    }

    @Test
    void perItemFloorsAndIgnoresWorthlessItems() {
        // A diamond at 64 sixteenths (4 emeralds): 35% = 22.4 -> 22.
        assertEquals(22, DriftGiftPayout.perItem(64, 1));
        assertEquals(25, DriftGiftPayout.perItem(64, 2)); // 25.6 -> 25
        assertEquals(0, DriftGiftPayout.perItem(1, 1));   // a stick: 0.35 -> 0, nothing to pay
        assertEquals(0, DriftGiftPayout.perItem(0, 3));
        assertEquals(0, DriftGiftPayout.perItem(64, 0));  // perk inactive
    }

    @Test
    void emeraldsAndRemainderSplitSixteenths() {
        assertEquals(1, DriftGiftPayout.emeralds(22));
        assertEquals(6, DriftGiftPayout.remainder(22));
        assertEquals(0, DriftGiftPayout.emeralds(15));
        assertEquals(15, DriftGiftPayout.remainder(15));
    }

    @Test
    void emeraldsToCoverRoundsUp() {
        assertEquals(0, DriftGiftPayout.emeraldsToCover(0));
        assertEquals(1, DriftGiftPayout.emeraldsToCover(1));
        assertEquals(1, DriftGiftPayout.emeraldsToCover(16));
        assertEquals(2, DriftGiftPayout.emeraldsToCover(17));
    }
}
