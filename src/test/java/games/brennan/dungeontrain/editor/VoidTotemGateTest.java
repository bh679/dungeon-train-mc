package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.worldgen.CycleLayout;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind;
import games.brennan.dungeontrain.worldgen.legacy.LegacySpan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link VoidTotemGate}: no Void Totem until the first End band, then from there on. */
final class VoidTotemGateTest {

    private static final long START = 10_000L;
    private static final CycleLayout.Fades FADES =
        new CycleLayout.Fades(232, 0, 300, 120, 500, 600, 600, 10_000, 1500, 1500, 1500, 480);

    private static WorldGenCycle shippedCycle() {
        List<LegacySpan> eras = new ArrayList<>();
        for (LegacyBandKind k : LegacyBandKind.values()) eras.add(new LegacySpan(k, 3000, 480, 6000));
        LegacySpan[] spans = eras.toArray(new LegacySpan[0]);
        List<String> warnings = new ArrayList<>();
        CycleLayout layout = CycleLayout.parse(CycleLayout.DEFAULT_ORDER, FADES, spans, t -> true, warnings::add);
        assertTrue(warnings.isEmpty(), warnings.toString());
        return new WorldGenCycle(START, 10_000, 40, new int[] {1, 2, 4, 8, 15}, 32, 0, 300, 5000,
            120, 500, 5000, 600, 5000, 600, 10_000, 8000, 1500, 5000, 0.3, 0.4, 12_000, 1500, 5000, 8000, 1500, 10_000, 0.08,
            spans, layout, 0);
    }

    @Test
    @DisplayName("not reached through the overworld, Nether and WWOO; reached by the first End band, and stays reached")
    void reachedAtTheFirstEndBand() {
        WorldGenCycle c = shippedCycle();
        int firstEnd = -1;
        for (int x = 0; x < 200_000; x += 50) {
            if (c.endStyleAt(x) != null) { firstEnd = x; break; }
        }
        assertTrue(firstEnd > START, "the shipped layout should have an End band");
        assertFalse(VoidTotemGate.reached(c, 0));
        assertFalse(VoidTotemGate.reached(c, (int) START + 100));
        assertFalse(c.isNetherCore(firstEnd), "sanity");
        boolean seen = false;
        for (int x = 0; x < firstEnd + 300_000; x += 97) {
            boolean r = VoidTotemGate.reached(c, x);
            if (seen) assertTrue(r, "un-reached the void at x=" + x);
            seen |= r;
            if (x < firstEnd - 20_000) assertFalse(r, "reached the void early at x=" + x);
        }
        assertTrue(VoidTotemGate.reached(c, firstEnd));
    }
}
