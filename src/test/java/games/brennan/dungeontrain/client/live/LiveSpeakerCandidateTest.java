package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A placed TV plays only when the world says so: the lit master block of its screen, with its block entity in place, unpaused, powered. */
class LiveSpeakerCandidateTest {

    @Test
    void aLitMasterInPlacePlays() {
        assertTrue(LiveSpeakerCandidate.eligible(true, true, false, false, false), "no external power needed");
        assertTrue(LiveSpeakerCandidate.eligible(true, true, false, true, true), "external power needed and present");
    }

    @Test
    void aChildBlockOrAnUnlitMasterNeverPlaysWhateverTickedIt() {
        assertFalse(LiveSpeakerCandidate.eligible(false, true, false, false, true), "a child block of a bigger screen, or a master whose block says off");
    }

    @Test
    void aStaleBlockEntityNeverPlays() {
        assertFalse(LiveSpeakerCandidate.eligible(true, false, false, false, true), "the world holds another block entity at that block");
    }

    @Test
    void vistasOwnPauseAndPowerStillApply() {
        assertFalse(LiveSpeakerCandidate.eligible(true, true, true, false, true), "paused");
        assertFalse(LiveSpeakerCandidate.eligible(true, true, false, true, false), "needs external power and has none");
    }
}
