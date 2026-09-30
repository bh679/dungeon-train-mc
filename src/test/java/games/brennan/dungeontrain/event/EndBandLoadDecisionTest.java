package games.brennan.dungeontrain.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static games.brennan.dungeontrain.event.EndBandLoadDecision.Action.FLAG_AND_REQUEST;
import static games.brennan.dungeontrain.event.EndBandLoadDecision.Action.NONE;
import static games.brennan.dungeontrain.event.EndBandLoadDecision.Action.REQUEST;
import static games.brennan.dungeontrain.event.EndBandLoadDecision.decide;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** What a loading End-band chunk is owed, per terrain mode. */
class EndBandLoadDecisionTest {

    @Test
    @DisplayName("worldgen mode: a new chunk already has its terrain, so nothing is owed")
    void worldgenNewChunkOwesNothing() {
        assertEquals(NONE, decide(true, false, true));
    }

    @Test
    @DisplayName("background mode: a new chunk is flagged and its sample requested")
    void backgroundNewChunkIsFlagged() {
        assertEquals(FLAG_AND_REQUEST, decide(true, false, false));
    }

    @Test
    @DisplayName("a chunk still flagged pending is requested again in either mode — old saves and failed worldgen writes")
    void pendingIsRequestedInEitherMode() {
        assertEquals(REQUEST, decide(false, true, true));
        assertEquals(REQUEST, decide(false, true, false));
        assertEquals(REQUEST, decide(true, true, true), "a worldgen write that failed flags the new chunk");
    }

    @Test
    @DisplayName("a reloaded chunk with its terrain in owes nothing")
    void reloadedTerrainedChunkOwesNothing() {
        assertEquals(NONE, decide(false, false, true));
        assertEquals(NONE, decide(false, false, false));
    }
}
