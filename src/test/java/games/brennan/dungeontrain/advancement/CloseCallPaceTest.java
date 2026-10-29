package games.brennan.dungeontrain.advancement;

import games.brennan.dungeontrain.advancement.CloseCallPace.Metric;
import games.brennan.dungeontrain.advancement.CloseCallPace.Sample;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloseCallPaceTest {

    private static final long MIN = 60_000L;

    /** A steady {@code perMin} carriages (and 100 m per carriage) for {@code minutes} minutes from 0. */
    private static CloseCallPace steady(int minutes, double perMin) {
        CloseCallPace p = CloseCallPace.EMPTY;
        for (int m = 0; m <= minutes; m++) p = p.with(new Sample(m * MIN, m * perMin, m * perMin * 100));
        return p;
    }

    @Test
    @DisplayName("no estimate until the window spans five minutes")
    void needsFiveMinutes() {
        assertTrue(steady(4, 20).etaMs(Metric.CARRIAGES, 1000).isEmpty());
        assertFalse(steady(5, 20).etaMs(Metric.CARRIAGES, 1000).isEmpty());
    }

    @Test
    @DisplayName("a steady pace gives remaining divided by rate, for carriages and metres")
    void steadyEta() {
        CloseCallPace p = steady(6, 20); // at 120 carriages, 12,000 m
        assertEquals(44 * MIN, p.etaMs(Metric.CARRIAGES, 1000).getAsLong());
        assertEquals(10 * MIN, p.etaMs(Metric.METRES, 32_000).getAsLong());
    }

    @Test
    @DisplayName("a stall, a reached target, or going backwards gives no estimate")
    void noProgress() {
        CloseCallPace stall = CloseCallPace.EMPTY;
        for (int m = 0; m <= 6; m++) stall = stall.with(new Sample(m * MIN, 500, 50_000));
        assertTrue(stall.etaMs(Metric.CARRIAGES, 1000).isEmpty());
        assertTrue(steady(6, 20).etaMs(Metric.CARRIAGES, 100).isEmpty());
    }

    @Test
    @DisplayName("readings are kept a minute apart and the window holds ten minutes")
    void windowAndGap() {
        CloseCallPace p = steady(6, 20);
        assertSame(p, p.with(new Sample(6 * MIN + 1000, 999, 999)), "a reading inside the minute is ignored");
        CloseCallPace long_ = steady(30, 20);
        long span = long_.samples().get(long_.samples().size() - 1).atMs() - long_.samples().get(0).atMs();
        assertTrue(span <= CloseCallPace.WINDOW_MS);
    }

    @Test
    @DisplayName("an impossible jump (teleport, new life) restarts the window")
    void implausibleRestarts() {
        CloseCallPace p = steady(8, 20).with(new Sample(9 * MIN, 0, 0)); // died: back to zero
        assertEquals(1, p.samples().size());
        assertTrue(p.etaMs(Metric.CARRIAGES, 1000).isEmpty());
    }
}
