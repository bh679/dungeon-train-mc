package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.wind.WindDistance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wind distance mirrors onto the reversed cycle behind spawn: a reversed column reads its forward twin's. */
final class WindDistanceTest {

    private static final long START = 10_000L;
    private static final CycleLayout LAYOUT = CycleLayoutTest.shipped();
    private static final long P = LAYOUT.period();
    private static final long BUFFER = LAYOUT.length(0);

    private static final WorldGenCycle C = new WorldGenCycle(START, 10_000, 40, new int[] {1, 2, 4, 8, 15}, 32, 0, 300, 5000,
            120, 500, 5000, 600, 5000, 600, 10_000, 8000, 1500, 5000, 0.3, 0.4, 6550, 750, 5000, 8000, 1500, 10_000, 0.08,
            CycleLayoutTest.eraDefaults(), LAYOUT, 0);

    private static final long WIND = C.upsideDownEndX();

    private static long fx(long u, int k) {
        return START + CycleLayout.runStart(k, P) + (u << k);
    }

    private static long bx(long u, int k) {
        return START - BUFFER - CycleLayout.runStart(k + 1, P) + (u << k);
    }

    @Test
    @DisplayName("forward of spawn the distance is unchanged")
    void forward() {
        assertTrue(WIND != Long.MIN_VALUE);
        for (double x : new double[] {START, WIND - 10.5, WIND, WIND + 1234.25}) {
            assertEquals(x - WIND, WindDistance.at(C, x, WIND), 1e-9);
        }
        assertEquals(-1.0, WindDistance.at(C, WIND + 50, Long.MIN_VALUE));
    }

    @Test
    @DisplayName("a reversed column reads its forward twin's distance")
    void reversedReadsTwin() {
        for (int k = 0; k <= 2; k++) {
            for (long u : new long[] {0L, P / 3, P / 2, P - 1}) {
                assertEquals(WindDistance.at(C, fx(u, k), WIND), WindDistance.at(C, bx(u, k), WIND), 1e-9);
            }
        }
        long uWindy = WIND - START + 100; // a run-0 base coordinate past the wind start
        assertTrue(WindDistance.at(C, bx(uWindy, 0), WIND) > 0);
    }

    @Test
    @DisplayName("the buffer and twins of bands before the wind start read negative")
    void calmBehind() {
        for (long x = START - BUFFER; x < START; x += 97) assertTrue(WindDistance.at(C, x, WIND) < 0);
        assertTrue(WindDistance.at(C, bx(0, 0), WIND) < 0);
        assertTrue(WindDistance.at(C, bx(WIND - START - 1, 0), WIND) < 0);
    }

    @Test
    @DisplayName("fractional X stays continuous inside a reversed column")
    void fractional() {
        long x = bx(WIND - START + 100, 0);
        double base = WindDistance.at(C, x, WIND);
        assertEquals(base + 0.25, WindDistance.at(C, x + 0.25, WIND), 1e-9);
        assertEquals(base + 0.75, WindDistance.at(C, x + 0.75, WIND), 1e-9);
    }
}
