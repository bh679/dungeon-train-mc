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
 * another on every run after — how the shipped order gives Lap 1's Nether and End their Biomes O'
 * Plenty look from the second cycle on, while Lap 2 stays WWOO → BetterNether → BoP → BetterEnd.
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
    @DisplayName("Lap 1's Nether and End are vanilla on the first cycle and BoP on every cycle after")
    void lap1TurnsBop() {
        assertEquals(Style.VANILLA, C.netherStyleAt(mid(1, 0)));
        assertEquals(Style.VANILLA, C.endStyleAt(mid(3, 0)));
        for (int k = 1; k <= 3; k++) {
            assertEquals(Style.BOP, C.netherStyleAt(mid(1, k)), "run " + k);
            assertEquals(Style.BOP, C.endStyleAt(mid(3, k)), "run " + k);
            assertTrue(C.isBopNetherAt(mid(1, k)));
            assertTrue(C.isBopEndAt(mid(3, k)));
            assertEquals(SecondLapOverworld.Stretch.VANILLA, SecondLapOverworld.at(C, mid(0, k)), "Lap 1 overworld stays vanilla");
        }
        assertFalse(C.isBopNetherAt(mid(1, 0)));
        // passes: 2 Nether occurrences per run → pass 0 vanilla, 1 Better, 2 BoP, 3 Better
        assertEquals(Style.VANILLA, C.netherStyleOfPass(0));
        assertEquals(Style.BETTER, C.netherStyleOfPass(1));
        assertEquals(Style.BOP, C.netherStyleOfPass(2));
        assertEquals(Style.BETTER, C.netherStyleOfPass(3));
        assertEquals(Style.BOP, C.endStyleOfPass(4));
    }

    @Test
    @DisplayName("Lap 2 is WWOO → BetterNether → BoP → BetterEnd on every cycle")
    void lap2Unchanged() {
        for (int k = 0; k <= 2; k++) {
            assertEquals(Style.WWOO, C.overworldStyleAt(mid(5, k)));
            assertEquals(Style.BETTER, C.netherStyleAt(mid(6, k)));
            assertEquals(Style.BOP, C.overworldStyleAt(mid(7, k)));
            assertEquals(Style.BETTER, C.endStyleAt(mid(8, k)));
        }
    }
}
