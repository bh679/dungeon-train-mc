package games.brennan.dungeontrain.worldgen.legacy;

import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for the per-chunk old/modern roll behind the legacy fades. */
final class LegacyBandsTest {

    private static final long SEED = 0x5EEDL;

    @Test
    @DisplayName("core chunks (ramp 1) always belong to the band")
    void coreAlwaysLegacy() {
        WorldGenCycle.LegacyHit core = new WorldGenCycle.LegacyHit(LegacyBandKind.BETA, 1.0);
        for (int cx = -50; cx < 50; cx++) {
            assertEquals(LegacyBandKind.BETA, LegacyBands.classify(SEED, cx, cx * 7, core));
        }
    }

    @Test
    @DisplayName("fade chunks go old with probability ≈ ramp, deterministically")
    void fadeTracksRamp() {
        WorldGenCycle.LegacyHit half = new WorldGenCycle.LegacyHit(LegacyBandKind.BETA, 0.3);
        int old = 0;
        int total = 0;
        for (int cx = 0; cx < 100; cx++) {
            for (int cz = 0; cz < 100; cz++) {
                LegacyBandKind k = LegacyBands.classify(SEED, cx, cz, half);
                assertEquals(k, LegacyBands.classify(SEED, cx, cz, half));
                if (k != null) old++;
                total++;
            }
        }
        double share = (double) old / total;
        assertTrue(Math.abs(share - 0.3) < 0.03, "share " + share);
    }

    @Test
    @DisplayName("Alpha winter: the last share of the core and the exit fade, never outside the slot")
    void winterShare() {
        assertFalse(LegacyBands.isWinter(0.49, 0.5));
        assertTrue(LegacyBands.isWinter(0.5, 0.5));
        assertTrue(LegacyBands.isWinter(1.2, 0.5));       // exit fade
        assertFalse(LegacyBands.isWinter(-0.1, 0.5));     // lead gap / entry fade
        assertFalse(LegacyBands.isWinter(Double.NaN, 1.0));
        assertFalse(LegacyBands.isWinter(0.99, 0.0));
        assertFalse(LegacyBands.isWinter(1.5, 0.0));
        assertTrue(LegacyBands.isWinter(0.0, 1.0));
    }
}
