package games.brennan.dungeontrain.perf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic coverage for the two decisions {@link ServerLoadSampler} makes without Minecraft
 * types: which GC beans count as tick-stalling pauses, and how the tick-time ring is read. The bean
 * names are the real ones each JDK 21 collector publishes.
 */
final class ServerLoadSamplerTest {

    @Test
    @DisplayName("G1: young and old count, the concurrent-cycle bean does not")
    void g1() {
        List<String> names = List.of("G1 Young Generation", "G1 Concurrent GC", "G1 Old Generation");
        assertTrue(ServerLoadSampler.isPauseCollector("G1 Young Generation", names));
        assertTrue(ServerLoadSampler.isPauseCollector("G1 Old Generation", names));
        assertFalse(ServerLoadSampler.isPauseCollector("G1 Concurrent GC", names));
    }

    @Test
    @DisplayName("Generational ZGC: only the Pauses beans count")
    void zgc() {
        List<String> names = List.of("ZGC Minor Cycles", "ZGC Minor Pauses", "ZGC Major Cycles", "ZGC Major Pauses");
        assertTrue(ServerLoadSampler.isPauseCollector("ZGC Minor Pauses", names));
        assertTrue(ServerLoadSampler.isPauseCollector("ZGC Major Pauses", names));
        assertFalse(ServerLoadSampler.isPauseCollector("ZGC Minor Cycles", names));
        assertFalse(ServerLoadSampler.isPauseCollector("ZGC Major Cycles", names));
    }

    @Test
    @DisplayName("Shenandoah: Pauses counts, Cycles does not")
    void shenandoah() {
        List<String> names = List.of("Shenandoah Pauses", "Shenandoah Cycles");
        assertTrue(ServerLoadSampler.isPauseCollector("Shenandoah Pauses", names));
        assertFalse(ServerLoadSampler.isPauseCollector("Shenandoah Cycles", names));
    }

    @Test
    @DisplayName("Parallel and Serial: every bean is a pause collector")
    void parallelAndSerial() {
        List<String> parallel = List.of("PS Scavenge", "PS MarkSweep");
        assertTrue(ServerLoadSampler.isPauseCollector("PS Scavenge", parallel));
        assertTrue(ServerLoadSampler.isPauseCollector("PS MarkSweep", parallel));
        List<String> serial = List.of("Copy", "MarkSweepCompact");
        assertTrue(ServerLoadSampler.isPauseCollector("Copy", serial));
    }

    @Test
    @DisplayName("Tick max reads the last N completed ticks, not the in-progress slot")
    void tickMaxSkipsCurrentSlot() {
        long[] ring = new long[100];
        ring[50] = 999;  // slot for tickCount 50 = in progress, stale from 100 ticks ago
        ring[49] = 7;
        ring[10] = 5;
        assertEquals(7, ServerLoadSampler.maxTickNanos(ring, 50, 40));
    }

    @Test
    @DisplayName("Tick max wraps around the ring and stops at the window edge")
    void tickMaxWraps() {
        long[] ring = new long[100];
        ring[99] = 30;   // tick 199 → in window from tickCount 205
        ring[2] = 20;    // tick 202
        ring[60] = 500;  // tick 160 → 45 back, outside a 40-tick window
        assertEquals(30, ServerLoadSampler.maxTickNanos(ring, 205, 40));
        assertEquals(500, ServerLoadSampler.maxTickNanos(ring, 205, 45));
    }

    @Test
    @DisplayName("Tick max before the server has run a full window")
    void tickMaxEarly() {
        long[] ring = new long[100];
        ring[99] = 1_000;  // never written in a real ring this early; must not be read
        ring[0] = 3;
        ring[1] = 4;
        assertEquals(4, ServerLoadSampler.maxTickNanos(ring, 2, 40));
        assertEquals(0, ServerLoadSampler.maxTickNanos(ring, 0, 40));
        assertEquals(0, ServerLoadSampler.maxTickNanos(new long[0], 10, 40));
    }
}
