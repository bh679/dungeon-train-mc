package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind;
import games.brennan.dungeontrain.worldgen.legacy.LegacyBands;
import games.brennan.dungeontrain.worldgen.legacy.LegacySpan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sunk zone on the shipped layout: the short {@code ow:sunk} approach and the Amplified slot behind it
 * form one unbroken stretch, right after the spheres band — so nothing between the two sits at stock height.
 * Pure {@link WorldGenCycle}, built the same way {@code BandLocatorTest} builds it.
 */
final class SunkZoneTest {

    private static final long START = 10_000L;
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
    @DisplayName("shipped layout: one unbroken sunk stretch — a 500-block approach, then Amplified's slot")
    void shippedLayoutHasOneSunkStretch() {
        WorldGenCycle c = cycle(CycleLayout.DEFAULT_ORDER);
        int from = (int) START;
        int to = (int) (START + 200_000);
        int runs = 0, sunkApproach = 0, first = Integer.MIN_VALUE, last = Integer.MIN_VALUE;
        boolean prev = false;
        for (int x = from; x < to; x++) {
            boolean in = SunkZone.contains(c, x);
            if (in && !prev) {
                runs++;
                if (first == Integer.MIN_VALUE) first = x;
            }
            if (in && runs == 1) last = x;
            if (runs == 1 && c.overworldStyleAt(x) == CycleLayout.Style.SUNK) sunkApproach++;
            prev = in;
            if (runs > 1) break;
        }
        assertTrue(runs >= 1, "the shipped layout has a sunk stretch");
        assertEquals(500, sunkApproach, "the approach is 500 blocks");
        assertTrue(c.isInSpheresBand(first - 1) || c.isInSpheresFade(first - 1),
                "the stretch starts where the spheres band ends");
        assertTrue(LegacyBands.isInSlot(c, LegacyBandKind.AMPLIFIED, last), "it ends on Amplified's slot");
        assertFalse(SunkZone.contains(c, last + 1));
        for (int x = first; x <= last; x++) {
            assertTrue(SunkZone.contains(c, x), "no stock-height gap inside the stretch at " + x);
        }
    }

    @Test
    @DisplayName("a plain ow gap is not sunk; the 'sunk' style parses")
    void styleParses() {
        WorldGenCycle c = cycle("ow:1000, ow:sunk:500, ow:1000");
        assertFalse(SunkZone.contains(c, (int) START + 500));
        assertTrue(SunkZone.contains(c, (int) START + 1250));
        assertFalse(SunkZone.contains(c, (int) START + 2000));
    }
}
