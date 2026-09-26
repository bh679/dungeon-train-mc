package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.worldgen.CycleLayout.Style;
import games.brennan.dungeontrain.worldgen.CycleLayout.Type;
import games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind;
import games.brennan.dungeontrain.worldgen.legacy.LegacySpan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for the ordered slot layout: parsing, the shipped default's geometry, and the doubling maths. */
final class CycleLayoutTest {

    /** The shipped fade defaults after the Nether halving: rise 232 (32 + 5 × 40), core fade 300. */
    static final CycleLayout.Fades FADES = new CycleLayout.Fades(232, 0, 300, 120, 500, 600, 600, 10_000, 1500, 1500, 1500, 480);

    /** Every era enabled at some classic length — the bare {@code legacy} token's defaults. */
    static LegacySpan[] eraDefaults() {
        List<LegacySpan> out = new ArrayList<>();
        for (LegacyBandKind k : LegacyBandKind.values()) out.add(new LegacySpan(k, 3000, 480, 6000));
        return out.toArray(new LegacySpan[0]);
    }

    static CycleLayout shipped() {
        List<String> warnings = new ArrayList<>();
        CycleLayout l = CycleLayout.parse(CycleLayout.DEFAULT_ORDER, FADES, eraDefaults(), t -> true, warnings::add);
        assertTrue(warnings.isEmpty(), warnings.toString());
        return l;
    }

    @Test
    @DisplayName("the shipped order parses to 16 slots and the planned run-1 length")
    void shippedGeometry() {
        CycleLayout l = shipped();
        assertEquals(16, l.count());
        // Lap 1: 2750 + (232+300+3000+300+232) + 3000 + (740+3000+740) + (600+2500+600+6000+600) = 24,594
        // Lap 2: 8000 + 9064 + 8000 + 9480 + (1500+15000) + 5000 = 56,044
        // Lap 3: legacy (480·12 + 5000 + 3500 + 4320 + 4000 + 5000 + 2000·4 + 1000 + 200 = 36,780)
        //        + 2000 + 6500 + 5000 + 6500 = 56,780
        assertEquals(137_418L, l.period());
        assertEquals(2, l.typeCount(Type.NETHER));
        assertEquals(2, l.typeCount(Type.END));
        assertEquals(1, l.typeCount(Type.LEGACY_RUN));
        assertEquals(7, l.typeCount(Type.OVERWORLD));
        // Lap-1 starts
        assertEquals(0L, l.start(0));
        assertEquals(2750L, l.start(1));                        // Nether
        assertEquals(2750L + 4064L, l.start(2));                // OW after the Nether
        assertEquals(9814L, l.start(3));                        // End
        assertEquals(9814L + 4480L, l.start(4));                // Upside-down
        assertEquals(24_594L, l.start(5));                      // OW·WWOO opens lap 2
        assertEquals(Style.WWOO, l.slot(5).style());
        assertEquals(Style.BETTER, l.slot(6).style());
        assertEquals(Style.BOP, l.slot(7).style());
        assertEquals(Style.BETTER, l.slot(8).style());
        assertEquals(Type.SPHERES, l.slot(9).type());
        assertEquals(Type.LEGACY_RUN, l.slot(11).type());
        assertEquals(24_594L + 56_044L, l.start(11));
        assertEquals(Type.STACKS, l.slot(15).type());
        assertEquals(1, l.occurrence(6));                       // the BetterNether slot is Nether occurrence 1
        assertEquals(0, l.occurrence(1));
    }

    @Test
    @DisplayName("the legacy run lays its eras back to back with one shared crossfade")
    void legacyRun() {
        CycleLayout l = shipped();
        LegacySpan[] eras = l.eras();
        assertEquals(11, eras.length);
        // The shipped run order is the order the token writes, not declaration order.
        assertArrayEquals(new LegacyBandKind[] {LegacyBandKind.AMPLIFIED, LegacyBandKind.BETA, LegacyBandKind.FAR_LANDS,
                        LegacyBandKind.CAVES_OF_CHAOS, LegacyBandKind.SKYLANDS, LegacyBandKind.FLOATING, LegacyBandKind.ALPHA,
                        LegacyBandKind.INFDEV, LegacyBandKind.CLASSIC, LegacyBandKind.SUPERFLAT, LegacyBandKind.VOID},
                java.util.Arrays.stream(eras).map(LegacySpan::kind).toArray(LegacyBandKind[]::new));
        assertEquals(5000, eras[0].hold());
        assertEquals(3500, eras[1].hold());
        assertEquals(4320, eras[2].hold());
        assertEquals(4000, eras[3].hold());
        assertEquals(1000, eras[9].hold());
        assertEquals(200, eras[10].hold());
        assertEquals(480L, l.eraCoreStart(0));
        assertEquals(480L + 5000L + 480L, l.eraCoreStart(1));
        assertEquals(480L + 5000L + 480L + 3500L + 480L, l.eraCoreStart(2));
        long total = 480L * 12 + 5000 + 4000 + 3500 + 4320 + 5000 + 2000 * 4 + 1000 + 200;
        assertEquals(total, l.length(11));
    }

