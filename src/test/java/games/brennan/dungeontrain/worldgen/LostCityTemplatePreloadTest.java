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

    /** First world X, scanning chunk by chunk from base {@code fromU} on run 0, where a city can start. */
    private static int firstCityX(long fromU) {
        for (int x = x(fromU, 0); x < x(fromU + LAYOUT.period(), 0); x += 16) {
            if (LostCityStructures.density(C, x >> 4) > 0.0D) return x;
        }
        throw new AssertionError("no city column after base " + fromU);
    }

    @Test
    @DisplayName("spawn's vanilla overworld is out of reach; the WWOO stretch is picked up 3000 blocks early")
    void wwooStretch() {
        long wwooStart = LAYOUT.start(WWOO_SLOT);
        assertEquals(CycleLayout.Style.WWOO, C.overworldStyleAt(x(wwooStart + 100L, 0)));
        assertFalse(nearLostCity(C, x(1000L, 0), LOOKAHEAD_BLOCKS));
        int first = firstCityX(0L);
        assertTrue(nearLostCity(C, first - LOOKAHEAD_BLOCKS + 16, LOOKAHEAD_BLOCKS));
        assertFalse(nearLostCity(C, first - LOOKAHEAD_BLOCKS - 2 * LostCityTemplatePreload.STEP_BLOCKS, LOOKAHEAD_BLOCKS));
        assertTrue(nearLostCity(C, x(wwooStart + LAYOUT.length(WWOO_SLOT) / 2, 0), LOOKAHEAD_BLOCKS));
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
    @DisplayName("without a Lost City era or a WWOO stretch, nothing ever triggers")
    void noCities() {
        CycleLayout without = layout(CycleLayout.DEFAULT_ORDER
                .replace("legacy:wwoo:lost_city=4000, ", "")
                .replace("ow:wwoo:4500", "ow:4500"));
        WorldGenCycle c = cycle(without);
        for (long u = 0; u < without.period(); u += 500) {
            assertFalse(nearLostCity(c, (int) (START + u), LOOKAHEAD_BLOCKS), "u=" + u);
        }
        assertFalse(nearLostCity(null, 0, LOOKAHEAD_BLOCKS));
    }
}
