package games.brennan.dungeontrain.worldgen;

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

    @Test
    @DisplayName("eviction waits while a player is near the run or in the WWOO stretch, and is free between")
    void quietAt() {
        assertFalse(LostCityTemplatePreload.quietAt(C, x(coreStart() + 2000L, 0)));                       // in the run
        assertFalse(LostCityTemplatePreload.quietAt(C, x(LAYOUT.start(WWOO_SLOT) + 100L, 0)));            // WWOO foretaste
        int bopSlot = WWOO_SLOT + 4;
        assertTrue(LostCityTemplatePreload.quietAt(C, x(LAYOUT.start(bopSlot) + LAYOUT.length(bopSlot) / 2, 0)));
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
