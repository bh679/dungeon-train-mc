package games.brennan.dungeontrain.train;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link CarriageLayout#draw}, {@link LayoutWeights} and the {@link ShellPool} paths. */
final class CarriageLayoutTest {

    @Test
    @DisplayName("each layout fills about its weight's share of groups, the same every time")
    void shares() {
        LayoutWeights w = new LayoutWeights(8, 1, 1);
        Map<CarriageLayout, Integer> counts = new EnumMap<>(CarriageLayout.class);
        int n = 10000;
        for (long g = 0; g < n; g++) {
            CarriageLayout l = CarriageLayout.draw(5L, g, w);
            assertEquals(l, CarriageLayout.draw(5L, g, w), "seeded per group");
            counts.merge(l, 1, Integer::sum);
        }
        assertShare(counts.get(CarriageLayout.ROOMS), n, 0.80);
        assertShare(counts.get(CarriageLayout.HALVES), n, 0.10);
        assertShare(counts.get(CarriageLayout.GROUP), n, 0.10);
    }

    @Test
    @DisplayName("a zero weight never draws its layout, and all zero is rooms")
    void zeroes() {
        for (long g = 0; g < 500; g++) {
            assertEquals(CarriageLayout.ROOMS, CarriageLayout.draw(1L, g, new LayoutWeights(0, 0, 0)));
            assertTrue(CarriageLayout.draw(1L, g, new LayoutWeights(1, 0, 1)) != CarriageLayout.HALVES);
        }
    }

    @Test
    @DisplayName("weights clamp to their range and change one at a time")
    void weights() {
        LayoutWeights w = new LayoutWeights(-3, 5, 99999);
        assertEquals(0, w.rooms());
        assertEquals(LayoutWeights.MAX, w.group());
        assertEquals(new LayoutWeights(0, 7, LayoutWeights.MAX), w.with(CarriageLayout.HALVES, 7));
        assertEquals(5, w.weightOf(CarriageLayout.HALVES));
    }

    @Test
    @DisplayName("each pool's templates live in its own folder; Room is the folder itself")
    void poolPaths() {
        assertEquals("fancy", ShellPool.ROOM.pathOf("fancy"));
        assertEquals("half/twin", ShellPool.HALF.pathOf("twin"));
        assertEquals("group/cargo", ShellPool.GROUP.pathOf("cargo"));
        assertEquals(ContentsSize.FULL, ShellPool.GROUP.size());
        assertEquals(ShellPool.HALF, ShellPool.of(ContentsSize.HALF));
        assertEquals(ShellPool.GROUP, ShellPool.parse("full").orElseThrow());
    }

    @Test
    @DisplayName("an id's pool is what was recorded for it, Room otherwise")
    void poolOf() {
        ShellPool.set("zz_test_half", ShellPool.HALF);
        try {
            assertEquals("half/zz_test_half", ShellPool.path("zz_test_half"));
            assertEquals(ShellPool.ROOM, ShellPool.poolOf("zz_never_seen"));
        } finally {
            ShellPool.forget("zz_test_half");
        }
        assertEquals("zz_test_half", ShellPool.path("zz_test_half"));
    }

    private static void assertShare(Integer count, int n, double expected) {
        double share = (count == null ? 0 : count) / (double) n;
        assertTrue(Math.abs(share - expected) < 0.02, "expected ~" + expected + ", got " + share);
    }
}
