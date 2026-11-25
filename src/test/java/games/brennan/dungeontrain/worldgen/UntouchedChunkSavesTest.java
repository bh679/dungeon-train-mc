package games.brennan.dungeontrain.worldgen;

import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UntouchedChunkSavesTest {

    @Test
    void ordinaryTerrainIsDropped() {
        assertEquals(UntouchedChunkSaves.Verdict.DROP, UntouchedChunkSaves.terrainVerdict(false, false));
    }

    @Test
    void mirroredTerrainBehindSpawnIsKept() {
        assertEquals(UntouchedChunkSaves.Verdict.MIRRORED, UntouchedChunkSaves.terrainVerdict(true, false));
        assertEquals(UntouchedChunkSaves.Verdict.MIRRORED, UntouchedChunkSaves.terrainVerdict(true, true));
    }

    @Test
    void endIslandsAreKept() {
        assertEquals(UntouchedChunkSaves.Verdict.END_ISLANDS, UntouchedChunkSaves.terrainVerdict(false, true));
    }

    @Test
    void droppedSinceSeesOnlyDropsAtOrAfterTheGivenTime() {
        UntouchedChunkSaves.resetCounts();
        long key = ChunkPos.asLong(596, 0);
        assertFalse(UntouchedChunkSaves.droppedSince(key, 0L));
        UntouchedChunkSaves.recordDrop(key, 500L);
        assertTrue(UntouchedChunkSaves.droppedSince(key, 400L));
        assertTrue(UntouchedChunkSaves.droppedSince(key, 500L));
        // Stamped after the drop: what stands there now is the room, written since.
        assertFalse(UntouchedChunkSaves.droppedSince(key, 501L));
        assertFalse(UntouchedChunkSaves.droppedSince(ChunkPos.asLong(597, 0), 0L));
        UntouchedChunkSaves.resetCounts();
        assertFalse(UntouchedChunkSaves.droppedSince(key, 0L));
    }

    @Test
    void nearIsASquareOfKeepChunksAroundThePlayer() {
        ChunkPos pos = new ChunkPos(100, -20);
        assertTrue(UntouchedChunkSaves.isNear(100, -20, pos, 14));
        assertTrue(UntouchedChunkSaves.isNear(86, -6, pos, 14));
        assertTrue(UntouchedChunkSaves.isNear(114, -34, pos, 14));
        assertFalse(UntouchedChunkSaves.isNear(85, -20, pos, 14));
        assertFalse(UntouchedChunkSaves.isNear(100, -35, pos, 14));
    }

    @Test
    void noPlayersMeansNotNear() {
        assertFalse(UntouchedChunkSaves.nearAnySpot(new long[0], new ChunkPos(0, 0), 14));
    }

    @Test
    void anySpotWithinKeepIsNear() {
        long[] spots = {ChunkPos.asLong(0, 0), ChunkPos.asLong(500, 3)};
        assertTrue(UntouchedChunkSaves.nearAnySpot(spots, new ChunkPos(510, 0), 14));
        assertFalse(UntouchedChunkSaves.nearAnySpot(spots, new ChunkPos(250, 0), 14));
    }

    @Test
    void touchedAfterACachedDropIsKept() {
        // The terrain says DROP and is cached; a player then changes the chunk.
        assertEquals(UntouchedChunkSaves.Verdict.DROP,
                UntouchedChunkSaves.liveVerdict(false, false, false, () -> UntouchedChunkSaves.Verdict.DROP));
        assertEquals(UntouchedChunkSaves.Verdict.TOUCHED,
                UntouchedChunkSaves.liveVerdict(true, false, false, () -> UntouchedChunkSaves.Verdict.DROP));
    }

    @Test
    void heldOrNearAtStopAfterACachedDropIsKept() {
        assertEquals(UntouchedChunkSaves.Verdict.PORTAL_ROOM,
                UntouchedChunkSaves.liveVerdict(false, true, false, () -> UntouchedChunkSaves.Verdict.DROP));
        assertEquals(UntouchedChunkSaves.Verdict.NEAR_PLAYER,
                UntouchedChunkSaves.liveVerdict(false, false, true, () -> UntouchedChunkSaves.Verdict.DROP));
    }

    @Test
    void liveFactsDoNotWorkOutTheTerrain() {
        AtomicInteger asked = new AtomicInteger();
        UntouchedChunkSaves.liveVerdict(true, false, false, () -> {
            asked.incrementAndGet();
            return UntouchedChunkSaves.Verdict.DROP;
        });
        assertEquals(0, asked.get());
    }

    @Test
    void onlyASkipAsTheChunkUnloadsIsADrop() {
        UntouchedChunkSaves.resetCounts();
        long key = ChunkPos.asLong(800, 1);
        // Periodic skips: the chunk is still in memory, unsaved, and will be judged again.
        for (long tick = 0; tick < 100; tick++) {
            assertTrue(UntouchedChunkSaves.settle(UntouchedChunkSaves.Verdict.DROP, false, key, tick));
        }
        assertFalse(UntouchedChunkSaves.wasDropped(key));
        assertFalse(UntouchedChunkSaves.droppedSince(key, 0L));
        // The unload save is skipped too: now it is really gone.
        assertTrue(UntouchedChunkSaves.settle(UntouchedChunkSaves.Verdict.DROP, true, key, 150L));
        assertTrue(UntouchedChunkSaves.wasDropped(key));
        assertTrue(UntouchedChunkSaves.droppedSince(key, 120L));
        assertEquals(1L, UntouchedChunkSaves.dropped());
        UntouchedChunkSaves.resetCounts();
    }

    @Test
    void aKeptChunkIsNeverADrop() {
        UntouchedChunkSaves.resetCounts();
        long key = ChunkPos.asLong(801, 1);
        assertFalse(UntouchedChunkSaves.settle(UntouchedChunkSaves.Verdict.TOUCHED, true, key, 10L));
        assertFalse(UntouchedChunkSaves.wasDropped(key));
        assertEquals(1L, UntouchedChunkSaves.kept());
        UntouchedChunkSaves.resetCounts();
    }
}
