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
    @DisplayName("no Half shell with a weight never makes a Half pair")
    void noWeightNeverHits() {
        for (long g = 0; g < 500; g++) {
            assertNull(HalfCarriageSelection.draw(42L, g, 100, List.of(), id -> 5));
            assertNull(HalfCarriageSelection.draw(42L, g, 100, List.of("halfy"), id -> 0));
        }
    }

    @Test
    @DisplayName("a Half shell takes about its weight's share of groups, the same each time")
    void weightedShare() {
        Map<String, Integer> w = Map.of("halfy", 25);
        int hits = 0;
        int n = 4000;
        for (long g = 0; g < n; g++) {
            String a = HalfCarriageSelection.draw(7L, g, 75, List.of("halfy"), w::get);
            String b = HalfCarriageSelection.draw(7L, g, 75, List.of("halfy"), w::get);
            assertEquals(a, b, "the draw is seeded — a re-stamped group gets the same answer");
            if (a != null) hits++;
        }
        double share = hits / (double) n;
        assertTrue(share > 0.21 && share < 0.29, "expected ~25%, got " + share);
    }

    @Test
    @DisplayName("with no ordinary pool at all, every group is a Half pair")
    void emptyRoomPoolAlwaysHalf() {
        for (long g = 0; g < 100; g++) {
            assertEquals("halfy", HalfCarriageSelection.draw(1L, g, 0, List.of("halfy"), id -> 1));
        }
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
