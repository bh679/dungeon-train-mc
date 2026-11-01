package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.event.EchoSlimeRescueEvents.RescueDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure-logic coverage for {@link EchoSlimeRescueEvents#step} — what one scan decides about an echo
 * slimed back towards the train (Bounce Back). Positions are supplied as arguments, so no Minecraft
 * bootstrap.
 */
final class EchoSlimeRescueStepTest {

    @Test
    @DisplayName("echo on the deck inside the window is credited")
    void onDeck_credits() {
        assertEquals(RescueDecision.CREDIT, EchoSlimeRescueEvents.step(true, false, false));
    }

    @Test
    @DisplayName("landing on the deck on the scan the window runs out still counts")
    void onDeck_atExpiry_credits() {
        assertEquals(RescueDecision.CREDIT, EchoSlimeRescueEvents.step(true, false, true));
    }

    @Test
    @DisplayName("still in the air inside the window keeps waiting")
    void offDeck_inWindow_keeps() {
        assertEquals(RescueDecision.KEEP, EchoSlimeRescueEvents.step(false, false, false));
    }

    @Test
    @DisplayName("still off the deck when the window runs out is dropped")
    void offDeck_expired_drops() {
        assertEquals(RescueDecision.DROP, EchoSlimeRescueEvents.step(false, false, true));
    }

    @Test
    @DisplayName("dead or unloaded echo is dropped")
    void gone_drops() {
        assertEquals(RescueDecision.DROP, EchoSlimeRescueEvents.step(null, false, false));
    }

    @Test
    @DisplayName("twin space never counts, even on something deck-like")
    void twinSpace_drops() {
        assertEquals(RescueDecision.DROP, EchoSlimeRescueEvents.step(true, true, false));
    }
}
