package games.brennan.dungeontrain.spawn;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-logic unit tests for {@link LatePhantomRate}: the distance curve, the day factor and the sky rule. */
final class LatePhantomRateTest {

    private static final long EXIT = 101_762L;
    private static final double EPS = 1e-9;

    @Test
    @DisplayName("nothing at or before the second cycle starts")
    void noneBeforeExit() {
        assertEquals(-1L, LatePhantomRate.steps(EXIT, EXIT));
        assertEquals(-1L, LatePhantomRate.steps(90_000, EXIT));
        assertEquals(0.0, LatePhantomRate.chance(-1L, true), EPS);
        assertEquals(0, LatePhantomRate.groupSize(-1L));
    }

    @Test
    @DisplayName("curve matches the approved table")
    void curveTable() {
        assertRow(1, 0.15, 1);
        assertRow(5_000, 0.275, 1);
        assertRow(10_000, 0.40, 2);
        assertRow(20_000, 0.65, 3);
        assertRow(30_000, 0.90, 4);
        assertRow(100_000, 0.90, 4);
    }

    @Test
    @DisplayName("daytime runs at a quarter of the night chance")
    void dayQuarter() {
        long steps = LatePhantomRate.steps(EXIT + 10_000, EXIT);
        assertEquals(0.10, LatePhantomRate.chance(steps, false), EPS);
        assertEquals(0.225, LatePhantomRate.chance(1_000L, false), EPS);
    }

    @Test
    @DisplayName("End or Nether sky box blocks spawns at half intensity")
    void skyRule() {
        assertTrue(LatePhantomRate.overworldSky(0.0, 0.0));
        assertTrue(LatePhantomRate.overworldSky(0.49, 0.49));
        assertFalse(LatePhantomRate.overworldSky(0.5, 0.0));
        assertFalse(LatePhantomRate.overworldSky(0.0, 1.0));
    }

    @Test
    @DisplayName("potion effects start where the spawn rate tops out, one level per step")
    void effectLevel() {
        assertEquals(150L, LatePhantomRate.STEPS_TO_MAX);
        assertEquals(0, LatePhantomRate.effectLevel(0L));
        assertEquals(0, LatePhantomRate.effectLevel(150L));
        assertEquals(1, LatePhantomRate.effectLevel(151L));
        assertEquals(40, LatePhantomRate.effectLevel(LatePhantomRate.steps(EXIT + 38_000, EXIT)));
        assertEquals(0.90, LatePhantomRate.chance(LatePhantomRate.STEPS_TO_MAX, true), EPS);
    }

    private static void assertRow(int blocksPast, double nightChance, int group) {
        long steps = LatePhantomRate.steps(EXIT + blocksPast, EXIT);
        assertEquals(nightChance, LatePhantomRate.chance(steps, true), EPS, "chance at " + blocksPast);
        assertEquals(group, LatePhantomRate.groupSize(steps), "group at " + blocksPast);
    }
}
