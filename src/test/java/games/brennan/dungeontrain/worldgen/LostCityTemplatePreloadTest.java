package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.worldgen.LostCityTemplatePreload.Mode;
import games.brennan.dungeontrain.worldgen.LostCityTemplatePreload.Need;
import games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static games.brennan.dungeontrain.worldgen.LostCityStructuresTest.C;
import static games.brennan.dungeontrain.worldgen.LostCityStructuresTest.LAYOUT;
import static games.brennan.dungeontrain.worldgen.LostCityStructuresTest.START;
import static games.brennan.dungeontrain.worldgen.LostCityStructuresTest.coreStart;
import static games.brennan.dungeontrain.worldgen.LostCityStructuresTest.cycle;
import static games.brennan.dungeontrain.worldgen.LostCityStructuresTest.layout;
import static games.brennan.dungeontrain.worldgen.LostCityStructuresTest.x;
import static games.brennan.dungeontrain.worldgen.LostCityTemplatePreload.LOOKAHEAD_BLOCKS;
import static games.brennan.dungeontrain.worldgen.LostCityTemplatePreload.nearLostCity;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LostCityTemplatePreloadTest {

    /** The shipped order's WWOO overworld stretch (Lap 1's few ruins). */
    private static final int WWOO_SLOT = 2;

    /** First world X, scanning chunk by chunk from base {@code fromU} on run 0, where the run can start a city. */
    private static int firstCityX(long fromU) {
        for (int x = x(fromU, 0); x < x(fromU + LAYOUT.period(), 0); x += 16) {
            if (!LostCityStructures.inWwooStretch(C, x >> 4) && LostCityStructures.density(C, x >> 4) > 0.0D) return x;
        }
        throw new AssertionError("no city column after base " + fromU);
    }

    @Test
    @DisplayName("the WWOO foretaste never triggers the pre-load, and neither does spawn's vanilla overworld")
    void wwooStretchDoesNotTrigger() {
        long wwooStart = LAYOUT.start(WWOO_SLOT);
        assertEquals(CycleLayout.Style.WWOO, C.overworldStyleAt(x(wwooStart + 100L, 0)));
        assertTrue(LostCityStructures.density(C, x(wwooStart + 100L, 0) >> 4) > 0.0D);   // cities can start there…
        assertFalse(nearLostCity(C, x(1000L, 0), LOOKAHEAD_BLOCKS));
        for (long u = wwooStart - LOOKAHEAD_BLOCKS; u < wwooStart + LAYOUT.length(WWOO_SLOT) + LOOKAHEAD_BLOCKS; u += 500) {
            assertFalse(nearLostCity(C, x(u, 0), LOOKAHEAD_BLOCKS), "u=" + u);        // …but it doesn't pre-load
        }
    }

    @Test
    @DisplayName("the Lost City run triggers a lookahead before its first building, and inside it")
    void lostCityRun() {
        int slot = LAYOUT.legacySlotOf(LegacyBandKind.LOST_CITY);
        long searchFrom = LAYOUT.start(slot) - LAYOUT.legacyLeadIn(slot);
        int first = firstCityX(searchFrom);
        assertTrue(nearLostCity(C, first - LOOKAHEAD_BLOCKS + 16, LOOKAHEAD_BLOCKS));
        assertFalse(nearLostCity(C, first - LOOKAHEAD_BLOCKS - 2 * LostCityTemplatePreload.STEP_BLOCKS, LOOKAHEAD_BLOCKS));
        assertTrue(nearLostCity(C, x(coreStart() + 2000L, 0), LOOKAHEAD_BLOCKS));
        assertTrue(nearLostCity(C, x(coreStart() + 2000L, 1), LOOKAHEAD_BLOCKS));   // the stretched second lap
    }

    @Test
    @DisplayName("the stretches between (End, upside-down, BoP overworld) don't trigger")
    void quietBetween() {
        int bopSlot = WWOO_SLOT + 4;
        assertEquals(CycleLayout.Style.BOP, LAYOUT.slot(bopSlot).style());
        assertFalse(nearLostCity(C, x(LAYOUT.start(bopSlot) + LAYOUT.length(bopSlot) / 2, 0), LOOKAHEAD_BLOCKS));
    }

    /** Generation reach at the default view distance of 12. */
    private static final int REACH = LostCityTemplatePreload.reachBlocks(12);

    /** Lowest world X of the WWOO stretch's first chunk column on run 0. */
    private static int wwooEdgeX() {
        for (int x = x(LAYOUT.start(WWOO_SLOT) - 2000L, 0) & ~15; ; x += 16) {
            if (LostCityStructures.inWwooStretch(C, x >> 4)) return x;
        }
    }

    private static Need need(int worldX, int reach) {
        return LostCityTemplatePreload.needAt(C, worldX, reach, Mode.REACH);
    }

    @Test
    @DisplayName("eviction waits while a player is near the run or in the WWOO stretch, and is free between")
    void quietAt() {
        assertFalse(LostCityTemplatePreload.quietAt(C, x(coreStart() + 2000L, 0), REACH));                // in the run
        assertFalse(LostCityTemplatePreload.quietAt(C, x(LAYOUT.start(WWOO_SLOT) + 100L, 0), REACH));     // WWOO foretaste
        int bopSlot = WWOO_SLOT + 4;
        assertTrue(LostCityTemplatePreload.quietAt(C, x(LAYOUT.start(bopSlot) + LAYOUT.length(bopSlot) / 2, 0), REACH));
    }

    @Test
    @DisplayName("generation reach is the view distance plus the structure-reference margin, in blocks")
    void reach() {
        assertEquals((12 + LostCityTemplatePreload.GEN_MARGIN_CHUNKS) * 16, REACH);
        assertEquals(LostCityTemplatePreload.GEN_MARGIN_CHUNKS * 16, LostCityTemplatePreload.reachBlocks(-3));
        assertTrue(LostCityTemplatePreload.reachBlocks(32) > REACH);
    }

    @Test
    @DisplayName("a player just outside the WWOO stretch, whose view reaches into it, is not quiet (the pre-change rule said they were)")
    void wwooEdgeWithinReach() {
        int edge = wwooEdgeX();
        int outside = edge - 200;                                    // own X outside, view inside
        assertFalse(LostCityStructures.inWwooStretch(C, outside >> 4));
        assertEquals(Need.QUIET, LostCityTemplatePreload.needAt(C, outside, REACH, Mode.LEGACY));
        assertEquals(Need.FORETASTE, need(outside, REACH));
        assertEquals(Need.FORETASTE, need(edge + 500, REACH));       // inside
        assertEquals(Need.HOLD, LostCityTemplatePreload.needAt(C, edge + 500, REACH, Mode.LEGACY));
    }

    @Test
    @DisplayName("the WWOO stretch's windows: foretaste out to reach + lookahead, held a margin further, quiet beyond; both edges")
    void wwooWindows() {
        int edge = wwooEdgeX();
        int foretaste = REACH + LostCityTemplatePreload.FORETASTE_LOOKAHEAD_BLOCKS;
        int hold = foretaste + LostCityTemplatePreload.EVICT_MARGIN_BLOCKS;
        assertEquals(Need.FORETASTE, need(edge - foretaste + 32, REACH));
        assertEquals(Need.HOLD, need(edge - foretaste - 32, REACH));
        assertEquals(Need.HOLD, need(edge - hold + 32, REACH));
        assertEquals(Need.QUIET, need(edge - hold - 32, REACH));

        int far = edge;
        while (LostCityStructures.inWwooStretch(C, far >> 4)) far += 16;   // first column past the stretch
        int last = far - 1;
        assertEquals(Need.FORETASTE, need(last + foretaste - 32, REACH));
        assertEquals(Need.HOLD, need(last + foretaste + 32, REACH));
        assertEquals(Need.QUIET, need(last + hold + 32, REACH));
    }

    @Test
    @DisplayName("a longer view distance reaches the WWOO stretch from further out")
    void reachScalesTheForetasteWindow() {
        int at = wwooEdgeX() - LostCityTemplatePreload.FORETASTE_LOOKAHEAD_BLOCKS - 500;
        assertEquals(Need.HOLD, need(at, REACH));                                           // 352 < 500
        assertEquals(Need.FORETASTE, need(at, LostCityTemplatePreload.reachBlocks(32)));    // 672 > 500
    }

    @Test
    @DisplayName("the run loads at the lookahead and evicts only a margin beyond it, so hovering at the edge never evicts")
    void runHysteresisBand() {
        int slot = LAYOUT.legacySlotOf(LegacyBandKind.LOST_CITY);
        int first = firstCityX(LAYOUT.start(slot) - LAYOUT.legacyLeadIn(slot));
        int step = LostCityTemplatePreload.STEP_BLOCKS;
        assertEquals(Need.RUN, need(first - LOOKAHEAD_BLOCKS + 16, REACH));
        assertEquals(Need.HOLD, need(first - LOOKAHEAD_BLOCKS - 2 * step, REACH));
        assertEquals(Need.HOLD, need(first - LOOKAHEAD_BLOCKS - LostCityTemplatePreload.EVICT_MARGIN_BLOCKS + 16, REACH));
        assertEquals(Need.QUIET, need(first - LOOKAHEAD_BLOCKS - LostCityTemplatePreload.EVICT_MARGIN_BLOCKS - 2 * step, REACH));
        // the pre-change rule had one threshold: quiet as soon as the run left the lookahead
        assertEquals(Need.QUIET, LostCityTemplatePreload.needAt(C, first - LOOKAHEAD_BLOCKS - 2 * step, REACH, Mode.LEGACY));

        int quiet = 0;
        int legacyQuiet = 0;
        int legacyEvictions = 0;
        for (int scan = 0; scan < 20 * LostCityTemplatePreload.EVICT_AFTER_SCANS; scan++) {
            // 60 s outside the lookahead, 60 s inside, back and forth
            boolean out = (scan / (2 * LostCityTemplatePreload.EVICT_AFTER_SCANS)) % 2 == 0;
            int at = first - LOOKAHEAD_BLOCKS + (out ? -2 * step : 2 * step);
            quiet = LostCityTemplatePreload.nextQuietScans(quiet,
                    LostCityTemplatePreload.quietScan(need(at, REACH), false, false));
            legacyQuiet = LostCityTemplatePreload.nextQuietScans(legacyQuiet,
                    LostCityTemplatePreload.quietScan(LostCityTemplatePreload.needAt(C, at, REACH, Mode.LEGACY), false, false));
            if (legacyQuiet >= LostCityTemplatePreload.EVICT_AFTER_SCANS) {
                legacyQuiet = 0;
                legacyEvictions++;
            }
            assertTrue(quiet < LostCityTemplatePreload.EVICT_AFTER_SCANS, "evicted while hovering, scan " + scan);
        }
        assertEquals(0, quiet);
        assertTrue(legacyEvictions >= 5, "the pre-change rule evicted on every pass outside: " + legacyEvictions);
    }

    @Test
    @DisplayName("a scan counts toward eviction only with every position quiet, no pre-load running and no recent demand")
    void quietScan() {
        assertTrue(LostCityTemplatePreload.quietScan(Need.QUIET, false, false));
        assertFalse(LostCityTemplatePreload.quietScan(Need.QUIET, true, false));
        assertFalse(LostCityTemplatePreload.quietScan(Need.QUIET, false, true));
        assertFalse(LostCityTemplatePreload.quietScan(Need.HOLD, false, false));
        assertFalse(LostCityTemplatePreload.quietScan(Need.FORETASTE, false, false));
        assertFalse(LostCityTemplatePreload.quietScan(Need.RUN, false, false));
    }

    @Test
    @DisplayName("an approved start holds the cache for the demand window and no longer; no start holds nothing")
    void demandHold() {
        long last = 5_000_000_000L;
        long hold = LostCityTemplatePreload.DEMAND_HOLD_NANOS;
        assertFalse(LostCityTemplatePreload.demandHolds(false, last + 1, last));
        assertTrue(LostCityTemplatePreload.demandHolds(true, last, last));
        assertTrue(LostCityTemplatePreload.demandHolds(true, last + hold - 1, last));
        assertFalse(LostCityTemplatePreload.demandHolds(true, last + hold, last));
        // nanoTime may be negative and may wrap: only the difference counts
        assertTrue(LostCityTemplatePreload.demandHolds(true, Long.MIN_VALUE + 10, Long.MAX_VALUE - 10));
    }

    @Test
    @DisplayName("a teleport is a one-tick move of JUMP_BLOCKS or more, either way; travel is not")
    void jumps() {
        assertFalse(LostCityTemplatePreload.jumped(1000, 1004));
        assertFalse(LostCityTemplatePreload.jumped(1000, 1000 - LostCityTemplatePreload.JUMP_BLOCKS + 1));
        assertTrue(LostCityTemplatePreload.jumped(1000, 1000 + LostCityTemplatePreload.JUMP_BLOCKS));
        assertTrue(LostCityTemplatePreload.jumped(1000, -50_000));
        assertTrue(LostCityTemplatePreload.jumped(Integer.MIN_VALUE + 5, Integer.MAX_VALUE - 5));
    }

    @Test
    @DisplayName("the quiet count climbs only over consecutive quiet scans, so eviction needs EVICT_AFTER_SCANS in a row")
    void hysteresis() {
        int q = 0;
        for (int i = 0; i < LostCityTemplatePreload.EVICT_AFTER_SCANS - 1; i++) q = LostCityTemplatePreload.nextQuietScans(q, true);
        assertTrue(q < LostCityTemplatePreload.EVICT_AFTER_SCANS);
        q = LostCityTemplatePreload.nextQuietScans(q, false);
        assertEquals(0, q);
        for (int i = 0; i < LostCityTemplatePreload.EVICT_AFTER_SCANS; i++) q = LostCityTemplatePreload.nextQuietScans(q, true);
        assertEquals(LostCityTemplatePreload.EVICT_AFTER_SCANS, q);
    }

    @Test
    @DisplayName("without a Lost City era nothing ever triggers")
    void noCities() {
        CycleLayout without = layout(CycleLayout.DEFAULT_ORDER.replace("legacy:wwoo:lost_city=3000, ", ""));
        WorldGenCycle c = cycle(without);
        for (long u = 0; u < without.period(); u += 500) {
            assertFalse(nearLostCity(c, (int) (START + u), LOOKAHEAD_BLOCKS), "u=" + u);
        }
        assertFalse(nearLostCity(null, 0, LOOKAHEAD_BLOCKS));
    }
}
