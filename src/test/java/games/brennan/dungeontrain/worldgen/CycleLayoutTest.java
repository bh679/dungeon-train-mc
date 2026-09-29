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
    static final CycleLayout.Fades FADES = new CycleLayout.Fades(232, 0, 300, 120, 500, 600, 600, 10_000, 1500, 750, 1500, 480);

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
    @DisplayName("the shipped order parses to 17 slots and the planned run-1 length")
    void shippedGeometry() {
        CycleLayout l = shipped();
        assertEquals(17, l.count());
        // Lap 1: 2750 + 3814 (a 2750-core Nether) + 3250 (WWOO) + joined End (740+1200 | 2000+740)
        //        + (600+2500+600+3000+600) = 21,794
        // Lap 2: 4500 (BoP) + 5564 + Lost City (480+3000+480) + 6480 + (750+6550) + 500 (the sunk approach) = 28,304
        // Lap 3: legacy (480·12 + 4000 + 2500 + 4320 + 3500 + 4000 + 2000·4 + 1000 + 200 = 33,280)
        //        + 650 + (1500+2000) + 4000 (the mix zone) + 6500 = 47,930
        assertEquals(98_028L, l.period());
        assertEquals(2, l.typeCount(Type.NETHER));
        assertEquals(3, l.typeCount(Type.END));
        assertEquals(2, l.typeCount(Type.LEGACY_RUN));
        assertEquals(5, l.typeCount(Type.OVERWORLD));
        // Lap-1 starts
        assertEquals(0L, l.start(0));
        assertEquals(2750L, l.start(1));                        // Nether
        assertEquals(3814L, l.length(1));
        assertEquals(2750L + 3814L, l.start(2));                // WWOO after the Nether
        assertEquals(Style.WWOO, l.slot(2).style());
        assertEquals(3250L, l.length(2));
        assertEquals(9_814L, l.start(3));                       // End (vanilla piece)
        assertEquals(9_814L + 1940L, l.start(4));               // End (BoP piece) — no fade between them
        assertEquals(11_754L + 2740L, l.start(5));              // Upside-down
        assertEquals(7_300L, l.length(5));
        assertEquals(21_794L, l.start(6));                      // OW·BoP opens lap 2
        assertEquals(Style.BOP, l.slot(6).style());
        assertEquals(Style.BETTER, l.slot(7).style());
        assertEquals(Type.LEGACY_RUN, l.slot(8).type());        // Lost City, its own run
        assertEquals(Style.BETTER, l.slot(9).style());
        // Lap 1's Nether: vanilla for its first 1000 core blocks on the first run, then Biomes O' Plenty;
        // Biomes O' Plenty on every run after.
        assertEquals(Style.VANILLA, l.slot(1).styleOnRun(0));
        assertTrue(l.slot(1).hasSplit());
        assertEquals(1000, l.slot(1).splitAt());
        assertEquals(Style.BOP, l.slot(1).splitStyle());
        assertEquals(Style.BOP, l.slot(1).styleOnRun(1));
        assertEquals(2750L + 232L + 300L, l.netherCoreStart(1));    // past the beach + mountain rise and the core fade
        assertFalse(l.slot(7).hasSplit());
        // Lap 1's End: vanilla then BoP on every run.
        assertEquals(Style.VANILLA, l.slot(3).styleOnRun(0));
        assertEquals(Style.VANILLA, l.slot(3).styleOnRun(2));
        assertEquals(Style.BOP, l.slot(4).styleOnRun(0));
        assertEquals(Style.BOP, l.slot(4).styleOnRun(2));
        assertEquals(Style.VANILLA, l.slot(0).styleOnRun(1));             // Lap 1's overworld stays vanilla
        assertEquals(Style.BETTER, l.slot(7).styleOnRun(1));              // Lap 2 is the same every run
        assertEquals(Type.SPHERES, l.slot(10).type());
        assertEquals(Type.LEGACY_RUN, l.slot(12).type());
        assertEquals(21_794L + 28_304L, l.start(12));
        assertEquals(Style.SUNK, l.slot(11).style());            // the short approach into Amplified
        assertEquals(Type.MIX, l.slot(15).type());
        assertEquals(4000L, l.length(15));                      // hard-edged: no fades
        assertEquals(Type.STACKS, l.slot(16).type());
        assertEquals(1, l.occurrence(7));                       // the BetterNether slot is Nether occurrence 1
        assertEquals(0, l.occurrence(1));
        assertEquals(1, l.occurrence(4));                       // the BoP End piece is End occurrence 1
        assertEquals(2, l.occurrence(9));                       // BetterEnd is End occurrence 2
    }

    @Test
    @DisplayName("back-to-back End slots join into one band: one fade in, one fade out, cores summed")
    void joinedEnd() {
        CycleLayout l = shipped();
        assertEquals(3, l.endGroupFirst(3));
        assertEquals(3, l.endGroupFirst(4));
        assertEquals(2, l.endGroupSize(4));
        assertEquals(l.start(3), l.endGroupStart(4));
        assertEquals(3200, l.endGroupCore(3));
        assertEquals(3200, l.endGroupCore(4));
        // The joined band is exactly as long as one 3200-core End.
        assertEquals(Disintegration.bandLength(120, 500, 3200), l.endGroupLength(3));
        // A lone End is untouched.
        assertEquals(9, l.endGroupFirst(9));
        assertEquals(1, l.endGroupSize(9));
        assertEquals(5000, l.endGroupCore(9));
        assertEquals(Disintegration.bandLength(120, 500, 5000), l.length(9));
        // Three in a row: only the ends carry fades.
        CycleLayout three = CycleLayout.parse("end:100, end:200, end:300", FADES, eraDefaults(), t -> true, m -> {});
        assertEquals(740L + 100L, three.length(0));
        assertEquals(200L, three.length(1));
        assertEquals(300L + 740L, three.length(2));
        assertEquals(600, three.endGroupCore(1));
    }

    @Test
    @DisplayName("the legacy runs lay their eras back to back with one shared crossfade; Lost City runs alone")
    void legacyRun() {
        CycleLayout l = shipped();
        LegacySpan[] lost = l.eras(8);
        assertEquals(1, lost.length);
        assertEquals(LegacyBandKind.LOST_CITY, lost[0].kind());
        assertEquals(3000, lost[0].hold());
        assertEquals(480L + 3000L + 480L, l.length(8));
        assertEquals(8, l.legacySlotOf(LegacyBandKind.LOST_CITY));
        assertEquals(0, l.eraIndex(LegacyBandKind.LOST_CITY));

        LegacySpan[] eras = l.eras(12);
        assertEquals(11, eras.length);
        // The shipped run order is the order the token writes, not declaration order.
        assertArrayEquals(new LegacyBandKind[] {LegacyBandKind.AMPLIFIED, LegacyBandKind.BETA, LegacyBandKind.FAR_LANDS,
                        LegacyBandKind.CAVES_OF_CHAOS, LegacyBandKind.SKYLANDS, LegacyBandKind.FLOATING, LegacyBandKind.ALPHA,
                        LegacyBandKind.INFDEV, LegacyBandKind.CLASSIC, LegacyBandKind.SUPERFLAT, LegacyBandKind.VOID},
                java.util.Arrays.stream(eras).map(LegacySpan::kind).toArray(LegacyBandKind[]::new));
        assertEquals(4000, eras[0].hold());
        assertEquals(2500, eras[1].hold());
        assertEquals(4320, eras[2].hold());
        assertEquals(3500, eras[3].hold());
        assertEquals(1000, eras[9].hold());
        assertEquals(200, eras[10].hold());
        assertEquals(480L, l.eraCoreStart(12, 0));
        assertEquals(480L + 4000L + 480L, l.eraCoreStart(12, 1));
        assertEquals(480L + 4000L + 480L + 2500L + 480L, l.eraCoreStart(12, 2));
        long total = 480L * 12 + 4000 + 2500 + 4320 + 3500 + 4000 + 2000 * 4 + 1000 + 200;
        assertEquals(total, l.length(12));
        assertEquals(12, l.legacySlotOf(LegacyBandKind.BETA));
        assertEquals(1, l.eraIndex(LegacyBandKind.BETA));
        assertEquals(12, l.allEras().size());
        assertEquals(0, l.eras(0).length);                     // a non-legacy slot has no eras
    }

    @Test
    @DisplayName("a legacy run can wear a look; a vanilla-terrain run after a Nether leads in over its exit mountains")
    void legacyStyleAndLeadIn() {
        CycleLayout l = shipped();
        assertEquals(Style.WWOO, l.slot(8).style());                 // legacy:wwoo:lost_city=3000
        assertEquals(1, l.eras(8).length);
        assertEquals(Style.VANILLA, l.slot(12).style());
        assertEquals(232L, l.legacyLeadIn(8));                       // megaHold 0 + rise 232
        assertEquals(0L, l.legacyLeadIn(12));                        // after an overworld gap
        assertEquals(0L, l.legacyLeadIn(7));                         // not a legacy slot
        List<String> warnings = new ArrayList<>();
        CycleLayout bare = CycleLayout.parse("ow:100, legacy:bop", FADES, eraDefaults(), t -> true, warnings::add);
        assertEquals(Style.BOP, bare.slot(1).style());
        assertEquals(LegacyBandKind.values().length, bare.eras(1).length);   // a bare run: every era
        assertTrue(warnings.isEmpty(), warnings.toString());
        // an old-generator era leads in over nothing, even after a Nether
        CycleLayout beta = CycleLayout.parse("nether:3000, legacy:beta=1000", FADES, eraDefaults(), t -> true, m -> {});
        assertEquals(0L, beta.legacyLeadIn(1));
    }

    @Test
    @DisplayName("an era already run by an earlier legacy slot is dropped from a later one")
    void legacyEraInOneSlotOnly() {
        List<String> warnings = new ArrayList<>();
        CycleLayout l = CycleLayout.parse("legacy:beta=300, ow:100, legacy:classic=200:beta=999", FADES, eraDefaults(),
                t -> true, warnings::add);
        assertEquals(1, l.eras(0).length);
        assertEquals(1, l.eras(2).length);
        assertEquals(LegacyBandKind.CLASSIC, l.eras(2)[0].kind());
        assertEquals(0, l.legacySlotOf(LegacyBandKind.BETA));
        assertEquals(1, warnings.size(), warnings.toString());
        // A bare legacy after a named one takes every era the first run doesn't.
        CycleLayout bare = CycleLayout.parse("legacy:lost_city=4000, legacy", FADES, eraDefaults(), t -> true, m -> {});
        assertEquals(LegacyBandKind.values().length - 1, bare.eras(1).length);
        assertEquals(0, bare.legacySlotOf(LegacyBandKind.LOST_CITY));
    }

    @Test
    @DisplayName("named legacy eras run in the order written; an era named twice keeps its first place")
    void legacyWrittenOrder() {
        List<String> warnings = new ArrayList<>();
        CycleLayout l = CycleLayout.parse("legacy:void=100:classic=200:beta=300:classic=999", FADES, eraDefaults(),
                t -> true, warnings::add);
        LegacySpan[] eras = l.eras(0);
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
        assertEquals(LegacyBandKind.values().length - 1, l.eras(0).length);
        for (LegacySpan e : l.eras(0)) assertEquals(6000, e.hold());
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
        assertFalse(l.anyOfTypeIn(Type.END, 0L, 9_813L));
        assertTrue(l.anyOfTypeIn(Type.END, 9_813L, 9_814L));
        // Spheres (slot 10) follows BetterEnd directly: approach starts at its own slot.
        assertEquals(l.start(10), l.approachStart(10));
        // Chuncks (slot 14) sits after an OW gap that follows the legacy run: approach starts at the run's end.
        assertEquals(l.start(12) + l.length(12), l.approachStart(14));
        // Occurrence passes.
        assertEquals(-1, l.occurrencesStarted(Type.NETHER, 0L));
        assertEquals(0, l.occurrencesStarted(Type.NETHER, 2750L));
        assertEquals(0, l.occurrencesStarted(Type.NETHER, l.start(7) - 1));
        assertEquals(1, l.occurrencesStarted(Type.NETHER, l.start(7)));
    }
}
