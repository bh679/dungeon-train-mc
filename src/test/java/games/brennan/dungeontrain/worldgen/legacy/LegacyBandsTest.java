package games.brennan.dungeontrain.worldgen.legacy;

import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
    @DisplayName("cycle order runs Beta then Skylands; only Skylands is void below")
    void kindOrder() {
        assertArrayEquals(new LegacyBandKind[] {LegacyBandKind.BETA, LegacyBandKind.SKYLANDS}, LegacyBandKind.values());
        assertFalse(LegacyBandKind.BETA.voidBelow());
        assertTrue(LegacyBandKind.SKYLANDS.voidBelow());
    }

    @Test
    @DisplayName("Skylands follows the track bed, but never drops its lowest land under the world floor")
    void skyYOffset() {
        // Default train (bedY 76) on the y=32 floor: old y 52 at the bed.
        assertEquals(76 - LegacyBands.SKY_BED_OLD_Y, LegacyBands.skyYOffset(76, 32));
        // A train low enough to push islands through the floor is clamped: lowest land lands on the floor.
        int clamped = LegacyBands.skyYOffset(20, 32);
        assertEquals(32, clamped + LegacyBands.SKY_LOWEST_LAND_OLD_Y);
    }
}
