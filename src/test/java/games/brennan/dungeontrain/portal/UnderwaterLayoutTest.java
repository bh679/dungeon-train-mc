package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The underwater room's sea rolls: floor 1-16 off the bottom, half flooded to the roof, else a 2-8 gap. */
class UnderwaterLayoutTest {

    private static final long SEED = 0x5EED_CAFEL;
    private static final int PAIRS = 10_000;

    @Test
    @DisplayName("floor always lands 1 to 16 rows off the bottom, and every row is reached")
    void floorRange() {
        Set<Integer> seen = new HashSet<>();
        for (int pair = 0; pair < PAIRS; pair++) {
            int floor = UnderwaterLayout.roll(SEED, pair, 0).floorRow();
            assertTrue(floor >= 1 && floor <= 16, "floor " + floor);
            seen.add(floor);
        }
        assertEquals(16, seen.size(), "every floor row should roll: " + seen);
    }

    @Test
    @DisplayName("gap is 0 about half the time, else 2 to 8 with every value reached")
    void gapRangeAndSplit() {
        int flooded = 0;
        Set<Integer> gaps = new HashSet<>();
        for (int pair = 0; pair < PAIRS; pair++) {
            int gap = UnderwaterLayout.roll(SEED, pair, 0).waterGap();
            if (gap == 0) {
                flooded++;
            } else {
                assertTrue(gap >= 2 && gap <= 8, "gap " + gap);
                gaps.add(gap);
            }
        }
        double share = flooded / (double) PAIRS;
        assertTrue(share > 0.47 && share < 0.53, "flooded share " + share);
        assertEquals(7, gaps.size(), "every gap 2..8 should roll: " + gaps);
    }

    @Test
    @DisplayName("deterministic per pair, and a re-roll moves it")
    void deterministicAndRerolls() {
        assertEquals(UnderwaterLayout.roll(SEED, 42, 0), UnderwaterLayout.roll(SEED, 42, 0));
        int changed = 0;
        for (int pair = 0; pair < 200; pair++) {
            if (!UnderwaterLayout.roll(SEED, pair, 0).equals(UnderwaterLayout.roll(SEED, pair, 1))) changed++;
        }
        assertTrue(changed > 150, "a re-roll should usually give a different sea: " + changed);
    }

    @Test
    @DisplayName("the water always stands at least 7 deep over the floor in a 32-tall box")
    void alwaysWater() {
        for (int pair = 0; pair < PAIRS; pair++) {
            UnderwaterLayout sea = UnderwaterLayout.roll(SEED, pair, 0);
            int top = sea.topWaterRow(PortalChunkTerrain.HEIGHT);
            assertTrue(top - sea.floorRow() + 1 >= 7, sea + " top " + top);
        }
        assertEquals(31, new UnderwaterLayout(5, 0).topWaterRow(32));
        assertEquals(23, new UnderwaterLayout(5, 8).topWaterRow(32));
    }
}
