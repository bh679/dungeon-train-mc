package games.brennan.dungeontrain.worldgen.structure;

import games.brennan.dungeontrain.worldgen.NetherBandBiomes;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins {@link AncientCitySite}: one region per pass, past the core, at the track's region-Z; 50 % on pass 0. */
final class AncientCitySiteTest {

    private static final WorldGenCycle CYCLE =
            new WorldGenCycle(1000L, 300, 40, new int[] {1, 5, 20}, 0, 60, 50, 200, 100, 40, 200, 0, 0, 0, 0);
    private static final int SEA = 63, CEILING = 320, NETHER_TOP = 40, RELIEF = 100, BED_Y = 76;

    private static long cell(long seed, long pass) {
        return AncientCitySite.cellX(CYCLE, seed, pass, SEA, CEILING, NETHER_TOP, RELIEF, BED_Y);
    }

    @Test
    @DisplayName("pass 0 has a city about half the time; passes 1+ always")
    void chance() {
        int seeds = 400, pass0 = 0, pass1 = 0;
        for (int i = 0; i < seeds; i++) {
            long seed = 0x1234_5678L + i * 0x9E37L;
            if (cell(seed, 0) != AncientCitySite.NONE) pass0++;
            if (cell(seed, 1) != AncientCitySite.NONE) pass1++;
        }
        assertTrue(pass0 > seeds * 0.4 && pass0 < seeds * 0.6, "pass 0 rate " + pass0 + "/" + seeds);
        assertEquals(seeds, pass1, "pass 1 must always have a city");
        assertEquals(seeds, seeds - 0, "sanity");
    }

    @Test
    @DisplayName("the chosen region lies wholly past the core, inside the pass, and the pick is deterministic")
    void placement() {
        int shift = NetherBandBiomes.CAVE_REGION_SHIFT;
        for (int i = 0; i < 50; i++) {
            long seed = 77L + i;
            for (long pass : new long[] {0, 1, 2}) {
                long c = cell(seed, pass);
                assertEquals(c, cell(seed, pass), "non-deterministic");
                if (c == AncientCitySite.NONE) continue;
                long[] range = CYCLE.netherPassRange((int) pass);
                int x0 = (int) (c << shift), x1 = x0 + (1 << shift) - 1;
                assertTrue(x0 >= range[0] && x1 < range[1], "region outside the pass at seed " + seed);
                assertTrue(CYCLE.netherPastCore(x0) && CYCLE.netherPastCore(x1), "region not past the core");
                assertEquals(x0 + (1 << (shift - 1)), AncientCitySite.centreX(c));
            }
        }
        assertEquals((1 << (NetherBandBiomes.CAVE_REGION_SHIFT - 1)), AncientCitySite.centreZ());
    }
}
