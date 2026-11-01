package games.brennan.dungeontrain.client.perf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic coverage of which builds {@link LagReportVersionGate} lets run lag detection: the
 * newest, anything ahead of it, and up to 20 minor versions behind.
 */
final class LagReportVersionGateTest {

    @Test
    @DisplayName("The newest release runs, whatever its patch")
    void latestRuns() {
        assertTrue(LagReportVersionGate.isRecent("0.1611.0", "0.1611.0"));
        assertTrue(LagReportVersionGate.isRecent("0.1611.4", "v0.1611.0"));
        assertTrue(LagReportVersionGate.isRecent("0.1611.0", "0.1611.9"));
    }

    @Test
    @DisplayName("A build ahead of the newest release (dev) runs")
    void aheadRuns() {
        assertTrue(LagReportVersionGate.isRecent("0.1612.0", "0.1611.0"));
        assertTrue(LagReportVersionGate.isRecent("1.0.0", "0.1611.0"));
    }

    @Test
    @DisplayName("Exactly 20 minors behind runs; 21 does not")
    void twentyMinorsIsTheEdge() {
        assertTrue(LagReportVersionGate.isRecent("0.1591.0", "0.1611.0"));
        assertFalse(LagReportVersionGate.isRecent("0.1590.9", "0.1611.0"));
    }

    @Test
    @DisplayName("An older major never runs")
    void olderMajorSkips() {
        assertFalse(LagReportVersionGate.isRecent("0.1611.0", "1.0.0"));
    }

    @Test
    @DisplayName("Pre-release and build suffixes are ignored")
    void suffixesIgnored() {
        assertTrue(LagReportVersionGate.isRecent("0.1600.0-beta+neoforge", "0.1611.0"));
    }

    @Test
    @DisplayName("An unknown or unreadable version does not run")
    void unknownSkips() {
        assertFalse(LagReportVersionGate.isRecent("0.1611.0", null));
        assertFalse(LagReportVersionGate.isRecent("0.1611.0", ""));
        assertFalse(LagReportVersionGate.isRecent("0.1611.0", "latest"));
        assertFalse(LagReportVersionGate.isRecent("unknown", "0.1611.0"));
    }
}
