package games.brennan.dungeontrain.builder.relay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two quick saves of one build must make one relay row: the second waits for the first's submit.
 */
final class BuilderSubmitGateTest {

    @Test
    @DisplayName("a second first-upload of one build waits, and only the newest waiting save runs")
    void secondSaveWaitsAndNewestWins() {
        BuilderSubmitGate gate = new BuilderSubmitGate();
        List<String> ran = new ArrayList<>();

        assertTrue(gate.begin("room"));
        assertTrue(gate.isSubmitting("room"));
        assertFalse(gate.begin("room"), "the build is already submitting");
        gate.holdLatest("room", () -> ran.add("older"));
        gate.holdLatest("room", () -> ran.add("newer"));

        Runnable waiting = gate.finish("room");
        waiting.run();
        assertEquals(List.of("newer"), ran);
        assertFalse(gate.isSubmitting("room"));
    }

    @Test
    @DisplayName("a submit that fails still releases the build, so later saves are not held forever")
    void finishReleasesWithNothingWaiting() {
        BuilderSubmitGate gate = new BuilderSubmitGate();
        assertTrue(gate.begin("room"));
        assertNull(gate.finish("room"));
        assertTrue(gate.begin("room"), "free again after a finish");
    }

    @Test
    @DisplayName("different builds never wait on each other")
    void keysAreIndependent() {
        BuilderSubmitGate gate = new BuilderSubmitGate();
        assertTrue(gate.begin("a"));
        assertTrue(gate.begin("b"));
        assertFalse(gate.isSubmitting("c"));
    }
}
