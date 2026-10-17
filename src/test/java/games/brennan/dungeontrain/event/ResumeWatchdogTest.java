package games.brennan.dungeontrain.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ResumeWatchdog#isResumeGap} reads the idle gap between ticks (previous tick's end to this
 * tick's start). A pause grows that gap; a server stall happens inside a tick and must not count,
 * or the hold it triggers loads chunks, stalls again, and re-triggers itself.
 */
class ResumeWatchdogTest {

    private static final long MS = 1_000_000L;

    @Test
    @DisplayName("no baseline (first tick) → not a resume")
    void noBaseline() {
        assertFalse(ResumeWatchdog.isResumeGap(0L, 10_000 * MS));
    }

    @Test
    @DisplayName("normal 50 ms cadence → not a resume")
    void normalCadence() {
        assertFalse(ResumeWatchdog.isResumeGap(1_000 * MS, 1_045 * MS));
    }

    @Test
    @DisplayName("catch-up tick right after a 20 s stall → not a resume (stall was inside the tick)")
    void catchUpAfterStall() {
        long stalledTickEnd = 21_000 * MS; // the tick itself took 20 s
        assertFalse(ResumeWatchdog.isResumeGap(stalledTickEnd, stalledTickEnd));
    }

    @Test
    @DisplayName("idle gap of 2 s or more between ticks → resume from pause")
    void pauseGap() {
        assertTrue(ResumeWatchdog.isResumeGap(1_000 * MS, 3_000 * MS));
        assertTrue(ResumeWatchdog.isResumeGap(1_000 * MS, 46_000 * MS));
    }

    @Test
    @DisplayName("just under 2 s idle → not a resume")
    void justUnder() {
        assertFalse(ResumeWatchdog.isResumeGap(1_000 * MS, 2_999 * MS));
    }
}
