package games.brennan.dungeontrain.worldgen;

import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DropLogTest {

    @Test
    void remembersTheLatestDropOfEachChunk() {
        DropLog log = new DropLog(16);
        long key = ChunkPos.asLong(3, 4);
        log.record(key, 100L);
        log.record(key, 300L);
        assertTrue(log.wasDropped(key));
        assertTrue(log.droppedSince(key, 300L));
        assertFalse(log.droppedSince(key, 301L));
        assertEquals(1, log.size());
        assertEquals(2L, log.total());
    }

    @Test
    void pastTheCapTheOldestAreForgotten() {
        DropLog log = new DropLog(8);
        for (int i = 0; i < 9; i++) log.record(ChunkPos.asLong(i, 0), i);
        assertTrue(log.size() <= 8);
        assertFalse(log.wasDropped(ChunkPos.asLong(0, 0)));
        assertFalse(log.wasDropped(ChunkPos.asLong(1, 0)));
        assertTrue(log.wasDropped(ChunkPos.asLong(8, 0)));
        assertEquals(9L, log.total());
    }

    @Test
    void dropsSharingOneTickStillRespectTheCap() {
        DropLog log = new DropLog(8);
        for (int i = 0; i < 50; i++) log.record(ChunkPos.asLong(i, 0), 5L);
        assertTrue(log.size() <= 8);
    }

    @Test
    void clearForgetsEverything() {
        DropLog log = new DropLog(8);
        log.record(ChunkPos.asLong(1, 1), 1L);
        log.clear();
        assertEquals(0, log.size());
        assertEquals(0L, log.total());
        assertThrows(IllegalArgumentException.class, () -> new DropLog(2));
    }
}
