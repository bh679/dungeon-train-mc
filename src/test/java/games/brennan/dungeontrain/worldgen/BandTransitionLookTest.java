package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.worldgen.SecondLapOverworld.Stretch;
import games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind;
import games.brennan.dungeontrain.worldgen.legacy.LegacySpan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SecondLapOverworld#lookAt}: the overworld-looking part of a band transition wears the look of
 * the modded overworld stretch it borders ({@link WorldGenCycle#bleedingOverworldStyleAt}); the
 * upside-down's only for the last ~28% of its Reassembly (u = 4133).
 *
 * <p>Base layout (u = blocks past the anchor, run 0), fades as {@code FADES}:</p>
 * <pre>
 *   ow           [0, 1000)
 *   upside_down  [1000, 5300)   mirror [1000, 2700), Reassembly [2700, 4700), exit gap [4700, 5300)
 *   ow:wwoo      [5300, 8300)
 *   nether       [8300, 12364)  entry side [8300, 8832), core [8832, 11832), exit side [11832, 12364)
 *   ow:bop       [12364, 15364)
 *   end          [15364, 19844) entry erosion [15364, 15484), exit erosion [19724, 19844)
 *   ow           [19844, 20844)
 * </pre>
 */
final class BandTransitionLookTest {

    private static final long START = 10_000L;
    private static final CycleLayout.Fades FADES = new CycleLayout.Fades(232, 0, 300, 120, 500, 600, 600, 10_000, 1500, 1500, 1500, 480);
    private static final String ORDER =
            "ow:1000, upside_down:500:2000, ow:wwoo:3000, nether:better:3000, ow:bop:3000, end:better:3000, ow:1000";

    private static LegacySpan[] eraDefaults() {
        List<LegacySpan> out = new ArrayList<>();
        for (LegacyBandKind k : LegacyBandKind.values()) out.add(new LegacySpan(k, 3000, 480, 6000));
        return out.toArray(new LegacySpan[0]);
    }

    private static WorldGenCycle cycle(String order) {
        List<String> warnings = new ArrayList<>();
        CycleLayout layout = CycleLayout.parse(order, FADES, eraDefaults(), t -> true, warnings::add);
        assertTrue(warnings.isEmpty(), warnings.toString());
        return new WorldGenCycle(START, 10_000, 40, new int[] {1, 2, 4, 8, 15}, 32, 0, 300, 5000,
                120, 500, 5000, 600, 5000, 600, 10_000, 8000, 1500, 5000, 0.3, 0.4, 12_000, 1500, 5000, 8000, 1500, 10_000, 0.08,
                eraDefaults(), layout, 0);
    }

    private static final WorldGenCycle C = cycle(ORDER);

    private static Stretch look(long u) {
        return SecondLapOverworld.lookAt(C, (int) (START + u));
    }

    private static Stretch own(long u) {
        return SecondLapOverworld.at(C, (int) (START + u));
    }

    @Test
    @DisplayName("the layout lands where the javadoc says — the modded stretches themselves")
    void layoutSanity() {
        assertEquals(Stretch.VANILLA, own(5299));
        assertEquals(Stretch.WWOO, own(5300));
        assertEquals(Stretch.WWOO, own(8299));
        assertEquals(Stretch.VANILLA, own(8300));
        assertEquals(Stretch.BOP, own(12364));
        assertEquals(Stretch.BOP, own(15363));
        assertEquals(Stretch.VANILLA, own(15364));
    }

    @Test
    @DisplayName("upside-down: the last ~28% of the Reassembly and the exit gap wear WWOO; the mirror does not")
    void upsideDownExit() {
        assertEquals(Stretch.VANILLA, look(1000));
        assertEquals(Stretch.VANILLA, look(2699));
        assertEquals(Stretch.VANILLA, look(2700)); // most of the Reassembly stays vanilla
        assertEquals(Stretch.VANILLA, look(4132));
        assertEquals(Stretch.WWOO, look(4133));
        assertEquals(Stretch.WWOO, look(4700));
        assertEquals(Stretch.WWOO, look(5299));
        assertEquals(Stretch.VANILLA, own(2700)); // the stretch itself is unchanged
    }

    @Test
    @DisplayName("Nether: the entry side wears WWOO, the exit side BoP, the core neither")
    void netherSides() {
        assertEquals(Stretch.WWOO, look(8300));
        assertEquals(Stretch.WWOO, look(8831));
        assertEquals(Stretch.VANILLA, look(8832));
        assertEquals(Stretch.VANILLA, look(11831));
        assertEquals(Stretch.BOP, look(11832));
        assertEquals(Stretch.BOP, look(12363));
    }

    @Test
    @DisplayName("End: the entry erosion wears BoP; the void, core and a vanilla-bordered exit do not")
    void endSides() {
        assertEquals(Stretch.BOP, look(15364));
        assertEquals(Stretch.BOP, look(15483));
        assertEquals(Stretch.VANILLA, look(15484));
        assertEquals(Stretch.VANILLA, look(17000));
        assertEquals(Stretch.VANILLA, look(19724));
        assertEquals(Stretch.VANILLA, look(19843));
    }

    @Test
    @DisplayName("plain gaps and bands between vanilla gaps stay vanilla")
    void vanillaNeighbours() {
        assertEquals(Stretch.VANILLA, look(0));
        assertEquals(Stretch.VANILLA, look(999));
        assertEquals(Stretch.VANILLA, look(20000));
        WorldGenCycle plain = cycle("ow:1000, nether:3000, ow:1000, upside_down:500:2000, ow:1000");
        for (long u = 0; u < 13_000; u += 7) {
            assertEquals(Stretch.VANILLA, SecondLapOverworld.lookAt(plain, (int) (START + u)), "u=" + u);
        }
    }

    @Test
    @DisplayName("a stretched run doubles every transition with its stretch")
    void stretchedRun() {
        long p = C.period();
        assertEquals(20_844L, p);
        long run1 = START + CycleLayout.runStart(1, p);
        assertEquals(Stretch.VANILLA, SecondLapOverworld.lookAt(C, (int) (run1 + (4132L << 1))));
        assertEquals(Stretch.WWOO, SecondLapOverworld.lookAt(C, (int) (run1 + (4133L << 1))));
        assertEquals(Stretch.WWOO, SecondLapOverworld.lookAt(C, (int) (run1 + (8831L << 1))));
        assertEquals(Stretch.VANILLA, SecondLapOverworld.lookAt(C, (int) (run1 + (8832L << 1))));
        assertEquals(Stretch.BOP, SecondLapOverworld.lookAt(C, (int) (run1 + (11832L << 1))));
    }
}
