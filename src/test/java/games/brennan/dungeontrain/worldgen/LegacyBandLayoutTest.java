package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind;
import games.brennan.dungeontrain.worldgen.legacy.LegacySpan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure layout tests for the legacy bands appended to the {@link WorldGenCycle} after the stacks band. */
final class LegacyBandLayoutTest {

    private static final long START = 1000L;
    private static final LegacySpan BETA = new LegacySpan(LegacyBandKind.BETA, 100, 50, 200);

    private static WorldGenCycle cycle(LegacySpan... legacy) {
        return new WorldGenCycle(START, 300, 40, new int[] {1, 5, 20}, 0, 60, 50, 200, 100, 40, 200,
                50, 200, 150, 80, 400, 60, 120, 0.3, 0.4, 500, 70, 90, 300, 40, 60, 0.1,
                legacy, 0);
    }

    private static WorldGenCycle preLegacy() {
        return new WorldGenCycle(START, 300, 40, new int[] {1, 5, 20}, 0, 60, 50, 200, 100, 40, 200,
                50, 200, 150, 80, 400, 60, 120, 0.3, 0.4, 500, 70, 90, 300, 40, 60, 0.1, 0);
    }

    /** World-X {@code local} blocks into the legacy tail of the first cycle. */
    private static int x(long local) {
        return (int) (START + preLegacy().period() + local);
    }

    @Test
    @DisplayName("no legacy spans (or only disabled ones) keep the period byte-identical")
    void disabledKeepsPeriod() {
        long base = preLegacy().period();
        assertEquals(base, cycle().period());
        assertEquals(base, cycle(new LegacySpan(LegacyBandKind.BETA, 100, 50, 0)).period());
    }

    @Test
    @DisplayName("an enabled span adds lead gap + two fades + hold to the period")
    void enabledAddsSlot() {
        assertEquals(preLegacy().period() + 100 + 2 * 50 + 200, cycle(BETA).period());
    }

    @Test
    @DisplayName("ramp: 0 in the lead gap, rising across the entry fade, 1 in the core, falling across the exit")
    void rampShape() {
        WorldGenCycle c = cycle(BETA);
        assertNull(c.legacyAt(x(0)));
        assertNull(c.legacyAt(x(99)));
        WorldGenCycle.LegacyHit entry = c.legacyAt(x(100));
        assertNotNull(entry);
        assertEquals(LegacyBandKind.BETA, entry.kind());
        assertTrue(entry.ramp() > 0.0 && entry.ramp() < 0.1);
        assertTrue(c.legacyAt(x(140)).ramp() > c.legacyAt(x(110)).ramp());
        assertEquals(1.0, c.legacyAt(x(150)).ramp());
        assertEquals(1.0, c.legacyAt(x(349)).ramp());
        assertTrue(c.legacyAt(x(350)).ramp() < 1.0);
        assertTrue(c.legacyAt(x(399)).ramp() > 0.0);
        assertNull(c.legacyAt(x(400)));             // next cycle's first overworld gap
    }

    @Test
    @DisplayName("core membership excludes fades; approach runs from lead gap to exit-fade end")
    void coreAndApproach() {
        WorldGenCycle c = cycle(BETA);
        assertFalse(c.isInLegacyBand(LegacyBandKind.BETA, x(149)));
        assertTrue(c.isInLegacyBand(LegacyBandKind.BETA, x(150)));
        assertTrue(c.isInLegacyBand(LegacyBandKind.BETA, x(349)));
        assertFalse(c.isInLegacyBand(LegacyBandKind.BETA, x(350)));
        assertFalse(c.isInLegacyApproachOrBand(LegacyBandKind.BETA, x(-1)));
        assertTrue(c.isInLegacyApproachOrBand(LegacyBandKind.BETA, x(0)));
        assertTrue(c.isInLegacyApproachOrBand(LegacyBandKind.BETA, x(399)));
        assertFalse(c.isInLegacyApproachOrBand(LegacyBandKind.BETA, x(400)));
    }

    @Test
    @DisplayName("the layout repeats every period")
    void repeats() {
        WorldGenCycle c = cycle(BETA);
        long p = c.period();
        assertTrue(c.isInLegacyBand(LegacyBandKind.BETA, (int) (x(200) + p)));
        assertEquals(c.legacyAt(x(120)).ramp(), c.legacyAt((int) (x(120) + 3 * p)).ramp());
    }

    @Test
    @DisplayName("a second span follows the first; core progress runs 0 → 1 across the core")
    void secondSpanAndProgress() {
        LegacySpan alpha = new LegacySpan(LegacyBandKind.ALPHA, 100, 50, 200);
        WorldGenCycle c = cycle(BETA, alpha);
        assertEquals(preLegacy().period() + 2 * (100 + 2 * 50 + 200), c.period());
        // Alpha's slot starts where Beta's ends (local 400).
        assertNull(c.legacyAt(x(499)));
        assertEquals(LegacyBandKind.ALPHA, c.legacyAt(x(500)).kind());
        assertTrue(c.isInLegacyBand(LegacyBandKind.ALPHA, x(550)));
        assertEquals(0.0, c.legacyCoreProgress(LegacyBandKind.ALPHA, x(550)));
        assertEquals(0.5, c.legacyCoreProgress(LegacyBandKind.ALPHA, x(650)));
        assertTrue(c.legacyCoreProgress(LegacyBandKind.ALPHA, x(760)) > 1.0);     // exit fade
        assertTrue(c.legacyCoreProgress(LegacyBandKind.ALPHA, x(410)) < 0.0);     // lead gap
        assertTrue(Double.isNaN(c.legacyCoreProgress(LegacyBandKind.ALPHA, x(399)))); // Beta's slot
        assertTrue(Double.isNaN(cycle(BETA).legacyCoreProgress(LegacyBandKind.ALPHA, x(550))));
    }

    @Test
    @DisplayName("zero fade is a hard edge: ramp jumps straight to 1")
    void hardEdge() {
        WorldGenCycle c = cycle(new LegacySpan(LegacyBandKind.BETA, 100, 0, 200));
        assertNull(c.legacyAt(x(99)));
        assertEquals(1.0, c.legacyAt(x(100)).ramp());
        assertEquals(1.0, c.legacyAt(x(299)).ramp());
        assertNull(c.legacyAt(x(300)));
    }
}
