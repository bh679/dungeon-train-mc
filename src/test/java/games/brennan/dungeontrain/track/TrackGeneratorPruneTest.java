package games.brennan.dungeontrain.track;

import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The X window a tail provider keeps remembering chunks for; everything behind it is dropped. */
final class TrackGeneratorPruneTest {

    @Test
    @DisplayName("keys within the window stay, the rest go, and the count is reported")
    void windowKeepsNearDropsFar() {
        Set<Long> filled = new HashSet<>();
        for (int cx = -100; cx <= 100; cx++) filled.add(ChunkPos.asLong(cx, 0));
        int dropped = TrackGenerator.pruneOutsideX(filled, 50, 10);
        assertEquals(201 - 21, dropped);
        assertEquals(21, filled.size());
        assertTrue(filled.contains(ChunkPos.asLong(40, 0)));
        assertTrue(filled.contains(ChunkPos.asLong(60, 0)));
        assertFalse(filled.contains(ChunkPos.asLong(39, 0)));
        assertFalse(filled.contains(ChunkPos.asLong(61, 0)));
    }

    @Test
    @DisplayName("works on the pending queue as well, and is a no-op inside the window")
    void queueAndNoOp() {
        Deque<Long> pending = new ArrayDeque<>();
        pending.offer(ChunkPos.asLong(0, 3));
        pending.offer(ChunkPos.asLong(-500, 3));
        assertEquals(1, TrackGenerator.pruneOutsideX(pending, 0, 26));
        assertEquals(0, TrackGenerator.pruneOutsideX(pending, 0, 26));
        assertEquals(1, pending.size());
    }
}
