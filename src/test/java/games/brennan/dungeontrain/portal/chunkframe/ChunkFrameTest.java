package games.brennan.dungeontrain.portal.chunkframe;

import net.minecraft.core.Vec3i;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    @DisplayName("A frame dresses every chunk dimension until told otherwise, and toggles keep that")
    void metaDefaultsAndToggles() {
        java.util.List<String> all = java.util.List.of("chunk_dimension", "chunk_dimension_nether", "chunk_dimension_end");
        ChunkFrameMeta meta = ChunkFrameMeta.DEFAULT;
        assertTrue(meta.allRooms());
        assertTrue(meta.appliesTo("chunk_dimension_end"));

        ChunkFrameMeta noNether = meta.toggled("chunk_dimension_nether", false, all);
        assertFalse(noNether.allRooms());
        assertFalse(noNether.appliesTo("chunk_dimension_nether"));
        assertTrue(noNether.appliesTo("chunk_dimension"));

        // Turning the last one back on returns to "every room", so a later chunk dimension joins too.
        assertTrue(noNether.toggled("chunk_dimension_nether", true, all).allRooms());
        assertTrue(meta.withNoRooms().rooms().isEmpty());
        assertFalse(meta.withNoRooms().appliesTo("chunk_dimension"));
    }

    @Test
    @DisplayName("Frame meta round-trips through JSON and clamps its weight")
    void metaJson() {
        ChunkFrameMeta meta = new ChunkFrameMeta(java.util.Set.of("chunk_dimension_end"), 7);
        assertEquals(meta, ChunkFrameMeta.fromJson(meta.toJson()));
        assertEquals(ChunkFrameMeta.DEFAULT, ChunkFrameMeta.fromJson(ChunkFrameMeta.DEFAULT.toJson()));
        assertEquals(ChunkFrameMeta.MAX_WEIGHT, ChunkFrameMeta.DEFAULT.withWeight(999).weight());
    }
}
