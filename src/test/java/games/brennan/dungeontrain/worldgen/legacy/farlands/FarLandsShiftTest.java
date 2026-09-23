package games.brennan.dungeontrain.worldgen.legacy.farlands;

import games.brennan.dungeontrain.worldgen.legacy.farlands.FarLandsShift.Stage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure layout of the Far Lands source-coordinate shift along a band instance. */
final class FarLandsShiftTest {

    private static final long HOLD = 6000L;
    private static final long CORE = 1_234_560L;

    private static int chunkAt(long local) {
        return (int) Math.floorDiv(CORE + local, 16L);
    }

    @Test
    @DisplayName("four quarter-length stages; the entry fade rides the wall stage, the exit fade the right one")
    void stages() {
        assertEquals(Stage.WALL, FarLandsShift.stageAt(CORE, HOLD, chunkAt(-150)));
        assertEquals(Stage.WALL, FarLandsShift.stageAt(CORE, HOLD, chunkAt(0)));
        assertEquals(Stage.WALL, FarLandsShift.stageAt(CORE, HOLD, chunkAt(1480)));
        assertEquals(Stage.LEFT, FarLandsShift.stageAt(CORE, HOLD, chunkAt(1504)));
        assertEquals(Stage.BOTH, FarLandsShift.stageAt(CORE, HOLD, chunkAt(3008)));
        assertEquals(Stage.RIGHT, FarLandsShift.stageAt(CORE, HOLD, chunkAt(4512)));
        assertEquals(Stage.RIGHT, FarLandsShift.stageAt(CORE, HOLD, chunkAt(6300)));
    }

    @Test
    @DisplayName("wall stage: one X shift for the whole stage, Z untouched, wall APPROACH blocks into the core")
    void wall() {
        FarLandsShift first = FarLandsShift.forChunk(CORE, HOLD, chunkAt(-150), 0);
        for (long l = -150; l < 1400; l += 16) {
            FarLandsShift s = FarLandsShift.forChunk(CORE, HOLD, chunkAt(l), (int) (l % 7));
            assertEquals(first, s);
        }
        long wallWorldX = (long) FarLandsShift.EDGE - first.dxBlocks();
        assertTrue(Math.abs(wallWorldX - (CORE + FarLandsShift.APPROACH)) < 16, "wall at " + wallWorldX);
    }

    @Test
    @DisplayName("side stages put the Z edge SIDE_Z blocks left (−Z) / right (+Z) of the track, X unshifted")
    void sides() {
        FarLandsShift left = FarLandsShift.forChunk(CORE, HOLD, chunkAt(2000), 5);
        FarLandsShift right = FarLandsShift.forChunk(CORE, HOLD, chunkAt(5000), -5);
        assertEquals(0, left.dxChunks());
        assertEquals(0, right.dxChunks());
        long leftEdge = -(long) FarLandsShift.EDGE - left.dzBlocks();
        long rightEdge = (long) FarLandsShift.EDGE - right.dzBlocks();
        assertTrue(leftEdge <= -FarLandsShift.SIDE_Z && leftEdge > -FarLandsShift.SIDE_Z - 16, "left edge " + leftEdge);
        assertEquals(-rightEdge, leftEdge);
        assertTrue(rightEdge >= FarLandsShift.SIDE_Z && rightEdge < FarLandsShift.SIDE_Z + 16, "right edge " + rightEdge);
    }

    @Test
    @DisplayName("both stage: chunks left of the track read the left edge, the rest the right one")
    void both() {
        int cx = chunkAt(3500);
        assertEquals(FarLandsShift.forChunk(CORE, HOLD, chunkAt(2000), 0),
                FarLandsShift.forChunk(CORE, HOLD, cx, -1));
        assertEquals(FarLandsShift.forChunk(CORE, HOLD, chunkAt(5000), 0),
                FarLandsShift.forChunk(CORE, HOLD, cx, 0));
    }
}
