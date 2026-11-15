package games.brennan.dungeontrain.worldgen;

import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

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
}
