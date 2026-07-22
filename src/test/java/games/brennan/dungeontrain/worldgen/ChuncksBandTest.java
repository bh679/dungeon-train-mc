package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure tests for the {@link ChuncksBand} per-chunk classification ({@link ChuncksBand#classify}) and the
 * slice cut ({@link ChuncksBand#cutY}). The world-facing entry points ({@code kindOf}/{@code isVoidChunk})
 * need a {@code ServerLevel} and are covered by in-game tests; here we test the seed-stable noise directly.
 */
final class ChuncksBandTest {

    private static final long SEED = 0x123456789ABCDEFL;

    @Test
    @DisplayName("classify is deterministic in (seed, chunkX, chunkZ)")
    void deterministic() {
        for (int i = 0; i < 50; i++) {
            int cx = i * 7 - 100, cz = i * 3 - 40;
            ChuncksBand.Kind a = ChuncksBand.classify(SEED, cx, cz, 0.3, 0.5);
            ChuncksBand.Kind b = ChuncksBand.classify(SEED, cx, cz, 0.3, 0.5);
            assertEquals(a, b, "same inputs must classify identically at (" + cx + "," + cz + ")");
        }
    }

    @Test
    @DisplayName("kept fraction tracks keepDensity, and slice fraction tracks sliceRatio of the kept chunks")
    void distributionTracksKnobs() {
        int n = 0, kept = 0, slice = 0;
        for (int cx = -80; cx < 80; cx++) {
            for (int cz = -80; cz < 80; cz++) {
                n++;
                ChuncksBand.Kind k = ChuncksBand.classify(SEED, cx, cz, 0.25, 0.4);
                if (k != ChuncksBand.Kind.VOID) {
                    kept++;
                    if (k == ChuncksBand.Kind.SLICE) slice++;
                }
            }
        }
        double keptFrac = (double) kept / n;
        double sliceFrac = (double) slice / kept;
        assertTrue(Math.abs(keptFrac - 0.25) < 0.03, "kept fraction " + keptFrac + " should be ≈0.25");
        assertTrue(Math.abs(sliceFrac - 0.40) < 0.05, "slice fraction " + sliceFrac + " should be ≈0.40");
    }

    @Test
    @DisplayName("keepDensity 0 → all void; 1 → none void")
    void densityExtremes() {
        for (int cx = 0; cx < 40; cx++) {
            assertEquals(ChuncksBand.Kind.VOID, ChuncksBand.classify(SEED, cx, cx, 0.0, 0.5));
            assertTrue(ChuncksBand.classify(SEED, cx, cx, 1.0, 0.5) != ChuncksBand.Kind.VOID);
        }
    }

    @Test
    @DisplayName("slice cut Y is deterministic and stays within the window around the bed")
    void cutYWindow() {
        int bedY = 96;
        for (int cx = -30; cx < 30; cx++) {
            for (int cz = -30; cz < 30; cz++) {
                int y = ChuncksBand.cutY(SEED, cx, cz, bedY);
                assertEquals(y, ChuncksBand.cutY(SEED, cx, cz, bedY), "cutY deterministic");
                assertTrue(y >= bedY - 24 && y <= bedY + 8, "cutY " + y + " out of window at (" + cx + "," + cz + ")");
            }
        }
    }
}
