package games.brennan.dungeontrain.worldgen.legacy.farlands;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Far Lands clock's speed curve along the band's script. */
final class FarLandsClockTest {

    private static final double EPS = 1e-4;

    @Test
    @DisplayName("normal speed before the entry wall and after the exit wall")
    void normalOutsideTheWalls() {
        assertEquals(1.0f, FarLandsClock.speedAt(-500), EPS);
        assertEquals(1.0f, FarLandsClock.speedAt(0), EPS);
        assertEquals(1.0f, FarLandsClock.speedAt(FarLandsClock.ENTRY_WALL), EPS);
        assertEquals(1.0f, FarLandsClock.speedAt(FarLandsShift.EXIT_WALL), EPS);
        assertEquals(1.0f, FarLandsClock.speedAt(FarLandsShift.SCRIPT_LEN), EPS);
        assertEquals(1.0f, FarLandsClock.speedAt(Double.NaN), EPS);
    }

    @Test
    @DisplayName("full speed through the closing walls, canyon and opening")
    void fullSpeedInTheHeart() {
        for (int p = FarLandsShift.ENTRY_END; p <= FarLandsShift.OPENING_END; p += 50) {
            assertEquals(FarLandsClock.MAX_SPEED, FarLandsClock.speedAt(p), EPS, "at " + p);
        }
    }

    @Test
    @DisplayName("ramps up monotonically after the entry wall and down before the exit wall")
    void rampsAreMonotone() {
        float prev = 1.0f;
        for (int p = FarLandsClock.ENTRY_WALL; p <= FarLandsShift.ENTRY_END; p += 8) {
            float s = FarLandsClock.speedAt(p);
            assertTrue(s >= prev, "ramp up dips at " + p);
            prev = s;
        }
        prev = FarLandsClock.MAX_SPEED;
        for (int p = FarLandsShift.EXIT_WALL - FarLandsClock.RAMP; p <= FarLandsShift.EXIT_WALL; p += 8) {
            float s = FarLandsClock.speedAt(p);
            assertTrue(s <= prev, "ramp down rises at " + p);
            prev = s;
        }
    }

    @Test
    @DisplayName("the two ramps mirror each other")
    void rampsAreSymmetric() {
        for (int d = 0; d <= FarLandsClock.RAMP; d += 23) {
            assertEquals(FarLandsClock.speedAt(FarLandsClock.ENTRY_WALL + d),
                FarLandsClock.speedAt(FarLandsShift.EXIT_WALL - d), EPS, "at " + d);
        }
        float mid = FarLandsClock.speedAt(FarLandsClock.ENTRY_WALL + FarLandsClock.RAMP / 2.0);
        assertEquals((1.0f + FarLandsClock.MAX_SPEED) / 2.0f, mid, EPS);
    }
}
