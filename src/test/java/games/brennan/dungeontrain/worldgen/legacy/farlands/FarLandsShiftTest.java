package games.brennan.dungeontrain.worldgen.legacy.farlands;

import games.brennan.dungeontrain.worldgen.legacy.farlands.FarLandsShift.Stage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure layout of the Far Lands source-coordinate shift along a band instance. */
final class FarLandsShiftTest {

    private static final long HOLD = FarLandsShift.SCRIPT_LEN;
    private static final long CORE = 1_234_560L;

    private static int chunkAt(long local) {
        return (int) Math.floorDiv(CORE + local, 16L);
    }

    private static FarLandsShift at(long local, int chunkZ) {
        return FarLandsShift.forChunk(CORE, HOLD, chunkAt(local), chunkZ);
    }

    /** World Z of a right-side wall (Far Lands at z ≥ it). */
    private static long rightEdge(FarLandsShift s) {
        return (long) FarLandsShift.EDGE - s.dzBlocks();
    }

    /** Distance of a left-side wall from the track (Far Lands at z ≤ −it). */
    private static long leftDist(FarLandsShift s) {
        return (long) FarLandsShift.EDGE + s.dzBlocks();
    }

    private static void assertNear(long expected, long actual) {
        assertTrue(actual >= expected && actual < expected + 16, "expected ~" + expected + " was " + actual);
    }

    @Test
    @DisplayName("stages at 1k / 3k / 6k / 8k; the entry fade reads the entry, the exit fade the exit")
    void stages() {
        assertEquals(Stage.ENTRY, FarLandsShift.stageAt(CORE, HOLD, chunkAt(-160)));
        assertEquals(Stage.ENTRY, FarLandsShift.stageAt(CORE, HOLD, chunkAt(992)));
        assertEquals(Stage.CLOSING, FarLandsShift.stageAt(CORE, HOLD, chunkAt(1008)));
        assertEquals(Stage.CANYON, FarLandsShift.stageAt(CORE, HOLD, chunkAt(3008)));
        assertEquals(Stage.OPENING, FarLandsShift.stageAt(CORE, HOLD, chunkAt(6000)));
        assertEquals(Stage.EXIT, FarLandsShift.stageAt(CORE, HOLD, chunkAt(8000)));
        assertEquals(Stage.EXIT, FarLandsShift.stageAt(CORE, HOLD, chunkAt(9400)));
    }

    @Test
    @DisplayName("entry: one X shift, Z untouched, the wall APPROACH blocks into the core")
    void entry() {
        FarLandsShift first = at(-160, 0);
        for (long l = -160; l < 992; l += 16) assertEquals(first, at(l, (int) (l % 7)));
        long wallWorldX = (long) FarLandsShift.EDGE - first.dxBlocks();
        assertTrue(Math.abs(wallWorldX - (CORE + FarLandsShift.APPROACH)) < 16, "wall at " + wallWorldX);
    }

    @Test
    @DisplayName("closing: left wall rests at SIDE_Z; the right closes in every chunk, fast then creeping")
    void closing() {
        assertEquals(1000, FarLandsShift.closingDistance(0.0));
        assertEquals(FarLandsShift.SIDE_Z, FarLandsShift.closingDistance(1.0));
        long prev = Long.MAX_VALUE;
        long firstQuarterDrop = 0;
        long lastQuarterDrop = 0;
        for (long l = 1008; l < 3000; l += 16) {
            assertNear(FarLandsShift.SIDE_Z, leftDist(at(l, -1)));
            assertEquals(0, at(l, 0).dxChunks());
            long d = rightEdge(at(l, 0));
            assertTrue(d <= prev, "wall moved out at " + l);
            if (prev != Long.MAX_VALUE) {
                if (l < 1500) firstQuarterDrop += prev - d;
                if (l >= 2500) lastQuarterDrop += prev - d;
            }
            prev = d;
        }
        assertTrue(firstQuarterDrop > 10 * lastQuarterDrop, firstQuarterDrop + " vs " + lastQuarterDrop);
        assertNear(1000, rightEdge(at(1008, 0)));
    }

    @Test
    @DisplayName("canyon: both walls at SIDE_Z, mirror images")
    void canyon() {
        assertNear(FarLandsShift.SIDE_Z, leftDist(at(4000, -3)));
        assertNear(FarLandsShift.SIDE_Z, rightEdge(at(4000, 3)));
    }

    @Test
    @DisplayName("opening: the left wall eases back out, mirroring the closing; the right one stays")
    void opening() {
        for (double t = 0; t <= 1.0; t += 0.05) {
            assertEquals(FarLandsShift.closingDistance(1.0 - t), FarLandsShift.openingDistance(t));
        }
        long prev = 0;
        for (long l = 6000; l < 8000; l += 16) {
            long d = leftDist(at(l, -1));
            assertTrue(d >= prev, "wall moved in at " + l);
            assertNear(FarLandsShift.SIDE_Z, rightEdge(at(l, 0)));
            prev = d;
        }
    }

    @Test
    @DisplayName("exit: the right wall sweeps across the track, then the train breaks out through an X wall")
    void exit() {
        long sweepLen = (FarLandsShift.SWEEP_END - FarLandsShift.OPENING_END) / FarLandsShift.SWEEP_STEPS.length;
        for (int k = 0; k < FarLandsShift.SWEEP_STEPS.length; k++) {
            long l = FarLandsShift.OPENING_END + k * sweepLen + 16;
            assertNear(FarLandsShift.SWEEP_STEPS[k], rightEdge(at(l, -2)));
            assertEquals(at(l, -2), at(l, 2));
        }
        FarLandsShift out = at(8600, 0);
        assertEquals(out, at(9400, 5));
        assertEquals(0, out.dzChunks());
        long wallWorldX = -(long) FarLandsShift.EDGE - out.dxBlocks();
        long expected = CORE + FarLandsShift.SCRIPT_LEN - FarLandsShift.APPROACH;
        assertTrue(Math.abs(wallWorldX - expected) < 16, "exit wall at " + wallWorldX);
    }

    @Test
    @DisplayName("a shorter core scales the script: stages keep their proportions")
    void scaled() {
        long hold = 2400;
        assertEquals(Stage.ENTRY, FarLandsShift.stageAt(CORE, hold, (int) ((CORE + 256) >> 4)));
        assertEquals(Stage.CLOSING, FarLandsShift.stageAt(CORE, hold, (int) ((CORE + 272) >> 4)));
        assertEquals(Stage.EXIT, FarLandsShift.stageAt(CORE, hold, (int) ((CORE + 2144) >> 4)));
    }
}
