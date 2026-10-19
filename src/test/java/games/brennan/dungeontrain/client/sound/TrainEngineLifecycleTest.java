package games.brennan.dungeontrain.client.sound;

import games.brennan.dungeontrain.client.sound.TrainEngineLifecycle.Action;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The engine loop comes back after any kind of mute, and never churns while it cannot be heard.
 */
class TrainEngineLifecycleTest {

    @Test
    @DisplayName("a healthy instance is kept")
    void liveInstanceIsKept() {
        assertEquals(Action.KEEP, TrainEngineLifecycle.decide(true, true, true, true, false));
        assertEquals(Action.KEEP, TrainEngineLifecycle.decide(true, true, true, true, true));
    }

    @Test
    @DisplayName("no instance yet: spawn one")
    void spawnsWhenNothingIsActive() {
        assertEquals(Action.SPAWN, TrainEngineLifecycle.decide(true, true, false, false, false));
    }

    @Test
    @DisplayName("Train Engine Volume off stops the instance and holds the spawn")
    void settingOffStopsAndHolds() {
        assertEquals(Action.STOP, TrainEngineLifecycle.decide(false, true, true, true, true));
        assertEquals(Action.WAIT, TrainEngineLifecycle.decide(false, true, false, false, true));
    }

    @Test
    @DisplayName("Master or Ambient muted stops the instance and holds the spawn")
    void mutedEngineStopsAndHolds() {
        assertEquals(Action.STOP, TrainEngineLifecycle.decide(true, false, true, true, true));
        assertEquals(Action.STOP, TrainEngineLifecycle.decide(true, false, true, false, true));
        assertEquals(Action.WAIT, TrainEngineLifecycle.decide(true, false, false, false, true));
    }

    @Test
    @DisplayName("an instance the engine dropped without stopping it is replaced")
    void droppedInstanceIsReplaced() {
        assertEquals(Action.SPAWN, TrainEngineLifecycle.decide(true, true, true, false, true));
    }

    @Test
    @DisplayName("replacement is throttled so a full channel pool cannot churn instances")
    void replacementWaitsForRetry() {
        assertEquals(Action.WAIT, TrainEngineLifecycle.decide(true, true, true, false, false));
    }
}
