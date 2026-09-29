package games.brennan.dungeontrain.worldgen.feature;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the pure {@link CavernNoise} field + carve rule: range, determinism, continuity, occupancy, window. */
final class CavernNoiseTest {

    @Test
    @DisplayName("field is in [0,1], deterministic per seed, and differs between seeds")
    void fieldRangeAndDeterminism() {
        int differ = 0;
        for (int x = -200; x <= 200; x += 7) {
            for (int y = 60; y <= 250; y += 5) {
                for (int z = -64; z <= 64; z += 9) {
                    double a = CavernNoise.field01(1L, x, y, z);
                    assertTrue(a >= 0.0 && a <= 1.0, "out of range at " + x + "," + y + "," + z);
                    assertEquals(a, CavernNoise.field01(1L, x, y, z), 0.0, "non-deterministic");
                    if (a != CavernNoise.field01(2L, x, y, z)) differ++;
                }
            }
        }
        assertTrue(differ > 1000, "seed has no effect");
    }

    @Test
    @DisplayName("field is continuous: neighbouring blocks differ by a small bound")
    void fieldContinuity() {
        for (int x = -100; x <= 100; x += 3) {
            for (int y = 70; y <= 230; y += 4) {
                double a = CavernNoise.field01(7L, x, y, 0);
                assertTrue(Math.abs(a - CavernNoise.field01(7L, x + 1, y, 0)) < 0.08, "x jump at " + x + "," + y);
                assertTrue(Math.abs(a - CavernNoise.field01(7L, x, y + 1, 0)) < 0.1, "y jump at " + x + "," + y);
                assertTrue(Math.abs(a - CavernNoise.field01(7L, x, y, 1)) < 0.08, "z jump at " + x + "," + y);
            }
        }
    }

    @Test
    @DisplayName("occupancy: a deep mountain interior is 25–40% cavern air at the threshold")
    void occupancy() {
        long seed = 0x1234_5678L;
        int seaLevel = 63;
        double top = 240.0;
        int lo = CavernNoise.windowBottom(seaLevel), hi = CavernNoise.windowTop(top);
        int carved = 0, total = 0;
        for (int x = 0; x < 512; x += 2) {
            for (int z = 0; z < 128; z += 2) {
                for (int y = lo + CavernNoise.TAPER; y <= hi - CavernNoise.TAPER; y += 2) {
                    total++;
                    if (CavernNoise.apply(seed, x, y, z, seaLevel, top, 5.0) < 0.0) carved++;
                }
            }
        }
        double frac = carved / (double) total;
        assertTrue(frac >= 0.25 && frac <= 0.40, "occupancy " + frac + " outside 25–40%");
    }

    @Test
    @DisplayName("window: nothing carved at/below the floor, at/above the roof, above y 250, or in a short column")
    void windowBounds() {
        long seed = 99L;
        int seaLevel = 63;
        for (int x = 0; x < 256; x += 3) {
            for (int z = 0; z < 64; z += 5) {
                assertEquals(5.0, CavernNoise.apply(seed, x, CavernNoise.windowBottom(seaLevel), z, seaLevel, 240.0, 5.0), 0.0);
                assertEquals(5.0, CavernNoise.apply(seed, x, seaLevel, z, seaLevel, 240.0, 5.0), 0.0);
                assertEquals(5.0, CavernNoise.apply(seed, x, CavernNoise.windowTop(240.0), z, seaLevel, 240.0, 5.0), 0.0);
                assertEquals(5.0, CavernNoise.apply(seed, x, 251, z, seaLevel, 319.0, 5.0), 0.0);
                assertEquals(5.0, CavernNoise.apply(seed, x, 80, z, seaLevel, 90.0, 5.0), 0.0);   // top − 24 < floor
            }
        }
        assertEquals(250, CavernNoise.windowTop(319.0));
        assertEquals(176, CavernNoise.windowTop(200.5));
    }

    @Test
    @DisplayName("corner interpolation reproduces apply() exactly (the batched path's building blocks)")
    void cornersMatchApply() {
        long seed = 5L;
        int seaLevel = 63;
        double top = 230.0;
        int lo = CavernNoise.windowBottom(seaLevel), hi = CavernNoise.windowTop(top);
        double[] c = new double[8];
        for (int x = -40; x < 40; x++) {
            for (int y = lo; y <= hi; y += 5) {
                for (int z = -8; z < 8; z++) {
                    CavernNoise.corners(seed, CavernNoise.cellX(x), CavernNoise.cellY(y), CavernNoise.cellX(z), c);
                    double viaCorners = CavernNoise.carve(CavernNoise.interpolate(c, x, y, z), y, lo, hi, 3.0);
                    assertEquals(CavernNoise.apply(seed, x, y, z, seaLevel, top, 3.0), viaCorners, 0.0,
                            "mismatch at " + x + "," + y + "," + z);
                }
            }
        }
    }
}
