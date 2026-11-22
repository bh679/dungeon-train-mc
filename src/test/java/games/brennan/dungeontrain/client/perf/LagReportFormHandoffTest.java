package games.brennan.dungeontrain.client.perf;

import games.brennan.dungeontrain.client.BugLogReporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What [Yes with info] hands the bug form: one pre-select, then one freeze line, then nothing. */
final class LagReportFormHandoffTest {

    private static final long T = 5_000_000L;

    @AfterEach
    void reset() {
        LagReportFormHandoff.clear();
    }

    @Test
    @DisplayName("Nothing armed: a manual /bug opens and submits as it always has")
    void unarmedGivesNothing() {
        assertFalse(LagReportFormHandoff.takePreselect(T));
        assertNull(LagReportFormHandoff.takeFreezeLine(T));
    }

    @Test
    @DisplayName("Armed: the form starts on Lag once, and its submit carries the freeze once")
    void oneShot() {
        LagReportFormHandoff.arm(4200, T);
        assertTrue(LagReportFormHandoff.takePreselect(T + 300));
        assertFalse(LagReportFormHandoff.takePreselect(T + 400), "a resize keeps DP's own state");
        assertEquals("Detected client freeze: ~4200 ms\n", LagReportFormHandoff.takeFreezeLine(T + 60_000));
        assertNull(LagReportFormHandoff.takeFreezeLine(T + 60_001), "one report per click");
        assertFalse(LagReportFormHandoff.takePreselect(T + 60_002), "a later /bug starts clean");
    }

    @Test
    @DisplayName("A form that never took the pre-select never gets the freeze line")
    void freezeLineNeedsTheForm() {
        LagReportFormHandoff.arm(4200, T);
        assertNull(LagReportFormHandoff.takeFreezeLine(T + 1));
    }

    @Test
    @DisplayName("A form opening too late after the click is not pre-selected; a submit too late carries nothing")
    void expires() {
        LagReportFormHandoff.arm(4200, T);
        assertFalse(LagReportFormHandoff.takePreselect(T + LagReportFormHandoff.OPEN_WINDOW_MS + 1));
        LagReportFormHandoff.arm(4200, T);
        assertTrue(LagReportFormHandoff.takePreselect(T + 1));
        assertNull(LagReportFormHandoff.takeFreezeLine(T + LagReportFormHandoff.TTL_MS + 1));
    }

    @Test
    @DisplayName("Logout drops it")
    void clearDrops() {
        LagReportFormHandoff.arm(4200, T);
        LagReportFormHandoff.clear();
        assertFalse(LagReportFormHandoff.takePreselect(T + 1));
    }

    @Test
    @DisplayName("Lag is found by label, whatever its place")
    void lagByLabel() {
        assertEquals(1, BugLogReporter.lagOptionIndex(List.of("Train Vanished", "Lag", "Other", "No")));
        assertEquals(0, BugLogReporter.lagOptionIndex(List.of("lag", "No")));
        assertEquals(-1, BugLogReporter.lagOptionIndex(List.of("Other", "No")));
    }
}
