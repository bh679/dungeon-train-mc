package games.brennan.dungeontrain.worldgen;

import net.minecraft.world.level.block.Rotation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure tests for the {@link StacksBand} per-chunk classification ({@link StacksBand#classify}), the
 * corridor exclusion, and the seed-stable pick/rotation helpers the feature uses. The world-facing entry
 * points ({@code kindOf}/{@code isVoidOrStackChunk}) need a {@code ServerLevel} and are covered by
 * in-game tests; here we test the seed-stable noise directly.
 */
final class StacksBandTest {

    private static final long SEED = 0x123456789ABCDEFL;

    @Test
    @DisplayName("classify is deterministic in (seed, chunkX, chunkZ)")
    void deterministic() {
        for (int i = 0; i < 50; i++) {
            int cx = i * 7 - 100, cz = i * 3 - 40;
            StacksBand.Kind a = StacksBand.classify(SEED, cx, cz, 0.6, 0.3, false);
            StacksBand.Kind b = StacksBand.classify(SEED, cx, cz, 0.6, 0.3, false);
            assertEquals(a, b, "same inputs must classify identically at (" + cx + "," + cz + ")");
        }
    }

    @Test
    @DisplayName("void fraction tracks voidRamp, and stack fraction tracks density of the void chunks")
    void distributionTracksKnobs() {
        int n = 0, voided = 0, stacks = 0;
        for (int cx = -80; cx < 80; cx++) {
            for (int cz = -80; cz < 80; cz++) {
                n++;
                StacksBand.Kind k = StacksBand.classify(SEED, cx, cz, 0.6, 0.3, false);
                if (k != StacksBand.Kind.TERRAIN) {
                    voided++;
                    if (k == StacksBand.Kind.STACK) stacks++;
                }
            }
        }
        double voidFrac = (double) voided / n;
        double stackFrac = (double) stacks / voided;
        assertTrue(Math.abs(voidFrac - 0.6) < 0.03, "void fraction " + voidFrac + " should be ≈0.6");
        assertTrue(Math.abs(stackFrac - 0.3) < 0.05, "stack fraction " + stackFrac + " should be ≈0.3");
    }

    @Test
    @DisplayName("voidRamp 0 → all terrain; 1 → no terrain; density 0 → no stacks; density 1 → every void chunk is a stack")
    void rampExtremes() {
        for (int cx = 0; cx < 40; cx++) {
            assertEquals(StacksBand.Kind.TERRAIN, StacksBand.classify(SEED, cx, cx, 0.0, 0.5, false));
            assertNotEquals(StacksBand.Kind.TERRAIN, StacksBand.classify(SEED, cx, cx, 1.0, 0.5, false));
            assertEquals(StacksBand.Kind.VOID, StacksBand.classify(SEED, cx, cx, 1.0, 0.0, false));
            assertEquals(StacksBand.Kind.STACK, StacksBand.classify(SEED, cx, cx, 1.0, 1.0, false));
        }
    }

    @Test
    @DisplayName("a corridor chunk is never a stack — void at most, terrain if the ramp leaves it")
    void corridorNeverStacks() {
        for (int cx = -60; cx < 60; cx++) {
            for (int cz = -3; cz <= 3; cz++) {
                StacksBand.Kind k = StacksBand.classify(SEED, cx, cz, 1.0, 1.0, true);
                assertEquals(StacksBand.Kind.VOID, k, "corridor chunk (" + cx + "," + cz + ") must be VOID");
                assertEquals(StacksBand.Kind.TERRAIN, StacksBand.classify(SEED, cx, cz, 0.0, 1.0, true));
            }
        }
    }

    @Test
    @DisplayName("touchesCorridor: chunk rows overlapping [wallMinZ, wallMaxZ] only")
    void corridorOverlap() {
        // Default-ish geometry: walls at z=-4 .. z=width+3 for width 4 → [-4, 7] → chunk rows -1 and 0.
        assertTrue(StacksBand.touchesCorridor(-1, -4, 7));
        assertTrue(StacksBand.touchesCorridor(0, -4, 7));
        assertFalse(StacksBand.touchesCorridor(-2, -4, 7));
        assertFalse(StacksBand.touchesCorridor(1, -4, 7));
        // A wide corridor reaching z=16 spills into row 1.
        assertTrue(StacksBand.touchesCorridor(1, -4, 16));
    }

    @Test
    @DisplayName("pickIndex stays in range, is deterministic, and the attempt salt rerolls")
    void pickIndex() {
        int n = 1180;
        boolean anyDiffers = false;
        for (int cx = -30; cx < 30; cx++) {
            for (int cz = -30; cz < 30; cz++) {
                int a = StacksBand.pickIndex(SEED, cx, cz, 0, n);
                assertEquals(a, StacksBand.pickIndex(SEED, cx, cz, 0, n), "pick deterministic");
                assertTrue(a >= 0 && a < n, "pick " + a + " out of range");
                int b = StacksBand.pickIndex(SEED, cx, cz, 1, n);
                assertTrue(b >= 0 && b < n, "reroll " + b + " out of range");
                if (a != b) anyDiffers = true;
            }
        }
        assertTrue(anyDiffers, "attempt salt must change the pick somewhere");
        assertEquals(-1, StacksBand.pickIndex(SEED, 0, 0, 0, 0));
        assertEquals(0, StacksBand.pickIndex(SEED, 5, 5, 3, 1));
    }

    @Test
    @DisplayName("pickIndex covers the whole catalogue roughly uniformly")
    void pickIndexUniform() {
        int n = 10;
        int[] counts = new int[n];
        int total = 0;
        for (int cx = -50; cx < 50; cx++) {
            for (int cz = -50; cz < 50; cz++) {
                counts[StacksBand.pickIndex(SEED, cx, cz, 0, n)]++;
                total++;
            }
        }
        for (int i = 0; i < n; i++) {
            double frac = (double) counts[i] / total;
            assertTrue(Math.abs(frac - 0.1) < 0.02, "bucket " + i + " fraction " + frac + " should be ≈0.1");
        }
    }

    @Test
    @DisplayName("rotationFor is deterministic and uses every rotation")
    void rotation() {
        java.util.EnumSet<Rotation> seen = java.util.EnumSet.noneOf(Rotation.class);
        for (int cx = -30; cx < 30; cx++) {
            for (int cz = -30; cz < 30; cz++) {
                Rotation r = StacksBand.rotationFor(SEED, cx, cz);
                assertEquals(r, StacksBand.rotationFor(SEED, cx, cz));
                seen.add(r);
            }
        }
        assertEquals(java.util.EnumSet.allOf(Rotation.class), seen);
    }
}
