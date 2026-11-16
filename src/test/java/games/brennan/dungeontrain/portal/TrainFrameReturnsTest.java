package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.portal.TrainFrameReturns.Step;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link TrainFrameReturns#decide}: a return lands only in a frame on a live carriage, and never guesses. */
final class TrainFrameReturnsTest {

    @Test
    @DisplayName("a portal with no record is not a way back, whatever else is true")
    void noRecord() {
        assertEquals(Step.NONE, TrainFrameReturns.decide(false, true, true, true));
        assertEquals(Step.NONE, TrainFrameReturns.decide(false, false, false, false));
    }

    @Test
    @DisplayName("a live carriage still holding the frame → land there")
    void landsInStandingFrame() {
        assertEquals(Step.LAND, TrainFrameReturns.decide(true, true, true, true));
        assertEquals(Step.LAND, TrainFrameReturns.decide(true, true, true, false));
    }

    @Test
    @DisplayName("a live carriage that lost the frame → forgotten, no waiting")
    void brokenFrameIsForgotten() {
        assertEquals(Step.FORGET, TrainFrameReturns.decide(true, true, false, true));
    }

    @Test
    @DisplayName("a registered carriage that is not live → wake it")
    void heldCarriageIsWoken() {
        assertEquals(Step.WAKE, TrainFrameReturns.decide(true, false, false, true));
    }

    @Test
    @DisplayName("a carriage neither live nor registered → gone, forgotten")
    void goneCarriageIsForgotten() {
        assertEquals(Step.FORGET, TrainFrameReturns.decide(true, false, false, false));
    }
}
