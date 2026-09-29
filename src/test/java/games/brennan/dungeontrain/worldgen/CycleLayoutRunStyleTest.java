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
 * The {@code first>later} style switch: a slot wears one look on the first run of the order and
 * another on every run after — how the shipped order gives Lap 1's Nether its Biomes O' Plenty look
 * from the second cycle on — and the first-run {@code a=N+b} split, which turns that Nether BoP
 * partway through its core on the first cycle. Lap 1's End is vanilla then BoP (two joined pieces)
 * every cycle, and Lap 2 stays BoP → BetterNether → Lost City → BetterEnd.
 */
final class CycleLayoutRunStyleTest {

    private static final long START = 10_000L;
    private static final CycleLayout LAYOUT = CycleLayoutTest.shipped();
    private static final long P = LAYOUT.period();
    private static final WorldGenCycle C = new WorldGenCycle(START, 10_000, 40, new int[] {1, 2, 4, 8, 15}, 32, 0, 300, 5000,
            120, 500, 5000, 600, 5000, 600, 10_000, 8000, 1500, 5000, 0.3, 0.4, 6550, 750, 5000, 8000, 1500, 10_000, 0.08,
            CycleLayoutTest.eraDefaults(), LAYOUT, 0);

    private static int x(long u, int k) {
        return (int) (START + CycleLayout.runStart(k, P) + (u << k));
    }

    private static int mid(int slot, int k) {
        return x(LAYOUT.start(slot) + LAYOUT.length(slot) / 2, k);
    }

    @Test
    @DisplayName("a>b parses to look a on run 0 and look b after; a plain style is the same every run")
    void parse() {
        List<String> warnings = new ArrayList<>();
        CycleLayout l = CycleLayout.parse("ow:100, nether:vanilla>bop:3000, end:better:3000, ow:bad>bop:50",
                CycleLayoutTest.FADES, CycleLayoutTest.eraDefaults(), t -> true, warnings::add);
        assertEquals(Style.VANILLA, l.slot(1).style());
        assertEquals(Style.BOP, l.slot(1).laterStyle());
        assertEquals(Style.BETTER, l.slot(2).styleOnRun(0));
        assertEquals(Style.BETTER, l.slot(2).styleOnRun(5));
        assertEquals(1, warnings.size(), warnings.toString());          // the bad switch is reported, slot kept
        assertEquals(Style.VANILLA, l.slot(3).styleOnRun(1));
    }

    @Test
    @DisplayName("a=N+b splits the first run's core: look a for N blocks, then b; later runs take '>' or else b")
    void parseSplit() {
        List<String> warnings = new ArrayList<>();
        CycleLayout l = CycleLayout.parse("ow:100, nether:vanilla=1000+bop>bop:2750, nether:vanilla=1000+bop:2750",
                CycleLayoutTest.FADES, CycleLayoutTest.eraDefaults(), t -> true, warnings::add);
        assertTrue(warnings.isEmpty(), warnings.toString());
        CycleLayout.Slot s = l.slot(1);
        assertEquals(Style.VANILLA, s.style());
        assertTrue(s.hasSplit());
        assertEquals(1000, s.splitAt());
        assertEquals(Style.BOP, s.splitStyle());
        assertEquals(Style.BOP, s.laterStyle());
        assertEquals(2750, s.core());
        CycleLayout.Slot noLater = l.slot(2);                            // no '>': later runs wear the split look
        assertTrue(noLater.hasSplit());
        assertEquals(Style.VANILLA, noLater.style());
        assertEquals(Style.BOP, noLater.laterStyle());
        assertEquals(2750, noLater.core());
        assertFalse(l.slot(0).hasSplit());
    }

