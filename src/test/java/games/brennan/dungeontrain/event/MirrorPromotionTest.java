package games.brennan.dungeontrain.event;

import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongPredicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link WorldUpsideDownEvents#promotableKeys} — the waiting→ready promotion
 * decision behind the deferred-mirror drain's CPU win. The drain iterates only the READY set, so this
 * predicate is exactly what keeps the un-appliable frontier ring out of the per-tick scan while never
 * permanently stranding a chunk. Tested in the codebase's pure static-helper style (no live server);
 * the full drain/apply behaviour (demotion, backstop independence, exactly-once, terrain identity) is
 * validated in-game per {@link games.brennan.dungeontrain.worldgen.UpsideDownMirrorTest}'s convention.
 */
final class MirrorPromotionTest {

    private static long key(int x, int z) {
        return ChunkPos.asLong(x, z);
    }

    /** Mirrors {@code WorldUpsideDownEvents.neighboursFull}: a key is full iff it + all 8 neighbours are loaded. */
    private static LongPredicate neighbourhoodFull(Set<Long> loaded) {
        return k -> {
            int cx = ChunkPos.getX(k), cz = ChunkPos.getZ(k);
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (!loaded.contains(key(cx + dx, cz + dz))) return false;
                }
            }
            return true;
        };
    }

    @Test
    @DisplayName("a pending chunk is promoted exactly when its 3x3 neighbourhood completes, never before")
    void promotesWhenNeighbourhoodCompletes() {
        long p = key(0, 0);
        Set<Long> pending = new HashSet<>(Set.of(p));
        Set<Long> ready = new HashSet<>();
        Set<Long> loaded = new HashSet<>();

        // Load 8 of 9 neighbours; the (1,1) corner is still missing, so p cannot be ready.
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (!(dx == 1 && dz == 1)) loaded.add(key(dx, dz));
            }
        }
        // A load event anywhere in p's 3x3 must NOT promote p yet.
        assertFalse(WorldUpsideDownEvents.promotableKeys(0, 0,
                pending::contains, ready::contains, neighbourhoodFull(loaded)).contains(p),
                "must not promote before neighbourhood complete");

        // The final neighbour loads → its ChunkEvent.Load is centred on (1,1); p is in that 3x3.
        loaded.add(key(1, 1));
        assertTrue(WorldUpsideDownEvents.promotableKeys(1, 1,
                pending::contains, ready::contains, neighbourhoodFull(loaded)).contains(p),
                "must promote once the last neighbour loads");
    }

    @Test
    @DisplayName("a frontier chunk with a permanently-unloaded neighbour is never promoted (CPU-win guard)")
    void frontierNeverPromoted() {
        long p = key(0, 0);
        Set<Long> pending = new HashSet<>(Set.of(p));
        Set<Long> ready = new HashSet<>();
        Set<Long> loaded = new HashSet<>();
        // Everything in p's 3x3 loaded EXCEPT the outer (1,0) neighbour — the sim-distance frontier edge.
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (!(dx == 1 && dz == 0)) loaded.add(key(dx, dz));
            }
        }
        // No load event anywhere in the neighbourhood can promote p while (1,0) stays unloaded.
        for (int cz = -1; cz <= 1; cz++) {
            for (int cx = -1; cx <= 1; cx++) {
                assertFalse(WorldUpsideDownEvents.promotableKeys(cx, cz,
                        pending::contains, ready::contains, neighbourhoodFull(loaded)).contains(p),
                        "frontier chunk must stay in WAITING (never re-scanned per tick)");
            }
        }
    }

    @Test
    @DisplayName("an already-ready chunk is not returned again, and a non-pending chunk is never promoted")
    void dedupAndNonPending() {
        long p = key(0, 0);
        Set<Long> loaded = new HashSet<>();
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                loaded.add(key(dx, dz));                       // fully loaded neighbourhood
            }
        }
        // Already ready → not returned again (no duplicate promotion work).
        assertFalse(WorldUpsideDownEvents.promotableKeys(0, 0,
                Set.of(p)::contains, Set.of(p)::contains, neighbourhoodFull(loaded)).contains(p),
                "already-ready chunk must not be re-promoted");
        // Not pending (unmarked) → never promoted even with a full neighbourhood.
        assertTrue(WorldUpsideDownEvents.promotableKeys(0, 0,
                new HashSet<Long>()::contains, new HashSet<Long>()::contains, neighbourhoodFull(loaded)).isEmpty(),
                "nothing pending → nothing promoted");
    }
}
