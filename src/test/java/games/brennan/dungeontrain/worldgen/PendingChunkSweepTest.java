package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static games.brennan.dungeontrain.worldgen.PendingChunkSweep.Action.NONE;
import static games.brennan.dungeontrain.worldgen.PendingChunkSweep.Action.REQUEST;
import static games.brennan.dungeontrain.worldgen.PendingChunkSweep.Action.WRITE_STASHED;
import static games.brennan.dungeontrain.worldgen.PendingChunkSweep.decide;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link PendingChunkSweep} — a loaded chunk still owed End-band terrain always gets it. */
class PendingChunkSweepTest {

    @Test
    @DisplayName("a pending chunk whose job was dropped asks for its sample again")
    void droppedJobIsRequestedAgain() {
        assertEquals(REQUEST, decide(true, false, false));
    }

    @Test
    @DisplayName("a pending chunk whose sample was stashed mid-load gets that sample written")
    void stashedSampleIsWritten() {
        assertEquals(WRITE_STASHED, decide(true, true, false));
    }

    @Test
    @DisplayName("finished or already-due chunks are left alone")
    void nothingOwed() {
        assertEquals(NONE, decide(false, false, false));
        assertEquals(NONE, decide(false, true, false));
        assertEquals(NONE, decide(true, true, true));
        assertEquals(NONE, decide(true, false, true));
    }
}
