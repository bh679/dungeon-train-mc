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
    @DisplayName("about one carriage group in ten draws an apple")
    void rate() {
        int hits = 0;
        for (int g = -GROUPS / 2; g < GROUPS / 2; g++) if (CosmicAppleDrops.groupGetsApple(12345L, g)) hits++;
        double rate = hits / (double) GROUPS;
        assertTrue(Math.abs(rate - 0.1) < 0.01, "rate " + rate);
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

    /** Diff-Level of a carriage at {@code pIdx}, the formula of {@code DifficultyProgression.positionTier}. */
    private static int level(int pIdx, int cpt, int delay, int offset) {
        int shifted = Math.max(0, Math.abs(pIdx) + offset);
        return Math.max(0, shifted / Math.max(1, cpt) - Math.max(0, delay));
    }

    @Test
    @DisplayName("every Diff-Level, Level 0 included, gets at least one apple on both sides, for many settings")
    void everyLevelHasAnApple() {
        int[] cpts = {1, 3, 7, 20, 40, 1000};
        for (long seed = 1; seed <= 6; seed++) {
            for (int gs = 1; gs <= 16; gs++) {
                for (int cpt : cpts) {
                    for (int delay : new int[] {0, 1}) {
                        for (int offset : new int[] {0, 50}) {
                            checkLevels(seed, gs, cpt, delay, offset);
                        }
                    }
                }
            }
        }
    }

    private static void checkLevels(long seed, int gs, int cpt, int delay, int offset) {
        int span = Math.min(30_000, cpt * 12 + gs * 4);
        java.util.Map<Integer, Integer> appleGroups = new java.util.HashMap<>();
        java.util.Set<Integer> levelsSeen = new java.util.HashSet<>();
        String ctx = "seed " + seed + " gs " + gs + " cpt " + cpt + " delay " + delay + " offset " + offset;
        long firstGroup = Math.floorDiv(-span, gs);
        long lastGroup = Math.floorDiv(span, gs);
        int lastFullPositive = Integer.MIN_VALUE;
        for (long g = firstGroup; g <= lastGroup; g++) {
            int anchor = (int) (g * gs);
            int lvl = level(anchor, cpt, delay, offset);
            int key = anchor < 0 ? -lvl - 1 : lvl;
            int slot = CosmicAppleDrops.slotFor(seed, anchor, gs, lvl, true, cpt, delay, offset);
            assertEquals(slot, CosmicAppleDrops.slotFor(seed, anchor, gs, lvl, true, cpt, delay, offset), ctx);
            assertTrue(slot < gs, ctx);
            long[] range = CosmicAppleDrops.levelGroups(lvl, anchor < 0, cpt, delay, offset, gs);
            assertTrue(range != null && g >= range[0] && g <= range[1], "group " + g + " outside its level " + ctx);
            levelsSeen.add(key);
            if (slot >= 0) appleGroups.merge(key, 1, Integer::sum);
            if (CosmicAppleDrops.isFloorGroup(seed, g, anchor < 0, lvl, cpt, delay, offset, gs)) {
                assertEquals(0, slot, "floor apple starts at the first carriage " + ctx);
            }
        }
        for (int key : levelsSeen) {
            int lvl = key < 0 ? -key - 1 : key;
            long[] range = CosmicAppleDrops.levelGroups(lvl, key < 0, cpt, delay, offset, gs);
            // Only levels whose groups all fall inside the swept span can be judged.
            if (range[0] < firstGroup || range[1] > lastGroup) continue;
            long want = Math.min(CosmicAppleDrops.MIN_PER_LEVEL, range[1] - range[0] + 1);
            assertTrue(appleGroups.getOrDefault(key, 0) >= want,
                    "level " + key + " has " + appleGroups.getOrDefault(key, 0) + " apples " + ctx);
        }
    }

    @Test
    @DisplayName("the floor adds one group only to levels that drew none")
    void floorOnlyFillsEmptyLevels() {
        int gs = 3;
        int cpt = 20;
        for (long seed = 1; seed <= 200; seed++) {
            for (int lvl = 0; lvl <= 30; lvl++) {
                long[] r = CosmicAppleDrops.levelGroups(lvl, false, cpt, 1, 0, gs);
                int natural = 0;
                int floored = 0;
                for (long g = r[0]; g <= r[1]; g++) {
                    if (CosmicAppleDrops.groupGetsApple(seed, g)) natural++;
                    if (CosmicAppleDrops.isFloorGroup(seed, g, false, lvl, cpt, 1, 0, gs)) {
                        assertTrue(!CosmicAppleDrops.groupGetsApple(seed, g), "floor on a winning group");
                        floored++;
                    }
                }
                assertEquals(Math.max(0, CosmicAppleDrops.MIN_PER_LEVEL - natural), floored, "seed " + seed + " level " + lvl);
            }
        }
    }

    @Test
    @DisplayName("no floor while a test stamp pins the Diff-Level")
    void noFloorWhenPinned() {
        for (long g = 0; g < 500; g++) {
            int anchor = (int) g * 3;
            boolean natural = CosmicAppleDrops.groupGetsApple(9L, g);
            int slot = CosmicAppleDrops.slotFor(9L, anchor, 3, 5, false, 20, 1, 0);
            assertEquals(natural, slot >= 0, "group " + g);
        }
    }

    @Test
    @DisplayName("Level 0 spans the origin out to carriage 39 at the defaults")
    void levelZeroRange() {
        long[] pos = CosmicAppleDrops.levelGroups(0, false, 20, 1, 0, 3);
        long[] neg = CosmicAppleDrops.levelGroups(0, true, 20, 1, 0, 3);
        assertEquals(0, pos[0]);
        assertEquals(13, pos[1]);
        assertEquals(-13, neg[0]);
        assertEquals(-1, neg[1]);
        assertEquals(14, CosmicAppleDrops.levelGroups(1, false, 20, 1, 0, 3)[0]);
    }
}
