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
