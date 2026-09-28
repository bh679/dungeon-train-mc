package games.brennan.dungeontrain.worldgen.legacy.farlands;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Far Lands clock's speed curve: a climb through the Far Lands, a fast fall just before the exit wall. */
final class FarLandsClockTest {

    private static final double EPS = 1e-3;
    private static final int LEN = FarLandsShift.SCRIPT_LEN;

    @Test
    @DisplayName("normal speed before the entry wall")
    void normalBeforeTheEntryWall() {
        assertEquals(1.0f, FarLandsClock.speedAt(-500), EPS);
        assertEquals(1.0f, FarLandsClock.speedAt(0), EPS);
        assertEquals(1.0f, FarLandsClock.speedAt(FarLandsClock.ENTRY_WALL), EPS);
        assertEquals(1.0f, FarLandsClock.speedAt(Double.NaN), EPS);
    }

    @Test
    @DisplayName("climbs steadily from the entry wall to the peak")
    void climbsToThePeak() {
        float prev = 1.0f;
        for (double p = FarLandsClock.ENTRY_WALL + 1; p <= FarLandsClock.PEAK; p += 16) {
            float s = FarLandsClock.speedAt(p);
            assertTrue(s > prev, "no climb at " + p);
            prev = s;
        }
        assertEquals(FarLandsClock.MAX_SPEED, FarLandsClock.speedAt(FarLandsClock.PEAK), EPS);
    }

    @Test
    @DisplayName("falls fast from the peak and is normal again 93.8% of the way through")
    void normalAgainBy938Percent() {
        float prev = FarLandsClock.MAX_SPEED;
        for (double p = FarLandsClock.PEAK; p <= FarLandsClock.FALL_END; p += 1) {
            float s = FarLandsClock.speedAt(p);
            assertTrue(s <= prev, "rises at " + p);
            prev = s;
        }
        assertEquals(1.0f, FarLandsClock.speedAt(0.938 * LEN), EPS);
        for (double p = 0.938 * LEN; p <= LEN; p += 8) {
            assertEquals(1.0f, FarLandsClock.speedAt(p), EPS, "not normal at " + p);
        }
        assertTrue(FarLandsClock.FALL_END < FarLandsShift.EXIT_WALL, "normal before the exit wall");
    }
}
