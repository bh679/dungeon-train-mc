package games.brennan.dungeontrain.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class ChunkFootprintTest {

    private static final int MARGIN = 3;

    /** Every chunk is ready except the listed ones; records what was asked for. */
    private static final class Fake implements ChunkColumnCache.Resolver<Object> {
        final Set<String> missing;
        final List<String> asked = new ArrayList<>();

        Fake(String... missing) {
            this.missing = Set.of(missing);
        }

        @Override
        public Object getNow(int chunkX, int chunkZ) {
            String key = chunkX + "," + chunkZ;
            asked.add(key);
            return missing.contains(key) ? null : key;
        }
    }

    @Test
    void interiorColumnNeedsOnlyItsOwnChunk() {
        Fake fake = new Fake();
        assertTrue(ChunkFootprint.ready(fake, 8, 8, MARGIN));
        assertEquals(List.of("0,0"), fake.asked);
    }

    @Test
    void missingOwnChunkFails() {
        assertFalse(ChunkFootprint.ready(new Fake("0,0"), 8, 8, MARGIN));
    }

    @Test
    void edgeColumnChecksTheNeighbourOnThatAxis() {
        assertFalse(ChunkFootprint.ready(new Fake("-1,0"), 2, 8, MARGIN));
        assertFalse(ChunkFootprint.ready(new Fake("1,0"), 13, 8, MARGIN));
        assertFalse(ChunkFootprint.ready(new Fake("0,-1"), 8, 2, MARGIN));
        assertFalse(ChunkFootprint.ready(new Fake("0,1"), 8, 13, MARGIN));
        assertTrue(ChunkFootprint.ready(new Fake("-1,0", "0,-1"), 3, 3, MARGIN));
    }

    @Test
    void cornerColumnChecksAllFourChunks() {
        assertTrue(ChunkFootprint.ready(new Fake(), 15, 15, MARGIN));
        for (String missing : List.of("0,0", "1,0", "0,1", "1,1")) {
            assertFalse(ChunkFootprint.ready(new Fake(missing), 15, 15, MARGIN), missing);
        }
    }

    @Test
    void negativeCoordinatesFloorToTheRightChunk() {
        assertFalse(ChunkFootprint.ready(new Fake("-1,-1"), -8, -8, MARGIN));
        assertFalse(ChunkFootprint.ready(new Fake("-2,-1"), -16, -8, MARGIN));
        assertFalse(ChunkFootprint.ready(new Fake("0,-1"), -1, -8, MARGIN));
    }
}
