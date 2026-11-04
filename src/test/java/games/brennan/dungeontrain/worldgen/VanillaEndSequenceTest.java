package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static games.brennan.dungeontrain.worldgen.VanillaEndSequence.Stage;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VanillaEndSequenceTest {

    @Test
    @DisplayName("a vanilla piece opens with small islands and ends in the highlands")
    void openingIsSmallIslandsEndIsHighlands() {
        for (double noise : new double[] {0.0, 0.5, 0.999}) {
            assertEquals(Stage.SMALL_ISLANDS, VanillaEndSequence.stageAt(0.0, noise));
            assertEquals(Stage.SMALL_ISLANDS, VanillaEndSequence.stageAt(0.2, noise));
            assertEquals(Stage.HIGHLANDS, VanillaEndSequence.stageAt(0.6, noise));
            assertEquals(Stage.HIGHLANDS, VanillaEndSequence.stageAt(1.0, noise));
        }
    }

    @Test
    @DisplayName("the highlands' share rises 0 → 1 across the seam and is monotonic")
    void shareIsMonotonicAcrossTheSeam() {
        double prev = -1.0;
        for (double p = 0.0; p <= 1.0; p += 0.01) {
            double share = VanillaEndSequence.highlandsShare(p);
            assertTrue(share >= prev, "share dropped at " + p);
            assertTrue(share >= 0.0 && share <= 1.0);
            prev = share;
        }
        assertEquals(0.0, VanillaEndSequence.highlandsShare(VanillaEndSequence.SMALL_ISLANDS_SHARE - VanillaEndSequence.SEAM_HALF_BLEND));
        assertEquals(0.5, VanillaEndSequence.highlandsShare(VanillaEndSequence.SMALL_ISLANDS_SHARE), 1e-9);
        assertEquals(1.0, VanillaEndSequence.highlandsShare(VanillaEndSequence.SMALL_ISLANDS_SHARE + VanillaEndSequence.SEAM_HALF_BLEND));
    }

    @Test
    @DisplayName("mid-seam the two stages interleave by column noise, deterministically per seed")
    void seamInterleavesByColumnNoise() {
        double mid = VanillaEndSequence.SMALL_ISLANDS_SHARE;
        List<Stage> a = new ArrayList<>();
        List<Stage> b = new ArrayList<>();
        int small = 0;
        int high = 0;
        for (int x = 0; x < 400; x += 4) {
            for (int z = -200; z < 200; z += 4) {
                Stage s = VanillaEndSequence.stageAt(mid, 42L, x, z);
                a.add(s);
                b.add(VanillaEndSequence.stageAt(mid, 42L, x, z));
                if (s == Stage.SMALL_ISLANDS) small++;
                else high++;
            }
        }
        assertEquals(a, b, "same seed and column must give the same stage");
        assertTrue(small > 0 && high > 0, "both stages present mid-seam: small=" + small + " high=" + high);
    }

    @Test
    @DisplayName("away from the seam the column noise plays no part")
    void awayFromSeamNoiseIsIrrelevant() {
        for (long seed : new long[] {1L, 2L, 99L}) {
            assertEquals(Stage.SMALL_ISLANDS, VanillaEndSequence.stageAt(0.1, seed, 123, -77));
            assertEquals(Stage.HIGHLANDS, VanillaEndSequence.stageAt(0.9, seed, 123, -77));
        }
    }

    @Test
    @DisplayName("small islands stamp thinner than the highlands; -1 means no sequence")
    void fillAndApplies() {
        assertTrue(VanillaEndSequence.islandFill(Stage.SMALL_ISLANDS) < VanillaEndSequence.islandFill(Stage.HIGHLANDS));
        assertEquals(1.0, VanillaEndSequence.islandFill(Stage.HIGHLANDS));
        assertFalse(VanillaEndSequence.applies(-1.0));
        assertTrue(VanillaEndSequence.applies(0.0));
    }
}
