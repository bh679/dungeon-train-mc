package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static games.brennan.dungeontrain.client.live.LiveEyeAfkOffer.REOFFER_MS;
import static games.brennan.dungeontrain.client.live.LiveEyeAfkOffer.newAbsence;
import static games.brennan.dungeontrain.client.live.LiveEyeAfkOffer.shouldOffer;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveEyeAfkOfferTest {

    private static final long TWO_MIN = 120_000L;
    private static final long FIVE_MIN = 300_000L;

    @Test
    @DisplayName("away 2 minutes in the world offers the eye")
    void inWorld() {
        assertFalse(shouldOffer(true, TWO_MIN - 1, false, false, -1L));
        assertTrue(shouldOffer(true, TWO_MIN, false, false, -1L));
    }

    @Test
    @DisplayName("the pause menu counts as the world, so the offer comes with the 2-minute auto-pause")
    void pauseMenu() {
        assertTrue(shouldOffer(true, TWO_MIN, true, true, -1L));
    }

    @Test
    @DisplayName("a book or chest open waits 5 minutes: reading is not being away")
    void otherScreen() {
        assertFalse(shouldOffer(true, TWO_MIN, true, false, -1L));
        assertFalse(shouldOffer(true, FIVE_MIN - 1, true, false, -1L));
        assertTrue(shouldOffer(true, FIVE_MIN, true, false, -1L));
    }

    @Test
    @DisplayName("never off air")
    void offAir() {
        assertFalse(shouldOffer(false, FIVE_MIN * 10, false, false, -1L));
    }

    @Test
    @DisplayName("once per absence, then again every 2 minutes while still away")
    void repeats() {
        long first = TWO_MIN + 500;
        assertFalse(shouldOffer(true, first + 1_000, false, false, first));
        assertFalse(shouldOffer(true, first + REOFFER_MS - 1, false, false, first));
        assertTrue(shouldOffer(true, first + REOFFER_MS, false, false, first));
    }

    @Test
    @DisplayName("input (the idle clock running backwards) starts a new absence")
    void absenceEnds() {
        assertFalse(newAbsence(TWO_MIN, -1L), "nothing offered yet");
        assertFalse(newAbsence(TWO_MIN + 1_000, TWO_MIN), "still away");
        assertTrue(newAbsence(1_000, TWO_MIN), "moved since");
    }
}
