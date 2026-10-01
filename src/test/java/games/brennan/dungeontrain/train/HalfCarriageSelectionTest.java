package games.brennan.dungeontrain.train;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure half of {@link HalfCarriageSelection} and {@link HalfJoinMode}: fit, weighted draw, layout. */
final class HalfCarriageSelectionTest {

    private static final CarriageDims NINE = new CarriageDims(9, 7, 7);
    private static final CarriageDims EIGHT = new CarriageDims(8, 7, 7);

    @Test
    @DisplayName("two Half boxes fit a three-carriage group with a 1–2 block gap, not a pair or a four")
    void fits() {
        assertTrue(HalfCarriageSelection.fits(NINE, 3));
        assertTrue(HalfCarriageSelection.fits(EIGHT, 3));
        assertFalse(HalfCarriageSelection.fits(NINE, 2), "13 + 13 overruns an 18-long group");
        assertFalse(HalfCarriageSelection.fits(NINE, 4), "a 10-block gap is two rooms apart, not a join");
        assertFalse(HalfCarriageSelection.fits(NINE, 1));
    }

    @Test
    @DisplayName("no Half template with a weight draws nothing")
    void noWeightDrawsNothing() {
        for (long g = 0; g < 200; g++) {
            assertNull(HalfCarriageSelection.draw(42L, g, 0, List.of(), id -> 5));
            assertNull(HalfCarriageSelection.draw(42L, g, 1, List.of("halfy"), id -> 0));
        }
    }

    @Test
    @DisplayName("the two halves are separate picks — they differ in some groups, and repeat exactly")
    void halvesAreIndependent() {
        Map<String, Integer> w = Map.of("a", 1, "b", 1);
        List<String> ids = List.of("a", "b");
        int differ = 0;
        for (long g = 0; g < 400; g++) {
            String first = HalfCarriageSelection.draw(7L, g, 0, ids, w::get);
            String second = HalfCarriageSelection.draw(7L, g, 1, ids, w::get);
            assertEquals(first, HalfCarriageSelection.draw(7L, g, 0, ids, w::get), "seeded per group");
            if (!first.equals(second)) differ++;
        }
        assertTrue(differ > 120 && differ < 280, "two even halves should differ about half the time, got " + differ);
    }

    @Test
    @DisplayName("a half's template follows its weight")
    void weightedHalf() {
        Map<String, Integer> w = Map.of("a", 3, "b", 1);
        int a = 0;
        for (long g = 0; g < 4000; g++) {
            if ("a".equals(HalfCarriageSelection.draw(9L, g, 0, List.of("a", "b"), w::get))) a++;
        }
        double share = a / 4000.0;
        assertTrue(share > 0.70 && share < 0.80, "expected ~75%, got " + share);
    }

    @Test
    @DisplayName("wall and bridge leave the gap between the halves; short butts them and shortens the group")
    void layoutPerMode() {
        // Nine-long carriages: a Half box is 13, a group 27 — one block of gap.
        assertEquals(1, HalfJoinMode.gap(27, 13));
        assertEquals(14, HalfJoinMode.WALL.secondHalfOffset(27, 13));
        assertEquals(14, HalfJoinMode.BRIDGE.secondHalfOffset(27, 13));
        assertEquals(13, HalfJoinMode.SHORT.secondHalfOffset(27, 13));
        assertEquals(0, HalfJoinMode.WALL.shortening(27, 13));
        assertEquals(1, HalfJoinMode.SHORT.shortening(27, 13));
        // Eight-long: 11 + 11 of 24 — two blocks.
        assertEquals(2, HalfJoinMode.gap(24, 11));
        assertEquals(13, HalfJoinMode.WALL.secondHalfOffset(24, 11));
        assertEquals(2, HalfJoinMode.SHORT.shortening(24, 11));
    }

    @Test
    @DisplayName("random resolves per group to each of the three concrete modes, deterministically")
    void randomResolves() {
        java.util.Set<HalfJoinMode> seen = java.util.EnumSet.noneOf(HalfJoinMode.class);
        for (long g = 0; g < 200; g++) {
            HalfJoinMode m = HalfJoinMode.RANDOM.resolve(99L, g);
            assertEquals(m, HalfJoinMode.RANDOM.resolve(99L, g));
            assertFalse(m == HalfJoinMode.RANDOM);
            seen.add(m);
        }
        assertEquals(java.util.EnumSet.of(HalfJoinMode.WALL, HalfJoinMode.BRIDGE, HalfJoinMode.SHORT), seen);
        assertEquals(HalfJoinMode.SHORT, HalfJoinMode.SHORT.resolve(99L, 3));
    }

    @Test
    @DisplayName("join modes parse from their keys, case-insensitively")
    void parse() {
        assertEquals(HalfJoinMode.BRIDGE, HalfJoinMode.parse(" Bridge ").orElseThrow());
        assertTrue(HalfJoinMode.parse("concrete").isEmpty());
        assertTrue(HalfJoinMode.parse(null).isEmpty());
    }
}
