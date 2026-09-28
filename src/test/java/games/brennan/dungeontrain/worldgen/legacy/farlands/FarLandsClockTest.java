package games.brennan.dungeontrain.worldgen.legacy.farlands;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Far Lands clock's speed curve: a climb through the core, a fast fall in the exit fade. */
final class FarLandsClockTest {

    private static final double EPS = 1e-3;

    @Test
    @DisplayName("normal speed before the entry wall")
    void normalBeforeTheEntryWall() {
        assertEquals(1.0f, FarLandsClock.speedInCore(-500), EPS);
        assertEquals(1.0f, FarLandsClock.speedInCore(0), EPS);
        assertEquals(1.0f, FarLandsClock.speedInCore(FarLandsClock.ENTRY_WALL), EPS);
        assertEquals(1.0f, FarLandsClock.speedInCore(Double.NaN), EPS);
    }

    @Test
    @DisplayName("climbs the whole way through the core, peaking at its end")
    void climbsThroughTheCore() {
        float prev = 1.0f;
        for (int p = FarLandsClock.ENTRY_WALL + 1; p <= FarLandsShift.SCRIPT_LEN; p += 16) {
            float s = FarLandsClock.speedInCore(p);
            assertTrue(s > prev, "no climb at " + p);
            prev = s;
        }
        assertEquals(FarLandsClock.MAX_SPEED, FarLandsClock.speedInCore(FarLandsShift.SCRIPT_LEN), EPS);
        float canyon = FarLandsClock.speedInCore(FarLandsShift.CLOSING_END);
        assertTrue(canyon > 1.0f && canyon < FarLandsClock.MAX_SPEED, "canyon mid-climb, was " + canyon);
    }

    @Test
    @DisplayName("falls fast from the peak to normal in the exit fade")
    void fallsFastInTheExitFade() {
        assertEquals(FarLandsClock.MAX_SPEED, FarLandsClock.speedPastCore(0), EPS);
        float prev = FarLandsClock.MAX_SPEED;
        for (int b = 1; b <= FarLandsClock.SLOW_DOWN_BLOCKS; b++) {
            float s = FarLandsClock.speedPastCore(b);
            assertTrue(s <= prev, "rises at " + b);
            prev = s;
        }
        assertEquals((1.0f + FarLandsClock.MAX_SPEED) / 2.0f,
            FarLandsClock.speedPastCore(FarLandsClock.SLOW_DOWN_BLOCKS / 2), EPS);
        assertEquals(1.0f, FarLandsClock.speedPastCore(FarLandsClock.SLOW_DOWN_BLOCKS), EPS);
        assertEquals(1.0f, FarLandsClock.speedPastCore(5_000), EPS);
    }
}
