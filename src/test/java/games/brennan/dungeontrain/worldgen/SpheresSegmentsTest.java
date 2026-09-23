package games.brennan.dungeontrain.worldgen;

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

    /** The shipped defaults: 3000 OW sky, End sky to 12000, Nether mix from 5000, End from 6000, ×5 over 9000–12000. */
    private static SpheresSegments defaults() {
        return SpheresSegments.of(3000,
                SpheresProgressionConfig.DEFAULT_NETHER_MIX_START_BLOCKS,
                SpheresProgressionConfig.DEFAULT_END_MIX_START_BLOCKS,
                SpheresProgressionConfig.DEFAULT_STRUCTURE_BOOST_START_BLOCKS,
                SpheresProgressionConfig.DEFAULT_STRUCTURE_BOOST_END_BLOCKS,
                SpheresProgressionConfig.DEFAULT_NETHER_SKY_START_BLOCKS,
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
    @DisplayName("the defaults lay the band out as 3k OW sky / 2k End / 1k +Nether / 3k +End / 3k ×5 structures / Nether sky")
    void defaultLayout() {
        SpheresSegments seg = defaults();
        assertEquals(3000, seg.endSkyStart());
        assertEquals(5000, seg.netherMixStart());
        assertEquals(6000, seg.endMixStart());
        assertEquals(9000, seg.structureBoostStart());
        assertEquals(12000, seg.structureBoostEnd());
        assertEquals(12000, seg.netherSkyStart());
    }

    @Test
    @DisplayName("overworld only before the Nether mix; OW + Nether until the End mix; all three after")
    void sourcesBySegment() {
        SpheresSegments seg = defaults();
        assertEquals(Map.of(SphereSource.OVERWORLD, 3000), tally(seg, -1));      // entry fade
        assertEquals(Map.of(SphereSource.OVERWORLD, 3000), tally(seg, 4999));

        Map<SphereSource, Integer> two = tally(seg, 5000);
        assertEquals(1500, two.get(SphereSource.OVERWORLD));
        assertEquals(1500, two.get(SphereSource.NETHER));
        assertEquals(null, two.get(SphereSource.END));

        for (long offset : new long[] {6000, 10_000, 13_000}) {
            Map<SphereSource, Integer> three = tally(seg, offset);
            assertEquals(1000, three.get(SphereSource.OVERWORLD));
            assertEquals(1000, three.get(SphereSource.NETHER));
            assertEquals(1000, three.get(SphereSource.END));
        }
    }

    @Test
    @DisplayName("weights shape the mix; all-zero weights fall back to overworld")
    void weights() {
        SpheresSegments noOw = SpheresSegments.of(0, 0, 0, 0, 0, 0, 0, 1, 1, 0, 1, 3);
        Map<SphereSource, Integer> t = tally(noOw, 10);
        assertEquals(null, t.get(SphereSource.OVERWORLD));
        assertEquals(750, t.get(SphereSource.NETHER));
        assertEquals(2250, t.get(SphereSource.END));

        SpheresSegments none = SpheresSegments.of(0, 0, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0);
        assertEquals(SphereSource.OVERWORLD, none.sourceAt(10, 0.5));
    }

    @Test
    @DisplayName("structure boost ramps 5x -> 20x at the window's midpoint -> 5x, only inside the window, capped at 1")
    void structureBoost() {
        SpheresSegments seg = defaults();
        assertEquals(1.0, seg.structureMultiplierAt(8999), EPS);
        assertEquals(5.0, seg.structureMultiplierAt(9000), EPS);
        assertEquals(12.5, seg.structureMultiplierAt(9750), EPS);
        assertEquals(20.0, seg.structureMultiplierAt(10_500), EPS);
        assertEquals(12.5, seg.structureMultiplierAt(11_250), EPS);
        assertEquals(5.0 + 15.0 * 2 / 3000.0, seg.structureMultiplierAt(11_999), EPS);
        assertEquals(1.0, seg.structureMultiplierAt(12_000), EPS);
        for (long o = 9000; o < 12_000; o++) {
            double m = seg.structureMultiplierAt(o);
            org.junit.jupiter.api.Assertions.assertTrue(m >= 5.0 - EPS && m <= 20.0 + EPS, "multiplier " + m + " at " + o);
        }

        assertEquals(0.0, seg.structureChanceAt(-1), EPS);
        assertEquals(0.08, seg.structureChanceAt(0), EPS);
        assertEquals(0.08, seg.structureChanceAt(8999), EPS);
        assertEquals(0.40, seg.structureChanceAt(9000), EPS);
        assertEquals(1.0, seg.structureChanceAt(10_500), EPS);     // 0.08 × 20 = 1.6, capped
        assertEquals(0.08, seg.structureChanceAt(12_000), EPS);
    }

    @Test
    @DisplayName("out-of-order offsets are clamped monotonic")
    void clamped() {
        SpheresSegments seg = SpheresSegments.of(-5, 800, 100, 900, 400, 10, -1, -2, -3, -1, 2, 2);
        assertEquals(0, seg.endSkyStart());
        assertEquals(800, seg.endMixStart());        // never before the Nether mix
        assertEquals(900, seg.structureBoostEnd());  // never before the boost start
        assertEquals(10, seg.netherSkyStart());
        assertEquals(0.0, seg.structureChance(), EPS);
        assertEquals(0.0, seg.structureBoostMultiplier(), EPS);
        assertEquals(0.0, seg.structureBoostPeakMultiplier(), EPS);
        assertEquals(0, seg.overworldWeight());
        assertTrue(seg.netherWeight() > 0);
    }
}
