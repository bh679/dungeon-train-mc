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

    @Test
    @DisplayName("cycle order runs Caves of Chaos, Beta, Far Lands, Skylands, Alpha, Infdev, Indev floating, Classic, Void; Skylands, floating and void are void below")
    void kindOrder() {
        assertArrayEquals(new LegacyBandKind[] {LegacyBandKind.CAVES_OF_CHAOS, LegacyBandKind.BETA, LegacyBandKind.FAR_LANDS, LegacyBandKind.SKYLANDS,
                        LegacyBandKind.ALPHA, LegacyBandKind.INFDEV, LegacyBandKind.FLOATING, LegacyBandKind.CLASSIC,
                        LegacyBandKind.VOID},
                LegacyBandKind.values());
        assertTrue(LegacyBandKind.VOID.voidBelow());
        assertFalse(LegacyBandKind.CAVES_OF_CHAOS.voidBelow());
        assertFalse(LegacyBandKind.BETA.voidBelow());
        assertTrue(LegacyBandKind.SKYLANDS.voidBelow());
        assertFalse(LegacyBandKind.ALPHA.voidBelow());
        assertFalse(LegacyBandKind.INFDEV.voidBelow());
        assertTrue(LegacyBandKind.FLOATING.voidBelow());
        assertFalse(LegacyBandKind.CLASSIC.voidBelow());
        assertFalse(LegacyBandKind.FAR_LANDS.voidBelow());
    }

    @Test
    @DisplayName("the floating band's core is all floating chunks")
    void floatingCore() {
        WorldGenCycle.LegacyHit core = new WorldGenCycle.LegacyHit(LegacyBandKind.FLOATING, 1.0);
        for (int cx = -20; cx < 20; cx++) {
            assertEquals(LegacyBandKind.FLOATING, LegacyBands.classify(SEED, cx, -cx * 3, core));
        }
    }

    @Test
    @DisplayName("Indev floating levels bed on the track, clamped so the whole level stays inside the world")
    void floatingYOffset() {
        assertEquals(76 - LegacyBands.FLOATING_BED_OLD_Y, LegacyBands.floatingYOffset(76, -64, 320));
        assertEquals(-64, LegacyBands.floatingYOffset(0, -64, 320));   // never below the floor
        assertEquals(320 - 256, LegacyBands.floatingYOffset(300, -64, 320)); // never through the ceiling
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
