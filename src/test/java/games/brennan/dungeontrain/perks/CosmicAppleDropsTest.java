package games.brennan.dungeontrain.perks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CosmicAppleDropsTest {

    private static final int GROUPS = 10_000;

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
    @DisplayName("only Cosmic Apples before Diff-Level 5")
    void shallowIsTierOne() {
        for (int level = 0; level < CosmicAppleDrops.BITE_FROM; level++) {
            assertEquals(GROUPS, tally(level)[1], "level " + level);
        }
    }

    @Test
    @DisplayName("Celestial Bites from Diff-Level 5, no Violet Core before 10")
    void middleHasBites() {
        for (int level = CosmicAppleDrops.BITE_FROM; level < CosmicAppleDrops.CORE_FROM; level++) {
            int[] n = tally(level);
            assertEquals(0, n[3], "level " + level);
            double bites = n[2] / (double) GROUPS;
            assertTrue(Math.abs(bites - 0.20) < 0.02, "level " + level + " bites " + bites);
        }
    }

    @Test
    @DisplayName("from Diff-Level 10 about 65 / 25 / 10 per cent")
    void deepHasAllThree() {
        int[] n = tally(CosmicAppleDrops.CORE_FROM + 7);
        assertTrue(Math.abs(n[1] / (double) GROUPS - 0.65) < 0.02, "apples " + n[1]);
        assertTrue(Math.abs(n[2] / (double) GROUPS - 0.25) < 0.02, "bites " + n[2]);
        assertTrue(Math.abs(n[3] / (double) GROUPS - 0.10) < 0.02, "cores " + n[3]);
    }

    @Test
    @DisplayName("the tier is fixed per seed and drop")
    void tierDeterministic() {
        for (int i = 0; i < 200; i++) {
            assertEquals(CosmicAppleDrops.tierFor(5L, i, 12), CosmicAppleDrops.tierFor(5L, i, 12));
        }
    }
}
