package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.worldgen.CycleLayout.Style;
import games.brennan.dungeontrain.worldgen.CycleLayout.Type;
import games.brennan.dungeontrain.worldgen.density.BetterNetherCoreBiomes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WorldGenCycle#isBetterNetherPass} / {@link WorldGenCycle#isBetterEndPass}: the one rule
 * worldgen, advancements and {@code /dtp} share. With a layout it follows each slot's configured
 * style, whatever the pass parity; the classic layout keeps its alternation.
 */
final class WorldGenCycleBetterStyleTest {

    private static final long START = 10_000L;

    private static CycleLayout layout(String order) {
        List<String> warnings = new ArrayList<>();
        CycleLayout l = CycleLayout.parse(order, CycleLayoutTest.FADES, CycleLayoutTest.eraDefaults(), t -> true, warnings::add);
        assertTrue(warnings.isEmpty(), warnings.toString());
        return l;
    }

    private static WorldGenCycle cycle(CycleLayout layout) {
        return new WorldGenCycle(START, 10_000, 40, new int[] {1, 2, 4, 8, 15}, 32, 0, 300, 5000,
                120, 500, 5000, 600, 5000, 600, 10_000, 8000, 1500, 5000, 0.3, 0.4, 12_000, 1500, 5000, 8000, 1500, 10_000, 0.08,
                CycleLayoutTest.eraDefaults(), layout, 0);
    }

    /** World X a little way into slot {@code i} on run {@code k}. */
    private static int x(CycleLayout l, int i, int k) {
        return (int) (START + CycleLayout.runStart(k, l.period()) + ((l.start(i) + 100) << k));
    }

    @Test
    @DisplayName("a first Nether marked :better is the BetterNether one — in worldgen, not just the config")
    void firstNetherBetter() {
        CycleLayout l = layout("nether:better:3000, ow:1000, nether:3000");
        WorldGenCycle c = cycle(l);
        assertTrue(c.isBetterNetherPass(0));
        assertFalse(c.isBetterNetherPass(1));
        assertTrue(c.isBetterNetherPass(2));
        assertFalse(c.isBetterNetherPass(3));
        assertFalse(c.isBetterNetherPass(-1));
        for (int k = 0; k < 3; k++) {
            assertTrue(c.isBetterNetherAt(x(l, 0, k)), "run " + k + " first Nether");
            assertEquals(Style.BETTER, c.netherStyleAt(x(l, 0, k)));
            assertFalse(c.isBetterNetherAt(x(l, 2, k)), "run " + k + " second Nether");
            assertEquals(Style.VANILLA, c.netherStyleAt(x(l, 2, k)));
        }
        // Between the bands the pass is the last one started — the overworld gap follows the better Nether.
        assertTrue(c.isBetterNetherAt(x(l, 1, 0)));
    }

    @Test
    @DisplayName("one Nether per run: its style holds on every run, not alternating by run index")
    void oneNetherPerRun() {
        WorldGenCycle plain = cycle(layout("ow:1000, nether:3000"));
        WorldGenCycle better = cycle(layout("ow:1000, nether:better:3000"));
        for (long pass = 0; pass < 6; pass++) {
            assertFalse(plain.isBetterNetherPass(pass), "plain pass " + pass);
            assertTrue(better.isBetterNetherPass(pass), "better pass " + pass);
        }
    }

    @Test
    @DisplayName("three Nethers per run follow their own styles")
    void threeNethersPerRun() {
        WorldGenCycle c = cycle(layout("nether:3000, ow:1000, nether:better:3000, ow:1000, nether:better:3000"));
        boolean[] want = {false, true, true, false, true, true};
        for (int pass = 0; pass < want.length; pass++) assertEquals(want[pass], c.isBetterNetherPass(pass), "pass " + pass);
    }

    @Test
    @DisplayName("a first End marked :better is the BetterEnd one")
    void firstEndBetter() {
        CycleLayout l = layout("ow:1000, end:better:3000, ow:1000, end:3000");
        WorldGenCycle c = cycle(l);
        assertTrue(c.isBetterEndPass(0));
        assertFalse(c.isBetterEndPass(1));
        assertTrue(c.isBetterEndPass(2));
        assertFalse(c.isBetterEndPass(-1));
        assertTrue(c.isBetterEndAt(x(l, 1, 1)));
        assertFalse(c.isBetterEndAt(x(l, 3, 1)));
    }

    @Test
    @DisplayName("the shipped order answers exactly as the old pass-parity rules did")
    void shippedOrderMatchesParity() {
        CycleLayout l = CycleLayoutTest.shipped();
        assertEquals(2, l.typeCount(Type.NETHER));
        assertEquals(2, l.typeCount(Type.END));
        WorldGenCycle c = cycle(l);
        for (long pass = -1; pass < 16; pass++) {
            assertEquals(BetterNetherCoreBiomes.isBetterNetherPass(pass), c.isBetterNetherPass(pass), "nether " + pass);
            assertEquals(EndBandStyle.isBetterEndPass(pass), c.isBetterEndPass(pass), "end " + pass);
        }
    }

    @Test
    @DisplayName("the classic (blank-order) layout keeps alternating by pass")
    void classicKeepsParity() {
        WorldGenCycle c = new WorldGenCycle(1000L, 300, 40, new int[] {1, 5, 20}, 0, 60, 50, 200, 100, 40, 200, 0, 0, 0, 0);
        assertFalse(c.hasLayout());
        for (long pass = -1; pass < 8; pass++) {
            assertEquals(BetterNetherCoreBiomes.isBetterNetherPass(pass), c.isBetterNetherPass(pass), "nether " + pass);
            assertEquals(EndBandStyle.isBetterEndPass(pass), c.isBetterEndPass(pass), "end " + pass);
        }
    }

    @Test
    @DisplayName("styleOfOccurrence reads the n-th slot of a type")
    void styleOfOccurrence() {
        CycleLayout l = layout("nether:better:3000, ow:1000, nether:3000");
        assertEquals(Style.BETTER, l.styleOfOccurrence(Type.NETHER, 0));
        assertEquals(Style.VANILLA, l.styleOfOccurrence(Type.NETHER, 1));
        assertEquals(null, l.styleOfOccurrence(Type.NETHER, 2));
        assertEquals(null, l.styleOfOccurrence(Type.END, 0));
    }
}
