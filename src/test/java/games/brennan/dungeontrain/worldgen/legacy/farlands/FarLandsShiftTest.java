package games.brennan.dungeontrain.worldgen.legacy.farlands;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure layout of the Far Lands source-coordinate shift along a band instance. */
final class FarLandsShiftTest {

    private static final long HOLD = 6000L;

    @Test
    @DisplayName("act 1: one X shift for the whole instance, Z untouched, wall APPROACH blocks into the core")
    void act1() {
        long coreStart = 1_234_567L;
        FarLandsShift first = FarLandsShift.forChunk(coreStart, HOLD, (int) (coreStart >> 4) - 20);
        for (int i = -20; i < 200; i++) {
            FarLandsShift s = FarLandsShift.forChunk(coreStart, HOLD, (int) (coreStart >> 4) + i);
            assertEquals(first.dxChunks(), s.dxChunks());
            assertEquals(0, s.dzChunks());
        }
        long wallWorldX = (long) FarLandsShift.EDGE - first.dxBlocks();
        assertTrue(Math.abs(wallWorldX - (coreStart + FarLandsShift.APPROACH)) < 16, "wall at " + wallWorldX);
    }

    @Test
    @DisplayName("act 2 shifts Z so the overflow edge sits just to the track's +Z side; X is unchanged")
    void act2() {
        long coreStart = -48_000L;
        int act2Chunk = (int) ((coreStart + (long) (HOLD * FarLandsShift.ACT2_FRACTION) + 15) >> 4);
        FarLandsShift before = FarLandsShift.forChunk(coreStart, HOLD, act2Chunk - 1);
        FarLandsShift after = FarLandsShift.forChunk(coreStart, HOLD, act2Chunk);
        assertEquals(0, before.dzChunks());
        assertEquals(before.dxChunks(), after.dxChunks());
        long edgeWorldZ = (long) FarLandsShift.EDGE - after.dzBlocks();
        assertTrue(edgeWorldZ >= FarLandsShift.CORNER_SIDE_Z && edgeWorldZ < FarLandsShift.CORNER_SIDE_Z + 16,
                "Z edge at " + edgeWorldZ);
        // the exit fade stays in act 2
        assertEquals(after, FarLandsShift.forChunk(coreStart, HOLD, (int) ((coreStart + HOLD + 400) >> 4)));
    }
}
