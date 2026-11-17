package games.brennan.dungeontrain.nap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Off the train's level the gate is judged where the player last stood on it. */
class NapGateDataTest {

    @Test
    @DisplayName("on the train's level the player's own X counts")
    void onTrainUsesOwnX() {
        assertEquals(120.0, NapGateData.gateX(true, 120.0, -400.0, 0.0));
    }

    @Test
    @DisplayName("in a room or another dimension the last train X counts")
    void offTrainUsesLastTrainX() {
        assertEquals(-400.0, NapGateData.gateX(false, 9_000_000.0, -400.0, 0.0));
    }

    @Test
    @DisplayName("with no train X known, the gate itself (counts as ahead)")
    void unknownFallsBackToGate() {
        assertEquals(64.0, NapGateData.gateX(false, 9_000_000.0, Double.NaN, 64.0));
        assertEquals(64.0, NapGateData.gateX(true, Double.NaN, Double.NaN, 64.0));
    }
}