    @Test
    @DisplayName("named legacy eras run in the order written; an era named twice keeps its first place")
    void legacyWrittenOrder() {
        List<String> warnings = new ArrayList<>();
        CycleLayout l = CycleLayout.parse("legacy:void=100:classic=200:beta=300:classic=999", FADES, eraDefaults(),
                t -> true, warnings::add);
        LegacySpan[] eras = l.eras();
        assertEquals(3, eras.length);
        assertEquals(LegacyBandKind.VOID, eras[0].kind());
        assertEquals(LegacyBandKind.CLASSIC, eras[1].kind());
        assertEquals(200, eras[1].hold());
        assertEquals(LegacyBandKind.BETA, eras[2].kind());
        assertEquals(1, warnings.size(), warnings.toString());
    }

    @Test
    @DisplayName("slot lookup is exact at every boundary")
    void lookup() {
        CycleLayout l = shipped();
        for (int i = 0; i < l.count(); i++) {
            assertEquals(i, l.indexAt(l.start(i)));
            assertEquals(i, l.indexAt(l.start(i) + l.length(i) - 1));
        }
        assertEquals(-1, l.indexAt(-1L));
        assertEquals(-1, l.indexAt(l.period()));
    }

    @Test
    @DisplayName("disabled bands drop out; unknown tokens are reported and skipped")
    void parseFiltering() {
        List<String> warnings = new ArrayList<>();
        CycleLayout l = CycleLayout.parse("ow:100, nether:200, bogus:5, end:300, ow:xyz:50", FADES, eraDefaults(),
                t -> t != Type.END, warnings::add);
        assertEquals(3, l.count());
        assertEquals(Type.OVERWORLD, l.slot(0).type());
        assertEquals(Type.NETHER, l.slot(1).type());
        assertEquals(Type.OVERWORLD, l.slot(2).type());
        assertEquals(2, warnings.size(), warnings.toString());
        assertNull(CycleLayout.parse("   ", FADES, eraDefaults(), t -> true, warnings::add));
        assertNull(CycleLayout.parse("bogus", FADES, eraDefaults(), t -> true, warnings::add));
    }

    @Test
    @DisplayName("a bare legacy token takes every enabled era at its configured length; a disabled era is skipped")
    void bareLegacy() {
        LegacySpan[] defaults = eraDefaults();
        defaults[LegacyBandKind.SKYLANDS.ordinal()] = new LegacySpan(LegacyBandKind.SKYLANDS, 0, 480, 0); // disabled
        CycleLayout l = CycleLayout.parse("legacy", FADES, defaults, t -> true, m -> {});
        assertEquals(1, l.count());
        assertEquals(LegacyBandKind.values().length - 1, l.eras().length);
        for (LegacySpan e : l.eras()) assertEquals(6000, e.hold());
    }

    @Test
    @DisplayName("doubling: run k spans P·(2^k−1) onward and maps back to a base coordinate")
    void doubling() {
        long p = 1000L;
        assertEquals(0, CycleLayout.runIndex(0L, p));
        assertEquals(0, CycleLayout.runIndex(999L, p));
        assertEquals(1, CycleLayout.runIndex(1000L, p));
        assertEquals(1, CycleLayout.runIndex(2999L, p));
        assertEquals(2, CycleLayout.runIndex(3000L, p));
        assertEquals(3, CycleLayout.runIndex(7000L, p));
        assertEquals(0L, CycleLayout.runStart(0, p));
        assertEquals(1000L, CycleLayout.runStart(1, p));
        assertEquals(3000L, CycleLayout.runStart(2, p));
        assertEquals(7000L, CycleLayout.runStart(3, p));
        assertEquals(500L, CycleLayout.baseCoord(500L, p));
        assertEquals(0L, CycleLayout.baseCoord(1000L, p));
        assertEquals(500L, CycleLayout.baseCoord(2000L, p));      // 1000 into run 1 = base 500
        assertEquals(999L, CycleLayout.baseCoord(2999L, p));
        assertEquals(250L, CycleLayout.baseCoord(4000L, p));      // 1000 into run 2 = base 250
        assertEquals(-1L, CycleLayout.baseCoord(-1L, p));
    }

    @Test
    @DisplayName("influence windows and approach starts")
    void influenceAndApproach() {
        CycleLayout l = shipped();
        assertTrue(l.anyOfTypeIn(Type.NETHER, 2740L, 2760L));
        assertFalse(l.anyOfTypeIn(Type.NETHER, 0L, 2749L));
        assertFalse(l.anyOfTypeIn(Type.END, 0L, 9813L));
        assertTrue(l.anyOfTypeIn(Type.END, 9813L, 9814L));
        // Spheres (slot 9) follows BetterEnd directly: approach starts at its own slot.
        assertEquals(l.start(9), l.approachStart(9));
        // Chuncks (slot 13) sits after an OW gap that follows the legacy run: approach starts at the run's end.
        assertEquals(l.start(11) + l.length(11), l.approachStart(13));
        // Occurrence passes.
        assertEquals(-1, l.occurrencesStarted(Type.NETHER, 0L));
        assertEquals(0, l.occurrencesStarted(Type.NETHER, 2750L));
        assertEquals(0, l.occurrencesStarted(Type.NETHER, l.start(6) - 1));
        assertEquals(1, l.occurrencesStarted(Type.NETHER, l.start(6)));
    }
}
