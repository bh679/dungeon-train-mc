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
 * upside-down's only for the last ~28% of its Reassembly (u = 3533).
 *
 * <p>Base layout (u = blocks past the anchor, run 0), fades as {@code FADES}:</p>
 * <pre>
 *   ow           [0, 1000)
 *   upside_down  [1000, 4700)   mirror [1000, 2100) (entry fade + core, no trailing fade before a Reassembly),
 *                               Reassembly [2100, 4100), exit gap [4100, 4700)
 *   ow:wwoo      [4700, 7700)
 *   nether       [7700, 11764)  entry side [7700, 8232), core [8232, 11232), exit side [11232, 11764)
 *   ow:bop       [11764, 14764)
 *   end          [14764, 19244) entry erosion [14764, 14884), exit erosion [19124, 19244)
 *   ow           [19244, 20244)
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
        assertEquals(Stretch.VANILLA, own(4699));
        assertEquals(Stretch.WWOO, own(4700));
        assertEquals(Stretch.WWOO, own(7699));
        assertEquals(Stretch.VANILLA, own(7700));
        assertEquals(Stretch.BOP, own(11764));
        assertEquals(Stretch.BOP, own(14763));
        assertEquals(Stretch.VANILLA, own(14764));
    }

    @Test
    @DisplayName("upside-down: the last ~28% of the Reassembly and the exit gap wear WWOO; the mirror does not")
    void upsideDownExit() {
        assertEquals(Stretch.VANILLA, look(1000));
        assertEquals(Stretch.VANILLA, look(2099));
        assertEquals(Stretch.VANILLA, look(2100)); // most of the Reassembly stays vanilla
        assertEquals(Stretch.VANILLA, look(3532));
        assertEquals(Stretch.WWOO, look(3533));
        assertEquals(Stretch.WWOO, look(4100));
        assertEquals(Stretch.WWOO, look(4699));
        assertEquals(Stretch.VANILLA, own(2100)); // the stretch itself is unchanged
    }

    @Test
    @DisplayName("upside-down: the WWOO start is a fraction of that Reassembly's own length, not a fixed X")
    void upsideDownStartFollowsReassemblyLength() {
        // Same band, Reassembly 3000 instead of 2000: mirror [1000, 2100), Reassembly [2100, 5100).
        WorldGenCycle longer = cycle("ow:1000, upside_down:500:3000, ow:wwoo:3000, ow:1000");
        long start = 2100L + Math.round(3000 * WorldGenCycle.UD_BLEED_REASSEMBLY_FRACTION);
        assertEquals(Stretch.VANILLA, SecondLapOverworld.lookAt(longer, (int) (START + start - 1)));
        assertEquals(Stretch.WWOO, SecondLapOverworld.lookAt(longer, (int) (START + start)));
    }

    @Test
    @DisplayName("Nether: the entry side wears WWOO, the exit side BoP, the core neither")
    void netherSides() {
        assertEquals(Stretch.WWOO, look(7700));
        assertEquals(Stretch.WWOO, look(8231));
        assertEquals(Stretch.VANILLA, look(8232));
        assertEquals(Stretch.VANILLA, look(11231));
        assertEquals(Stretch.BOP, look(11232));
        assertEquals(Stretch.BOP, look(11763));
    }

    @Test
    @DisplayName("End: the entry erosion wears BoP; the void, core and a vanilla-bordered exit do not")
    void endSides() {
        assertEquals(Stretch.BOP, look(14764));
        assertEquals(Stretch.BOP, look(14883));
        assertEquals(Stretch.VANILLA, look(14884));
        assertEquals(Stretch.VANILLA, look(16400));
        assertEquals(Stretch.VANILLA, look(19124));
        assertEquals(Stretch.VANILLA, look(19243));
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
        assertEquals(20_244L, p);
        long run1 = START + CycleLayout.runStart(1, p);
        assertEquals(Stretch.VANILLA, SecondLapOverworld.lookAt(C, (int) (run1 + (3532L << 1))));
        assertEquals(Stretch.WWOO, SecondLapOverworld.lookAt(C, (int) (run1 + (3533L << 1))));
        assertEquals(Stretch.WWOO, SecondLapOverworld.lookAt(C, (int) (run1 + (8231L << 1))));
        assertEquals(Stretch.VANILLA, SecondLapOverworld.lookAt(C, (int) (run1 + (8232L << 1))));
        assertEquals(Stretch.BOP, SecondLapOverworld.lookAt(C, (int) (run1 + (11232L << 1))));
    }
}
