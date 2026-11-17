package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link WorldGenCycle#bandPosition} and its inverse {@link WorldGenCycle#worldXOf}: a nap's band and depth.
 * Ahead of spawn the band follows from X alone; behind it, it moves with the reverse slide.
 */
final class WorldGenCycleBandPositionTest {

    private static final long START = 10_000L;
    private static final CycleLayout LAYOUT = CycleLayoutTest.shipped();
    private static final long P = LAYOUT.period();
    private static final long BUFFER = LAYOUT.length(0);

    private static final WorldGenCycle C = new WorldGenCycle(START, 10_000, 40, new int[] {1, 2, 4, 8, 15}, 32, 0, 300, 5000,
            120, 500, 5000, 600, 5000, 600, 10_000, 8000, 1500, 5000, 0.3, 0.4, 6550, 750, 5000, 8000, 1500, 10_000, 0.08,
            CycleLayoutTest.eraDefaults(), LAYOUT, 0);

    @AfterEach
    void resetSlide() {
        WorldGenCycle.setReverseSlide(0L);
    }

    @Test
    @DisplayName("forwards: X -> band position -> X is the identity, across runs")
    void forwardRoundTrip() {
        for (long x = START; x < START + 3 * P; x += 997) {
            WorldGenCycle.BandPosition p = C.bandPosition(x);
            assertNotNull(p);
            assertEquals(false, p.reversed());
            assertWithinBlock(x, C.worldXOf(p), p.run());
        }
    }

    @Test
    @DisplayName("backwards: X -> band position -> X is the identity under the same slide")
    void reversedRoundTrip() {
        for (long slide : new long[] {0L, 640L, 12_800L}) {
            WorldGenCycle.setReverseSlide(slide);
            for (long x = START - BUFFER - slide - 1; x > START - BUFFER - slide - 3 * P; x -= 991) {
                WorldGenCycle.BandPosition p = C.bandPosition(x);
                assertNotNull(p, "x=" + x);
                assertEquals(true, p.reversed());
                long back = C.worldXOf(p);
                // Doubled runs cover 2^k world blocks per base block; the inverse lands on that block's start.
                assertWithinBlock(x, back, p.run());
                assertEquals(p, C.bandPosition(back));
            }
        }
    }

    /** Doubled runs cover {@code 2^run} world blocks per base block; the inverse lands on that block's first column. */
    private static void assertWithinBlock(long x, long back, int run) {
        long d = x - back;
        org.junit.jupiter.api.Assertions.assertTrue(d >= 0 && d < (1L << run), "x=" + x + " back=" + back + " run=" + run);
    }

    @Test
    @DisplayName("backwards the same X is another band once the slide differs; the position restores it")
    void slideMovesBandsBehindSpawn() {
        WorldGenCycle.setReverseSlide(6400L);
        long x = START - BUFFER - 6400L - P / 3;
        WorldGenCycle.BandPosition napped = C.bandPosition(x);
        WorldGenCycle.setReverseSlide(0L);
        assertNotEquals(napped, C.bandPosition(x), "X alone is not enough behind spawn");
        WorldGenCycle.setReverseSlide(6400L);
        assertEquals(x, C.worldXOf(napped));
    }

    @Test
    @DisplayName("the buffer has no position; a position that doesn't fit the layout has no X")
    void outOfLayout() {
        assertNull(C.bandPosition(START - 1));
        assertEquals(Long.MIN_VALUE, C.worldXOf(new WorldGenCycle.BandPosition(false, 0, LAYOUT.count(), 0)));
        assertEquals(Long.MIN_VALUE, C.worldXOf(new WorldGenCycle.BandPosition(false, 0, 0, LAYOUT.length(0))));
        assertEquals(Long.MIN_VALUE, C.worldXOf(new WorldGenCycle.BandPosition(true, -1, 0, 0)));
        assertEquals(Long.MIN_VALUE, C.worldXOf(null));
    }
}
