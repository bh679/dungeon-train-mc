package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.client.live.LiveMicHint.Kind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LiveMicHintTest {

    @Test
    void micOffOrGameSoundOffSaysWhereToTurnItOn() {
        assertEquals(Kind.MIC_OFF, LiveMicHint.of(true, false, false, false, false, true));
        assertEquals(Kind.MIC_OFF, LiveMicHint.of(false, true, false, false, false, true), "the mic needs Stream game sound");
        assertEquals(Kind.MIC_OFF, LiveMicHint.of(true, false, false, false, true, true), "no device is not the point while the mic is off");
    }

    @Test
    void reminderOffSilencesTheMicOffLineOnly() {
        assertEquals(Kind.NONE, LiveMicHint.of(true, false, false, false, false, false));
        assertEquals(Kind.NO_DEVICE, LiveMicHint.of(true, true, false, false, true, false), "a wanted mic that failed always says so");
    }

    @Test
    void aMicThatIsOnSaysNothing() {
        assertEquals(Kind.NONE, LiveMicHint.of(true, true, true, false, false, true));
        assertEquals(Kind.NONE, LiveMicHint.of(true, true, false, false, false, true), "dropped for another reason: that reason has its own line");
    }

    @Test
    void aBarredMicLeavesItToTheBarredLine() {
        assertEquals(Kind.NONE, LiveMicHint.of(true, true, true, true, false, true));
        assertEquals(Kind.NONE, LiveMicHint.of(true, false, false, true, false, true));
    }
}
