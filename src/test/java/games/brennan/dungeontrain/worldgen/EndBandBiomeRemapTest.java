package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The band's vanilla→BetterEnd pick: deterministic, clumpy, covering, and ordered by id — no registry needed. */
class EndBandBiomeRemapTest {

    private static final long SEED = 424242L;

    @Test
    @DisplayName("the same seed and quart always pick the same entry; another seed picks differently somewhere")
    void pickIsDeterministic() {
        boolean differs = false;
        for (int qx = -300; qx < 300; qx += 7) {
            for (int qz = -300; qz < 300; qz += 11) {
                int a = EndBandBiomeRemap.pickIndex(SEED, qx, qz, 16);
                assertEquals(a, EndBandBiomeRemap.pickIndex(SEED, qx, qz, 16));
                if (a != EndBandBiomeRemap.pickIndex(SEED + 1, qx, qz, 16)) differs = true;
            }
        }
        assertTrue(differs, "a different seed should lay the biomes out differently");
    }

    @Test
    @DisplayName("picks come in clumps: few neighbouring quarts differ")
    void pickIsSpatiallyCoherent() {
        int changes = 0;
        int pairs = 0;
        for (int qx = 0; qx < 256; qx++) {
            for (int qz = 0; qz < 256; qz++) {
                int here = EndBandBiomeRemap.pickIndex(SEED, qx, qz, 16);
                if (qx > 0 && here != EndBandBiomeRemap.pickIndex(SEED, qx - 1, qz, 16)) changes++;
                if (qx > 0) pairs++;
            }
        }
        double fraction = changes / (double) pairs;
        assertTrue(fraction < 0.05, "expected < 5% of +x neighbours to differ, got " + fraction);
        assertTrue(fraction > 0.0, "expected some borders inside a 256-quart window");
    }

    @Test
    @DisplayName("every pool entry is picked somewhere and never one outside the pool")
    void pickCoversThePool() {
        for (int n : new int[] {1, 2, 3, 16}) {
            Set<Integer> seen = new HashSet<>();
            for (int qx = -1024; qx < 1024; qx += 16) {
                for (int qz = -1024; qz < 1024; qz += 16) {
                    int i = EndBandBiomeRemap.pickIndex(SEED, qx, qz, n);
                    assertTrue(i >= 0 && i < n, "index " + i + " outside pool of " + n);
                    seen.add(i);
                }
            }
            assertEquals(n, seen.size(), "every entry of a pool of " + n + " should be used");
        }
    }

    @Test
    @DisplayName("a single-entry pool always picks it")
    void singleEntryPool() {
        assertEquals(0, EndBandBiomeRemap.pickIndex(SEED, 12345, -678, 1));
    }

    @Test
    @DisplayName("tables sort by id whatever order the registry handed them in")
    void tableOrderIsIndependentOfInput() {
        List<String> ids = new ArrayList<>(List.of("betterend:shadow_forest", "betterend:amber_land",
                "betterend:umbrella_jungle", "betterend:ice_starfield", "betterend:flower_islets",
                "minecraft:end_barrens", "biomesoplenty:end_wilds"));
        Set<String> voids = Set.of("betterend:ice_starfield", "betterend:flower_islets");
        EndBandBiomeRemap.Table<String> first = table(ids, voids);
        Collections.shuffle(ids, new Random(7));
        EndBandBiomeRemap.Table<String> shuffled = table(ids, voids);
        assertEquals(first, shuffled);
        assertEquals(List.of("betterend:amber_land", "betterend:shadow_forest", "betterend:umbrella_jungle"), first.land());
        assertEquals(List.of("betterend:flower_islets", "betterend:ice_starfield"), first.voids());
    }

    @Test
    @DisplayName("an empty table is empty; a half-empty one is not")
    void emptiness() {
        assertTrue(table(List.of(), Set.of()).isEmpty());
        assertTrue(!table(List.of("betterend:amber_land"), Set.of()).isEmpty());
        assertNotEquals(table(List.of("betterend:amber_land"), Set.of()), table(List.of(), Set.of()));
    }

    /** Land = any betterend id not in {@code voids}; void = the ids in {@code voids}; everything else dropped. */
    private static EndBandBiomeRemap.Table<String> table(List<String> ids, Set<String> voids) {
        return EndBandBiomeRemap.Table.of(ids, id -> id,
                id -> id.startsWith("betterend:") && !voids.contains(id),
                voids::contains);
    }
}
