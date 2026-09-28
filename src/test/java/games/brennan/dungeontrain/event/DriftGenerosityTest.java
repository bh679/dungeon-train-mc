package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.event.DriftGenerosity.Ledger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DriftGenerosityTest {

    private static final double EPS = 1e-6;

    /** The worth credited for one close that moved the contents' worth by {@code delta}. */
    private static double close(Ledger[] ledger, double delta) {
        Ledger before = ledger[0];
        ledger[0] = before.apply(delta);
        return DriftGenerosity.creditable(before, ledger[0]);
    }

    @Test
    void aDepositIsCreditedInFull() {
        Ledger[] l = { Ledger.EMPTY };
        assertEquals(7.0, close(l, 7.0), EPS);
    }

    @Test
    void takingThingsOutCreditsNothing() {
        Ledger[] l = { Ledger.EMPTY };
        assertEquals(0.0, close(l, -12.0), EPS);
    }

    @Test
    void cyclingTheSameItemEarnsOnce() {
        Ledger[] l = { Ledger.EMPTY };
        assertEquals(7.0, close(l, 7.0), EPS);
        assertEquals(0.0, close(l, -7.0), EPS);
        assertEquals(0.0, close(l, 7.0), EPS);   // back to the old peak, not above it
        assertEquals(0.0, close(l, -7.0), EPS);
        assertEquals(0.0, close(l, 7.0), EPS);
    }

    @Test
    void onlyTheRiseAboveThePeakCounts() {
        Ledger[] l = { Ledger.EMPTY };
        close(l, 7.0);
        close(l, -7.0);
        assertEquals(3.0, close(l, 10.0), EPS);  // net 10 against a peak of 7
    }

    @Test
    void lootingFirstThenGivingBackEarnsNothingUntilPastZero() {
        Ledger[] l = { Ledger.EMPTY };
        close(l, -5.0);                          // took 5 worth of the carriage's loot
        assertEquals(0.0, close(l, 5.0), EPS);   // returning it is not a gift
        assertEquals(2.0, close(l, 2.0), EPS);
    }

    @Test
    void kindnessScalesWithWorth() {
        assertEquals(0.35F, DriftGenerosity.kindnessFor(7.0), EPS);
        assertEquals(0.0F, DriftGenerosity.kindnessFor(0.0), EPS);
        assertEquals(0.0F, DriftGenerosity.kindnessFor(-3.0), EPS);
    }

    @Test
    void kindnessIsCappedPerClose() {
        assertEquals(1.0F, DriftGenerosity.kindnessFor(20.0), EPS);
        assertEquals(1.0F, DriftGenerosity.kindnessFor(500.0), EPS);
    }
}