    @Test
    @DisplayName("a split outside a Nether slot is warned and dropped; a malformed split is warned")
    void parseSplitRejects() {
        List<String> warnings = new ArrayList<>();
        CycleLayout l = CycleLayout.parse("ow:100, end:vanilla=100+bop:500", CycleLayoutTest.FADES,
                CycleLayoutTest.eraDefaults(), t -> true, warnings::add);
        assertEquals(1, warnings.size(), warnings.toString());
        CycleLayout.Slot end = l.slot(1);
        assertFalse(end.hasSplit());
        assertEquals(-1, end.splitAt());
        assertEquals(Style.VANILLA, end.style());
        assertEquals(Style.VANILLA, end.laterStyle());                   // the dropped split doesn't leak into later runs
        assertEquals(500, end.core());

        warnings.clear();
        CycleLayout bad = CycleLayout.parse("ow:100, nether:vanilla=x+bop:2750", CycleLayoutTest.FADES,
                CycleLayoutTest.eraDefaults(), t -> true, warnings::add);
        assertEquals(1, warnings.size(), warnings.toString());
        assertFalse(bad.slot(1).hasSplit());
        assertEquals(2750, bad.slot(1).core());                          // the slot is kept, the split ignored
    }

    @Test
    @DisplayName("Lap 1's Nether turns BoP partway through on the first cycle and is BoP after; its End is vanilla then BoP every cycle")
    void lap1TurnsBop() {
        assertEquals(Style.VANILLA, C.netherStyleAt(mid(1, 0)));                // the slot's first-run style
        long split = LAYOUT.netherCoreStart(1) + LAYOUT.slot(1).splitAt();
        assertEquals(Style.VANILLA, C.netherLookAt(x(split - 1, 0)));
        assertFalse(C.isBopNetherAt(x(split - 1, 0)));
        assertEquals(Style.BOP, C.netherLookAt(x(split, 0)));
        assertTrue(C.isBopNetherAt(x(split + 1, 0)));
        for (int k = 0; k <= 3; k++) {
            assertEquals(Style.VANILLA, C.endStyleAt(mid(3, k)), "run " + k);
            assertEquals(Style.BOP, C.endStyleAt(mid(4, k)), "run " + k);
            assertTrue(C.isBopEndAt(mid(4, k)));
            assertFalse(C.isBopEndAt(mid(3, k)));
            assertEquals(SecondLapOverworld.Stretch.WWOO, SecondLapOverworld.at(C, mid(2, k)), "Lap 1 WWOO every cycle");
        }
        for (int k = 1; k <= 3; k++) {
            assertEquals(Style.BOP, C.netherStyleAt(mid(1, k)), "run " + k);
            assertTrue(C.isBopNetherAt(mid(1, k)));
            assertEquals(SecondLapOverworld.Stretch.VANILLA, SecondLapOverworld.at(C, mid(0, k)), "Lap 1 overworld stays vanilla");
        }
        // passes: 2 Nether occurrences per run → pass 0 vanilla, 1 Better, 2 BoP, 3 Better
        assertEquals(Style.VANILLA, C.netherStyleOfPass(0));
        assertEquals(Style.BETTER, C.netherStyleOfPass(1));
        assertEquals(Style.BOP, C.netherStyleOfPass(2));
        assertEquals(Style.BETTER, C.netherStyleOfPass(3));
        // 3 End occurrences per run → vanilla, BoP, Better, repeating
        assertEquals(Style.VANILLA, C.endStyleOfPass(3));
        assertEquals(Style.BOP, C.endStyleOfPass(4));
        assertEquals(Style.BETTER, C.endStyleOfPass(5));
    }

    @Test
    @DisplayName("Lap 2 is BoP → BetterNether → Lost City → BetterEnd on every cycle")
    void lap2Unchanged() {
        for (int k = 0; k <= 2; k++) {
            assertEquals(Style.BOP, C.overworldStyleAt(mid(6, k)));
            assertEquals(Style.BETTER, C.netherStyleAt(mid(7, k)));
            assertTrue(C.isInLegacyBand(games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind.LOST_CITY, mid(8, k)));
            assertEquals(Style.BETTER, C.endStyleAt(mid(9, k)));
        }
    }
}
