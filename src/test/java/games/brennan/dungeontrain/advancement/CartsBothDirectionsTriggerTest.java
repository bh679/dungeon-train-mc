package games.brennan.dungeontrain.advancement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** "The Long Way Back": reach carriage +threshold and carriage -threshold, in either order. */
class CartsBothDirectionsTriggerTest {

    private static final CartsBothDirectionsTrigger.Instance HUNDRED =
        new CartsBothDirectionsTrigger.Instance(Optional.empty(), 100);

    @Test
    @DisplayName("needs both ends: +100 alone or -100 alone is not enough")
    void needsBothEnds() {
        assertFalse(HUNDRED.matches(100, 0), "forward end only");
        assertFalse(HUNDRED.matches(0, -100), "back end only");
        assertTrue(HUNDRED.matches(100, -100));
        assertTrue(HUNDRED.matches(250, -130), "past both ends");
    }

    @Test
    @DisplayName("one carriage short of either end does not count")
    void oneShort() {
        assertFalse(HUNDRED.matches(99, -100));
        assertFalse(HUNDRED.matches(100, -99));
    }

    @Test
    @DisplayName("going 100 forward and back to the start is no longer enough")
    void thereAndBackToStartFails() {
        // The old rule (100 forward + 100 back) was met by +100 then back to 0: the lowest index stays 0.
        assertFalse(HUNDRED.matches(100, 0));
    }
}
