package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.config.SpheresProgressionConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for the spheres band progression layout ({@link SpheresSegments}). */
final class SpheresSegmentsTest {

    private static final double EPS = 1e-9;

    /** The shipped defaults: 750 OW sky, End sky after, Nether mix from 1250, End from 1750, boost over 2250–5250. */
    private static SpheresSegments defaults() {
        return SpheresSegments.of(750,
                SpheresProgressionConfig.DEFAULT_NETHER_MIX_START_BLOCKS,
                SpheresProgressionConfig.DEFAULT_END_MIX_START_BLOCKS,
                SpheresProgressionConfig.DEFAULT_STRUCTURE_BOOST_START_BLOCKS,
                SpheresProgressionConfig.DEFAULT_STRUCTURE_BOOST_END_BLOCKS,
                SpheresProgressionConfig.DEFAULT_STRUCTURE_CHANCE,
                SpheresProgressionConfig.DEFAULT_STRUCTURE_BOOST_MULTIPLIER,
                SpheresProgressionConfig.DEFAULT_STRUCTURE_BOOST_PEAK_MULTIPLIER,
                1, 1, 1);
    }

    private static Map<SphereSource, Integer> tally(SpheresSegments seg, long offset) {
        Map<SphereSource, Integer> out = new EnumMap<>(SphereSource.class);
        for (int i = 0; i < 3000; i++) {
            out.merge(seg.sourceAt(offset, i / 3000.0), 1, Integer::sum);
        }
        return out;
    }

    @Test
    @DisplayName("the defaults lay the 5250-block band out as 750 OW sky / 500 End / 500 +Nether / 500 +End / 3k structure boost")
    void defaultLayout() {
        SpheresSegments seg = defaults();
        assertEquals(750, seg.endSkyStart());
        assertEquals(1250, seg.netherMixStart());
        assertEquals(1750, seg.endMixStart());
        assertEquals(2250, seg.structureBoostStart());
        assertEquals(5250, seg.structureBoostEnd());
        assertEquals(DungeonTrainCommonConfig.DEFAULT_SPHERES_HOLD_BLOCKS, seg.structureBoostEnd(),
                "the structure boost runs to the band's end");
    }

    @Test
    @DisplayName("overworld only before the Nether mix; OW + Nether until the End mix; all three after")
    void sourcesBySegment() {
        SpheresSegments seg = defaults();
        assertEquals(Map.of(SphereSource.OVERWORLD, 3000), tally(seg, -1));      // entry fade
        assertEquals(Map.of(SphereSource.OVERWORLD, 3000), tally(seg, 1249));

        Map<SphereSource, Integer> two = tally(seg, 1250);
        assertEquals(1500, two.get(SphereSource.OVERWORLD));
        assertEquals(1500, two.get(SphereSource.NETHER));
        assertEquals(null, two.get(SphereSource.END));

        for (long offset : new long[] {1750, 3000, 5249}) {
            Map<SphereSource, Integer> three = tally(seg, offset);
            assertEquals(1000, three.get(SphereSource.OVERWORLD));
            assertEquals(1000, three.get(SphereSource.NETHER));
            assertEquals(1000, three.get(SphereSource.END));
        }
    }

    @Test
    @DisplayName("weights shape the mix; all-zero weights fall back to overworld")
    void weights() {
        SpheresSegments noOw = SpheresSegments.of(0, 0, 0, 0, 0, 0, 1, 1, 0, 1, 3);
        Map<SphereSource, Integer> t = tally(noOw, 10);
        assertEquals(null, t.get(SphereSource.OVERWORLD));
        assertEquals(750, t.get(SphereSource.NETHER));
        assertEquals(2250, t.get(SphereSource.END));

        SpheresSegments none = SpheresSegments.of(0, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0);
        assertEquals(SphereSource.OVERWORLD, none.sourceAt(10, 0.5));
    }

    @Test
    @DisplayName("structure boost ramps 5x -> 20x at the window's midpoint -> 5x, only inside the window, capped at 1")
    void structureBoost() {
        SpheresSegments seg = defaults();
        assertEquals(1.0, seg.structureMultiplierAt(2249), EPS);
        assertEquals(5.0, seg.structureMultiplierAt(2250), EPS);
        assertEquals(12.5, seg.structureMultiplierAt(3000), EPS);
        assertEquals(20.0, seg.structureMultiplierAt(3750), EPS);
        assertEquals(12.5, seg.structureMultiplierAt(4500), EPS);
        assertEquals(5.0 + 15.0 * 2 / 3000.0, seg.structureMultiplierAt(5249), EPS);
        assertEquals(1.0, seg.structureMultiplierAt(5250), EPS);
        for (long o = 2250; o < 5250; o++) {
            double m = seg.structureMultiplierAt(o);
            org.junit.jupiter.api.Assertions.assertTrue(m >= 5.0 - EPS && m <= 20.0 + EPS, "multiplier " + m + " at " + o);
        }

        assertEquals(0.0, seg.structureChanceAt(-1), EPS);
        assertEquals(0.08, seg.structureChanceAt(0), EPS);
        assertEquals(0.08, seg.structureChanceAt(2249), EPS);
        assertEquals(0.40, seg.structureChanceAt(2250), EPS);
        assertEquals(1.0, seg.structureChanceAt(3750), EPS);     // 0.08 × 20 = 1.6, capped
        assertEquals(0.08, seg.structureChanceAt(5250), EPS);
    }

    @Test
    @DisplayName("out-of-order offsets are clamped monotonic")
    void clamped() {
        SpheresSegments seg = SpheresSegments.of(-5, 800, 100, 900, 400, -1, -2, -3, -1, 2, 2);
        assertEquals(0, seg.endSkyStart());
        assertEquals(800, seg.endMixStart());        // never before the Nether mix
        assertEquals(900, seg.structureBoostEnd());  // never before the boost start
        assertEquals(0.0, seg.structureChance(), EPS);
        assertEquals(0.0, seg.structureBoostMultiplier(), EPS);
        assertEquals(0.0, seg.structureBoostPeakMultiplier(), EPS);
        assertEquals(0, seg.overworldWeight());
        assertTrue(seg.netherWeight() > 0);
    }
}
