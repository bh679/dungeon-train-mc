package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.advancement.CloseCallRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveEyeTrainHandoutTest {

    @Test
    @DisplayName("only a close call on The Challenge earns a train Live Eye")
    void owesEye() {
        assertTrue(LiveEyeTrainHandout.owesEye(CloseCallRules.THE_CHALLENGE));
        assertFalse(LiveEyeTrainHandout.owesEye(CloseCallRules.NEARING_INFINITY));
        assertFalse(LiveEyeTrainHandout.owesEye(CloseCallRules.THE_LONG_RUN));
    }

    @Test
    @DisplayName("the eye never lands on a melon flatbed; it takes the next one")
    void eyeFlatbed() {
        int size = 3;
        assertFalse(LiveEyeTrainHandout.isEyeFlatbed(15, size), "group 5 is a melon flatbed");
        assertTrue(LiveEyeTrainHandout.isEyeFlatbed(18, size), "the next flatbed");
        assertTrue(LiveEyeTrainHandout.isEyeFlatbed(3, size));
    }
}
