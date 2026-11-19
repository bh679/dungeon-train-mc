package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.event.EchoSlimeRescueEvents.RescueDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure-logic coverage for {@link EchoSlimeRescueEvents#step} — what one scan decides about an echo
 * slimed back towards the train (Knockback Aboard). Positions are supplied as arguments, so no Minecraft
 * bootstrap.
 */
final class EchoSlimeRescueStepTest {

    @Test
    @DisplayName("standing on the deck, alive, is credited")
    void groundedOnDeck_credits() {
        assertEquals(RescueDecision.CREDIT, EchoSlimeRescueEvents.step(true, true, false));
    }

    @Test
    @DisplayName("still in the air keeps waiting, however long the fall")
    void airborne_keeps() {
        assertEquals(RescueDecision.KEEP, EchoSlimeRescueEvents.step(false, false, false));
    }

    @Test
    @DisplayName("passing just over the deck in mid-air is not a landing yet")
    void airborneOverDeck_keeps() {
        assertEquals(RescueDecision.KEEP, EchoSlimeRescueEvents.step(true, false, false));
    }

    @Test
    @DisplayName("landing on ground that isn't the train ends the attempt")
    void groundedOffTrain_drops() {
        assertEquals(RescueDecision.DROP, EchoSlimeRescueEvents.step(false, true, false));
    }

    @Test
    @DisplayName("dead (fall damage, the void) or unloaded echo is dropped")
    void gone_drops() {
        assertEquals(RescueDecision.DROP, EchoSlimeRescueEvents.step(null, false, false));
    }

    @Test
    @DisplayName("twin space never counts, even on something deck-like")
    void twinSpace_drops() {
        assertEquals(RescueDecision.DROP, EchoSlimeRescueEvents.step(true, true, true));
    }
}
