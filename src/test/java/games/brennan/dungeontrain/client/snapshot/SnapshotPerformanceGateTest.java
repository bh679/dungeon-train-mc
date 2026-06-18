package games.brennan.dungeontrain.client.snapshot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link SnapshotPerformanceGate}: tick-nanos → TPS conversion and the
 * FPS/TPS "ok to capture" decision. No NeoForge bootstrap — the class takes plain numbers.
 */
final class SnapshotPerformanceGateTest {

    @Test
    @DisplayName("tick nanos convert to TPS, capped at 20; a no-data sample reads as healthy")
    void tpsFromTickNanos_basics() {
        assertEquals(20.0, SnapshotPerformanceGate.tpsFromTickNanos(50_000_000L), 1e-6, "50ms/tick = 20 TPS (perfect)");
        assertEquals(20.0, SnapshotPerformanceGate.tpsFromTickNanos(40_000_000L), 1e-6, "40ms/tick caps at 20, not 25");
        assertEquals(10.0, SnapshotPerformanceGate.tpsFromTickNanos(100_000_000L), 1e-6, "100ms/tick = 10 TPS");
        assertEquals(20.0, SnapshotPerformanceGate.tpsFromTickNanos(0L), 1e-6, "no sample → healthy");
        assertEquals(20.0, SnapshotPerformanceGate.tpsFromTickNanos(-5L), 1e-6, "negative sample → healthy");
    }

    @Test
    @DisplayName("FPS below the floor blocks; at or above passes")
    void ok_fpsGate() {
        assertFalse(SnapshotPerformanceGate.ok(29, 30, 20.0, 18, true), "29 < 30 fps blocks");
        assertTrue(SnapshotPerformanceGate.ok(30, 30, 20.0, 18, true), "30 == 30 fps passes");
        assertTrue(SnapshotPerformanceGate.ok(120, 30, 20.0, 18, true));
    }

    @Test
    @DisplayName("the TPS gate only applies when TPS is known (single-player)")
    void ok_tpsGate_knownVsUnknown() {
        assertFalse(SnapshotPerformanceGate.ok(120, 30, 17.0, 18, true), "17 < 18 tps blocks when known");
        assertTrue(SnapshotPerformanceGate.ok(120, 30, 18.0, 18, true), "18 == 18 tps passes");
        assertTrue(SnapshotPerformanceGate.ok(120, 30, 1.0, 18, false), "unknown tps (multiplayer) never blocks");
    }

    @Test
    @DisplayName("a zero threshold disables that gate; the other still applies")
    void ok_zeroDisables() {
        assertTrue(SnapshotPerformanceGate.ok(1, 0, 20.0, 18, true), "minFps 0 → FPS never blocks");
        assertTrue(SnapshotPerformanceGate.ok(120, 30, 1.0, 0, true), "minTps 0 → TPS never blocks");
        assertFalse(SnapshotPerformanceGate.ok(1, 30, 20.0, 18, true), "the un-disabled gate still blocks");
    }
}
