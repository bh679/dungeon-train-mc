package games.brennan.dungeontrain.portal.chunkframe;

import net.minecraft.core.Vec3i;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkFrameTest {

    @Test
    @DisplayName("A frame is the room plus one block on every side, starting one block outside it")
    void sizeAndOffset() {
        assertEquals(new Vec3i(16, 32, 16), ChunkFrame.ROOM_SIZE);
        assertEquals(new Vec3i(18, 34, 18), ChunkFrame.SIZE);
        assertEquals(new Vec3i(-1, -1, -1), ChunkFrame.OFFSET);
    }

    @Test
    @DisplayName("The shell is exactly the cells outside the room box; everything else is the room")
    void shellIsOutsideTheBox() {
        int shell = 0;
        Vec3i s = ChunkFrame.SIZE;
        for (int x = 0; x < s.getX(); x++) {
            for (int y = 0; y < s.getY(); y++) {
                for (int z = 0; z < s.getZ(); z++) {
                    int rx = x + ChunkFrame.OFFSET.getX(), ry = y + ChunkFrame.OFFSET.getY(), rz = z + ChunkFrame.OFFSET.getZ();
                    boolean outside = rx < 0 || ry < 0 || rz < 0 || rx >= 16 || ry >= 32 || rz >= 16;
                    assertEquals(outside, ChunkFrame.isShell(x, y, z), x + "," + y + "," + z);
                    if (outside) shell++;
                }
            }
        }
        assertEquals(18 * 34 * 18 - 16 * 32 * 16, shell);
    }

    @Test
    @DisplayName("A room's pick is by weight, the same for the same seed, and none when it has none")
    void pickIsWeightedAndStable() {
        assertNull(ChunkRoomFrames.EMPTY.pick(42));
        ChunkRoomFrames frames = ChunkRoomFrames.EMPTY.with("a", 1).with("b", 3);
        Map<String, Integer> counts = new HashMap<>();
        for (long seed = 0; seed < 4000; seed++) counts.merge(frames.pick(seed), 1, Integer::sum);
        assertTrue(counts.get("b") > 2 * counts.get("a"), "b should come up about three times as often: " + counts);
        assertEquals(frames.pick(7), frames.pick(7));
    }

    @Test
    @DisplayName("Editing a room's list keeps order, sets weights in place, and round-trips through JSON")
    void editsAndJson() {
        ChunkRoomFrames frames = ChunkRoomFrames.EMPTY.with("a", 1).with("b", 2).with("a", 5);
        assertEquals("a", frames.entries().get(0).name());
        assertEquals(5, frames.entries().get(0).weight());
        assertEquals(2, frames.entries().size());
        assertEquals(frames, ChunkRoomFrames.fromJson(frames.toJson()));
        assertFalse(frames.without("a").entries().stream().anyMatch(e -> e.name().equals("a")));
        assertEquals(ChunkRoomFrames.MAX_WEIGHT, ChunkRoomFrames.EMPTY.with("c", 999).entries().get(0).weight());
    }
}
