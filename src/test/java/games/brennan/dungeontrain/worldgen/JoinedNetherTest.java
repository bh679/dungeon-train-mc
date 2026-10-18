package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.worldgen.CycleLayout.Style;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Back-to-back Nether slots (BetterNether → Deeper Dark) are one range: the mountains stay at full height
 * between the two cores, the whole stretch wears the deep-dark caves and deepslate, and the second slot's
 * exit still hands Lost City its lead-in.
 */
final class JoinedNetherTest {

    private static final long START = 10_000L;
    private static final String ORDER =
            "ow:1000, nether:better:4500, nether:otherside:3000, legacy:wwoo:lost_city=3000, ow:1000";
    private static final CycleLayout LAYOUT =
            CycleLayout.parse(ORDER, CycleLayoutTest.FADES, CycleLayoutTest.eraDefaults(), t -> true, w -> {});
    private static final WorldGenCycle C = new WorldGenCycle(START, 10_000, 40, new int[] {1, 2, 4, 8, 15}, 32, 0, 300,
            5000, 120, 500, 5000, 600, 5000, 600, 10_000, 8000, 1500, 5000, 0.3, 0.4, 6550, 750, 5000, 8000, 1500,
            10_000, 0.08, CycleLayoutTest.eraDefaults(), LAYOUT, 0);

    private static final int RISE = 232;
    private static final int CORE_FADE = 300;

    private static int x(long u) {
        return (int) (START + u);
    }

    @Test
    @DisplayName("'otherside' parses on a Nether slot and is dropped (with a warning) anywhere else")
    void parsesOtherside() {
        assertEquals(Style.OTHERSIDE, LAYOUT.slot(2).style());
        assertEquals(CycleLayout.Type.NETHER, LAYOUT.slot(2).type());
        assertEquals(Style.OTHERSIDE,
                CycleLayout.parse("nether:deeper_dark:3000", CycleLayoutTest.FADES, CycleLayoutTest.eraDefaults(),
                        t -> true, w -> {}).slot(0).style());
        List<String> warnings = new ArrayList<>();
        CycleLayout bad = CycleLayout.parse("ow:otherside:1000", CycleLayoutTest.FADES, CycleLayoutTest.eraDefaults(),
                t -> true, warnings::add);
        assertEquals(Style.VANILLA, bad.slot(0).style());
        assertEquals(1, warnings.size());
    }

    @Test
    @DisplayName("joining flags: only the touching sides of the two Nether slots")
    void joinFlags() {
        assertFalse(LAYOUT.netherJoinedBefore(1));
        assertTrue(LAYOUT.netherJoinedAfter(1));
        assertTrue(LAYOUT.netherJoinedBefore(2));
        assertFalse(LAYOUT.netherJoinedAfter(2));
        assertEquals(1, LAYOUT.netherGroupFirst(2));
        // slot lengths are unchanged by joining
        assertEquals(NetherTransition.bandLength(RISE, 0, CORE_FADE, 3000), LAYOUT.length(2));
    }

    @Test
    @DisplayName("between the two cores the range never dips: full height, mega multiplier, no beach, no feather")
    void continuousRange() {
        long bnCoreEnd = LAYOUT.start(1) + RISE + CORE_FADE + 4500;
        long ddCoreStart = LAYOUT.netherCoreStart(2);
        assertEquals(LAYOUT.start(2) + RISE + CORE_FADE, ddCoreStart);
        for (long u = bnCoreEnd; u < ddCoreStart; u++) {
            int wx = x(u);
            assertEquals(1.0, C.netherHeightRamp(wx), "height at " + u);
            assertEquals(15.0, C.netherMountainMultiplier(wx), 1e-9, "multiplier at " + u);
            assertEquals(1.0, C.netherMountainFeather(wx), "feather at " + u);
            assertFalse(C.isNetherBeachStage(wx), "beach at " + u);
            assertTrue(C.netherDeepDarkSide(wx) || C.isNetherCore(wx), "deep-dark side at " + u);
            assertTrue(C.netherDeepslateGap(wx) == 0, "deepslate at " + u);
        }
        // the core crossfades still happen on both sides of the seam
        assertTrue(C.netherRamp(x(bnCoreEnd + CORE_FADE / 2)) > 0.0);
        assertEquals(0.0, C.netherRamp(x(LAYOUT.start(2))));
        assertTrue(C.netherRamp(x(ddCoreStart - CORE_FADE / 2)) > 0.0);
    }

    @Test
    @DisplayName("the range's outer ends still rise from and fall to the overworld")
    void outerEdges() {
        assertTrue(C.isNetherBeachStage(x(LAYOUT.start(1) + 1)));
        assertTrue(C.netherHeightRamp(x(LAYOUT.start(1) + 10)) < 1.0);
        assertFalse(C.netherDeepDarkSide(x(LAYOUT.start(1) + RISE)));      // BetterNether's way in: lush/dripstone
        long ddEnd = LAYOUT.start(2) + LAYOUT.length(2);
        assertTrue(C.netherHeightRamp(x(ddEnd - 10)) < 1.0);
        assertTrue(C.netherDeepDarkSide(x(ddEnd - 10)));                   // Deeper Dark's way out: deep dark
        // the joined slot has no beach of its own — ocean detection reads the range's first slot
        assertEquals(x(LAYOUT.start(1)), C.netherBandEntranceX(x(LAYOUT.start(2) + 5)));
    }

    @Test
    @DisplayName("Lost City still starts on the Deeper Dark's exit mountains")
    void lostCityLeadIn() {
        assertEquals((long) RISE, LAYOUT.legacyLeadIn(3));
    }
}
