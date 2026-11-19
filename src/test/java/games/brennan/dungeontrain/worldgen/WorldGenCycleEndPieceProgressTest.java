package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind;
import games.brennan.dungeontrain.worldgen.legacy.LegacySpan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link WorldGenCycle#endPieceProgress}: 0 → 1 across a vanilla End piece's core, -1 elsewhere. */
class WorldGenCycleEndPieceProgressTest {

    private static final long START = 1000L;
    private static final CycleLayout.Fades FADES =
            new CycleLayout.Fades(232, 0, 300, 120, 500, 600, 600, 10_000, 1500, 1500, 1500, 480);

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

    @Test
    @DisplayName("vanilla piece: 0 at its first core column, 1 at its last, clamped in the fades")
    void progressSpansTheCore() {
        WorldGenCycle c = cycle("ow:1000, end:vanilla:1200, end:bop:2000, ow:1000");
        int firstCore = -1;
        int lastCore = -1;
        for (int x = (int) START; x < START + 8000; x++) {
            if (c.isEndCore(x) && c.endStyleOfPass(c.endPassIndex(x)) == CycleLayout.Style.VANILLA) {
                if (firstCore < 0) firstCore = x;
                lastCore = x;
            }
        }
        assertTrue(firstCore > 0, "expected a vanilla End core");
        long pass0 = c.endPassIndex(firstCore);
        assertEquals(0.0, c.endPieceProgress(firstCore, pass0), 1.0 / 1200);
        assertEquals(1.0, c.endPieceProgress(lastCore, pass0), 2.0 / 1200);
        assertEquals(0.5, c.endPieceProgress((firstCore + lastCore) / 2, pass0), 2.0 / 1200);
        // leading void fade: clamped to 0 (small islands); never -1 inside the band
        assertEquals(0.0, c.endPieceProgress(firstCore - 50, pass0));
        // the BoP piece after it measures against its own core, from 0 again
        int bopStart = lastCore + 1;
        long pass1 = c.endPassIndex(bopStart);
        assertEquals(1L, pass1);
        assertTrue(c.endPieceProgress(bopStart, pass1) < 0.01);
        // a seam column owned by the vanilla pass still reads the vanilla piece's END (1.0), not the BoP start
        assertEquals(1.0, c.endPieceProgress(bopStart + 10, pass0));
    }

    @Test
    @DisplayName("-1 outside the cycle and without an End slot")
    void minusOneOutside() {
        WorldGenCycle c = cycle("ow:1000, end:vanilla:1200, ow:1000");
        assertEquals(-1.0, c.endPieceProgress((int) START - 500, 0L));
        assertEquals(-1.0, c.endPieceProgress((int) START + 100, -1L));
        WorldGenCycle noEnd = cycle("ow:1000, nether:3000, ow:1000");
        assertEquals(-1.0, noEnd.endPieceProgress((int) START + 2000, 0L));
    }
}
