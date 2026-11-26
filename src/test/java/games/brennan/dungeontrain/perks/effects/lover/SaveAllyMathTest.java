package games.brennan.dungeontrain.perks.effects.lover;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SaveAllyMathTest {

    @Test
    void handSetPoints() {
        int[] range = {6, 7, 8, 8, 9, 9, 10};
        double[] every = {30, 25, 21, 19, 17.5, 16.5, 16};
        for (int r = 1; r <= 7; r++) {
            assertEquals(range[r - 1], SaveAllyMath.range(r), "range " + r);
            assertEquals(every[r - 1], SaveAllyMath.everyMinutes(r), 1e-9, "every " + r);
        }
    }

    @Test
    void easesTowardEightMinutesFromPointEight() {
        assertEquals(15.5, round1(SaveAllyMath.everyMinutes(8)));
        assertEquals(15.1, round1(SaveAllyMath.everyMinutes(9)));
        assertEquals(14.6, round1(SaveAllyMath.everyMinutes(10)));
        assertEquals(11.6, round1(SaveAllyMath.everyMinutes(20)));
        assertEquals(9.9, round1(SaveAllyMath.everyMinutes(30)));
        assertEquals(8.6, round1(SaveAllyMath.everyMinutes(50)));
        assertTrue(SaveAllyMath.everyMinutes(500) > 8.0);
    }

    @Test
    void rangeGainsABlockEveryTwoPoints() {
        assertEquals(10, SaveAllyMath.range(8));
        assertEquals(10, SaveAllyMath.range(9));
        assertEquals(11, SaveAllyMath.range(10));
        assertEquals(11, SaveAllyMath.range(11));
        assertEquals(12, SaveAllyMath.range(12));
    }

    @Test
    void offAtRankZero() {
        assertEquals(0, SaveAllyMath.range(0));
        assertEquals(0L, SaveAllyMath.everyTicks(0));
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
