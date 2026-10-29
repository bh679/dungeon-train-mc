package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CometShowerPlanTest {

    /** Strikes per minute before showers carried them: every comet's chance x the share aimed at the train. */
    private static double strikesPerMinuteBefore(long lap, double depth) {
        return 1200.0 * SpheresComets.chancePerTick(lap, 1.0, 1.0) * SpheresComets.aimedShare(lap, depth);
    }

    @Test
    void showersKeepTheStrikeRate() {
        double[][] where = {{0, 0.0}, {0, 0.5}, {0, 1.0}, {1, 0.0}, {2, 0.0}, {3, 0.0}, {9, 0.0}};
        for (double[] w : where) {
            long lap = (long) w[0];
            double before = strikesPerMinuteBefore(lap, w[1]);
            assertEquals(before, CometShowerPlan.of(lap, w[1]).strikesPerMinute(), before * 0.01,
                "lap " + lap + " depth " + w[1]);
        }
        assertEquals(0.05, CometShowerPlan.of(0L, 0.0).strikesPerMinute(), 1e-9);
        assertEquals(1.0, CometShowerPlan.of(0L, 1.0).strikesPerMinute(), 1e-9);
        assertEquals(4.0, CometShowerPlan.of(1L, 0.0).strikesPerMinute(), 1e-9);
        assertEquals(8.0, CometShowerPlan.of(2L, 0.0).strikesPerMinute(), 1e-9);
        assertEquals(10.0, CometShowerPlan.of(3L, 0.0).strikesPerMinute(), 1e-9);
    }

    @Test
    void deeperShowersAreBiggerFasterAndMoreOften() {
        CometShowerPlan first = CometShowerPlan.of(0L, 0.0);
        CometShowerPlan lap1 = CometShowerPlan.of(1L, 0.0);
        CometShowerPlan lap3 = CometShowerPlan.of(3L, 0.0);
        assertEquals(1.0, first.strikesMean(), 1e-9);
        assertEquals(2.0, CometShowerPlan.of(0L, 1.0).strikesMean(), 1e-9);
        assertEquals(3.0, lap1.strikesMean(), 1e-9);
        assertEquals(8.0, lap3.strikesMean(), 1e-9);
        assertTrue(lap1.meanPeriodTicks() < CometShowerPlan.of(0L, 1.0).meanPeriodTicks());
        assertTrue(lap3.speedScale() > lap1.speedScale() && lap1.speedScale() > first.speedScale());
        assertEquals(24000.0, first.meanPeriodTicks(), 1e-6, "one shower every 20 minutes where comets start");
        assertEquals(900.0, lap1.meanPeriodTicks(), 1e-6, "one every 45 s in lap 1");
    }

    @Test
    void lapOneOnLeavesQuietBetweenShowers() {
        assertEquals(440, CometShowerPlan.of(1L, 0.0).durationTicks(3), "22 s");
        assertEquals(580, CometShowerPlan.of(2L, 0.0).durationTicks(6), "29 s");
        assertEquals(630, CometShowerPlan.of(3L, 0.0).durationTicks(8), "31.5 s");
        for (long lap = 1L; lap <= 5L; lap++) {
            double share = CometShowerPlan.of(lap, 0.0).showerShare();
            assertTrue(share > 0.4 && share < 0.7, "lap " + lap + " is in a shower " + share + " of the time");
        }
    }

    @Test
    void strikesRollWholeAroundTheMean() {
        CometShowerPlan halfway = CometShowerPlan.of(0L, 0.5);
        assertEquals(1, halfway.rollStrikes(0.9));
        assertEquals(2, halfway.rollStrikes(0.1));
        assertEquals(3, CometShowerPlan.of(1L, 0.0).rollStrikes(0.0));
        assertEquals(3, CometShowerPlan.of(1L, 0.0).rollStrikes(0.99));
    }
}
