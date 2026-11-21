package games.brennan.dungeontrain.perks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CosmicAppleDropsTest {

    private static final int GROUPS = 10_000;

    @Test
    @DisplayName("apples stay shut until one unlock point is spent")
    void opensAfterFirstUnlockPoint() {
        assertTrue(!CosmicAppleDrops.opensAt(0));
        assertTrue(CosmicAppleDrops.opensAt(1));
        assertTrue(CosmicAppleDrops.opensAt(3));
    }

    @Test
    @DisplayName("about one carriage group in five draws an apple")
    void rate() {
        int hits = 0;
        for (int g = -GROUPS / 2; g < GROUPS / 2; g++) if (CosmicAppleDrops.groupGetsApple(12345L, g)) hits++;
        double rate = hits / (double) GROUPS;
        assertTrue(Math.abs(rate - 0.2) < 0.015, "rate " + rate);
    }

    @Test
    @DisplayName("the draw is fixed per seed and group, and a new seed draws other groups")
    void deterministic() {
        StringBuilder a = new StringBuilder();
        StringBuilder b = new StringBuilder();
        StringBuilder c = new StringBuilder();
        for (int g = 0; g < 200; g++) {
            a.append(CosmicAppleDrops.groupGetsApple(7L, g) ? '1' : '0');
            b.append(CosmicAppleDrops.groupGetsApple(7L, g) ? '1' : '0');
            c.append(CosmicAppleDrops.groupGetsApple(8L, g) ? '1' : '0');
        }
        assertEquals(a.toString(), b.toString());
        assertNotEquals(a.toString(), c.toString());
    }

    private static int[] tally(int diffLevel) {
        int[] n = new int[4];
        for (int i = 0; i < GROUPS; i++) n[CosmicAppleDrops.tierFor(99L, i, diffLevel)]++;
        return n;
    }

    @Test
    @DisplayName("only Cosmic Apples up to Diff-Level 5")
    void shallowIsTierOne() {
        for (int level = 0; level <= 5; level++) {
            assertEquals(GROUPS, tally(level)[1], "level " + level);
        }
    }

    @Test
    @DisplayName("no Violet Core up to Diff-Level 25, while Celestial Bites rise to about 6.6 per cent")
    void noCoresBeforeTwentyFive() {
        for (int level = 0; level <= 25; level++) {
            assertEquals(0, tally(level)[3], "level " + level);
        }
        double bites = tally(25)[2] / (double) GROUPS;
        assertTrue(Math.abs(bites - 0.066) < 0.01, "bites at 25 " + bites);
    }

    @Test
    @DisplayName("at Diff-Level 100 about 25 per cent Bites and 5 per cent Cores")
    void hundredMix() {
        int[] n = tally(100);
        assertTrue(Math.abs(n[2] / (double) GROUPS - 0.25) < 0.02, "bites " + n[2]);
        assertTrue(Math.abs(n[3] / (double) GROUPS - 0.052) < 0.01, "cores " + n[3]);
    }

    @Test
    @DisplayName("from Diff-Level 150 about 65 / 25 / 10 per cent")
    void deepHasAllThree() {
        for (int level : new int[] {150, 400}) {
            int[] n = tally(level);
            assertTrue(Math.abs(n[1] / (double) GROUPS - 0.65) < 0.02, "apples " + n[1]);
            assertTrue(Math.abs(n[2] / (double) GROUPS - 0.25) < 0.02, "bites " + n[2]);
            assertTrue(Math.abs(n[3] / (double) GROUPS - 0.10) < 0.02, "cores " + n[3]);
        }
    }

    @Test
    @DisplayName("going deeper never makes the rarer apples less likely")
    void chancesNeverFall() {
        for (int level = 1; level <= 300; level++) {
            assertTrue(CosmicAppleDrops.biteChance(level) >= CosmicAppleDrops.biteChance(level - 1), "bite " + level);
            assertTrue(CosmicAppleDrops.coreChance(level) >= CosmicAppleDrops.coreChance(level - 1), "core " + level);
        }
    }

    @Test
    @DisplayName("the tier is fixed per seed and drop")
    void tierDeterministic() {
        for (int i = 0; i < 200; i++) {
            assertEquals(CosmicAppleDrops.tierFor(5L, i, 120), CosmicAppleDrops.tierFor(5L, i, 120));
        }
    }
}
