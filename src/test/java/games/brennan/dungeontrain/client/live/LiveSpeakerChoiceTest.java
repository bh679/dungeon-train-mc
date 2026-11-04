package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Only one TV has sound at a time: the worn or held one first, else the nearest placed one, kept unless another is clearly nearer. */
class LiveSpeakerChoiceTest {

    @Test
    void onlyOneTvPlays() {
        assertEquals(1, LiveSpeakerChoice.MAX_SPEAKERS);
        assertEquals(1, LiveTvSpeakers.MAX_TV_SPEAKERS);
    }

    @Test
    void theWornTvSilencesEveryPlacedTv() {
        assertNull(LiveSpeakerChoice.placed(true, Map.of("near", 1.0, "far", 5.0), null));
        assertNull(LiveSpeakerChoice.placed(true, Map.of("near", 1.0), "near"), "even the one that was playing");
        assertNull(LiveSpeakerChoice.placed(true, Map.of(), "near", true), "even one held through a gap");
    }

    @Test
    void noPlacedTvInRangeMeansNone() {
        assertNull(LiveSpeakerChoice.placed(false, Map.of(), null));
        assertNull(LiveSpeakerChoice.placed(false, Map.of(), "gone"), "the one playing left range");
    }

    @Test
    void theNearestPlacedTvPlays() {
        assertEquals("near", LiveSpeakerChoice.placed(false, Map.of("far", 7.0, "near", 2.0, "mid", 4.0), null));
    }

    @Test
    void thePlayingTvKeepsTheSoundUnlessAnotherIsClearlyNearer() {
        assertEquals("a", LiveSpeakerChoice.placed(false, Map.of("a", 3.0, "b", 3.0), "a"), "equal distance: no flap");
        assertEquals("a", LiveSpeakerChoice.placed(false, Map.of("a", 3.4, "b", 3.0), "a"), "nearer by less than the margin");
        assertEquals("a", LiveSpeakerChoice.placed(false, Map.of("a", 3.5, "b", 3.0), "a"), "nearer by exactly the margin");
        assertEquals("b", LiveSpeakerChoice.placed(false, Map.of("a", 3.6, "b", 3.0), "a"), "nearer by more than the margin");
    }

    @Test
    void aTvThatStoppedAskingGivesWayToTheNearest() {
        assertEquals("b", LiveSpeakerChoice.placed(false, Map.of("b", 6.0, "c", 8.0), "a"));
    }

    /** A TV on a Sable carriage skips client ticks: the one playing keeps its sound through the gap, so it is one sound, not a restart. */
    @Test
    void thePlayingTvKeepsTheSoundThroughATickItDidNotAskOn() {
        assertEquals("a", LiveSpeakerChoice.placed(false, Map.of(), "a", true), "no TV asked this tick: still a's");
        assertEquals("a", LiveSpeakerChoice.placed(false, Map.of("b", 1.0), "a", true), "another asked, a did not: still a's, no handover");
        assertNull(LiveSpeakerChoice.placed(false, Map.of(), "a", false), "held no longer: switched off");
        assertEquals("b", LiveSpeakerChoice.placed(false, Map.of("b", 1.0), "a", false), "held no longer: the nearest takes over");
    }

    @Test
    void aHeldTvThatAsksAgainIsJudgedByDistanceAsUsual() {
        assertEquals("b", LiveSpeakerChoice.placed(false, Map.of("a", 3.6, "b", 3.0), "a", true), "asked, and another is clearly nearer");
        assertEquals("a", LiveSpeakerChoice.placed(false, Map.of("a", 3.0, "b", 3.0), "a", true));
    }

    @Test
    void theHoldIsShort() {
        assertTrue(LiveSpeakerChoice.held(1_000, 1_000), "asked this tick");
        assertTrue(LiveSpeakerChoice.held(1_000, 1_000 + LiveSpeakerChoice.HOLD_MS), "a few ticks ago");
        assertFalse(LiveSpeakerChoice.held(1_000, 1_001 + LiveSpeakerChoice.HOLD_MS), "longer: let go");
        assertTrue(LiveSpeakerChoice.HOLD_MS >= 100 && LiveSpeakerChoice.HOLD_MS <= 1_000, "a few ticks, not a switch-off's worth");
    }
}
