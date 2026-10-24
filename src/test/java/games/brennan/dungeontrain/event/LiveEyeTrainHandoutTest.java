package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.advancement.CloseCallRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveEyeTrainHandoutTest {

    @Test
    @DisplayName("The Challenge owes a flatbed eye; the other close calls boost chests")
    void split() {
        LiveEyeTrainHandout.Split challenge = LiveEyeTrainHandout.split(List.of(CloseCallRules.THE_CHALLENGE));
        assertTrue(challenge.owesEye());
        assertTrue(challenge.boost().isEmpty());

        LiveEyeTrainHandout.Split both = LiveEyeTrainHandout.split(
            List.of(CloseCallRules.THE_CHALLENGE, CloseCallRules.NEARING_INFINITY, CloseCallRules.THE_LONG_RUN));
        assertTrue(both.owesEye());
        assertEquals(List.of(CloseCallRules.NEARING_INFINITY.id(), CloseCallRules.THE_LONG_RUN.id()), both.boost());

        assertFalse(LiveEyeTrainHandout.split(List.of(CloseCallRules.THE_LONG_RUN)).owesEye());
    }

    @Test
    @DisplayName("a boosted player rolls each eye-less container with room once")
    void shouldRoll() {
        assertTrue(LiveEyeTrainHandout.shouldRoll(true, false, false, true));
        assertFalse(LiveEyeTrainHandout.shouldRoll(false, false, false, true), "no boost");
        assertFalse(LiveEyeTrainHandout.shouldRoll(true, true, false, true), "already rolled");
        assertFalse(LiveEyeTrainHandout.shouldRoll(true, false, true, true), "already holds an eye");
        assertFalse(LiveEyeTrainHandout.shouldRoll(true, false, false, false), "full");
        assertTrue(LiveEyeTrainHandout.BOOST_CHANCE > 0f && LiveEyeTrainHandout.BOOST_CHANCE < 1f);
    }
}
