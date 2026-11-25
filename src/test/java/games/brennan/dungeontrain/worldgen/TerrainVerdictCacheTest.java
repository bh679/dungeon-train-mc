package games.brennan.dungeontrain.worldgen;

import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TerrainVerdictCacheTest {

    private static final long KEY = ChunkPos.asLong(700, 2);

    @Test
    void terrainIsWorkedOutOnceAcrossManySaveAttempts() {
        TerrainVerdictCache cache = new TerrainVerdictCache();
        AtomicInteger worked = new AtomicInteger();
        for (int tick = 0; tick < 200; tick++) {
            assertEquals(UntouchedChunkSaves.Verdict.DROP, cache.get(KEY, TerrainVerdictCache.Use.REMEMBER, k -> {
                worked.incrementAndGet();
                return UntouchedChunkSaves.Verdict.DROP;
            }));
        }
        assertEquals(1, worked.get());
        assertEquals(199L, cache.hits());
        assertEquals(1L, cache.misses());
    }

    @Test
    void everyVerdictRoundTrips() {
        TerrainVerdictCache cache = new TerrainVerdictCache();
        for (UntouchedChunkSaves.Verdict verdict : UntouchedChunkSaves.Verdict.values()) {
            long key = ChunkPos.asLong(verdict.ordinal(), 0);
            cache.get(key, TerrainVerdictCache.Use.REMEMBER, k -> verdict);
            assertEquals(verdict, cache.get(key, TerrainVerdictCache.Use.REMEMBER, k -> UntouchedChunkSaves.Verdict.OFF));
        }
    }

    @Test
    void unloadForgetsTheChunk() {
        TerrainVerdictCache cache = new TerrainVerdictCache();
        cache.get(KEY, TerrainVerdictCache.Use.REMEMBER, k -> UntouchedChunkSaves.Verdict.MIRRORED);
        cache.forget(KEY);
        assertEquals(0, cache.size());
        assertEquals(UntouchedChunkSaves.Verdict.DROP, cache.get(KEY, TerrainVerdictCache.Use.REMEMBER, k -> UntouchedChunkSaves.Verdict.DROP));
    }

    @Test
    void aPeekDoesNotFillTheCache() {
        TerrainVerdictCache cache = new TerrainVerdictCache();
        cache.get(KEY, TerrainVerdictCache.Use.PEEK, k -> UntouchedChunkSaves.Verdict.DROP);
        assertEquals(0, cache.size());
    }

    @Test
    void clearEmptiesEverything() {
        TerrainVerdictCache cache = new TerrainVerdictCache();
        cache.get(KEY, TerrainVerdictCache.Use.REMEMBER, k -> UntouchedChunkSaves.Verdict.DROP);
        cache.get(KEY, TerrainVerdictCache.Use.REMEMBER, k -> UntouchedChunkSaves.Verdict.DROP);
        cache.clear();
        assertEquals(0, cache.size());
        assertEquals(0L, cache.hits());
        assertEquals(0L, cache.misses());
    }

    @Test
    void theUnloadSaveWorksTheTerrainOutFresh() {
        TerrainVerdictCache cache = new TerrainVerdictCache();
        cache.get(KEY, TerrainVerdictCache.Use.REMEMBER, k -> UntouchedChunkSaves.Verdict.DROP);
        assertEquals(UntouchedChunkSaves.Verdict.MIRRORED,
                cache.get(KEY, TerrainVerdictCache.Use.FRESH, k -> UntouchedChunkSaves.Verdict.MIRRORED));
        // ...and does not overwrite what periodic saves read.
        assertEquals(UntouchedChunkSaves.Verdict.DROP,
                cache.get(KEY, TerrainVerdictCache.Use.REMEMBER, k -> UntouchedChunkSaves.Verdict.OFF));
    }
}
