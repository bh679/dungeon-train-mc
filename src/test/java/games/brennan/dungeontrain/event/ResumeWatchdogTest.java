package games.brennan.dungeontrain.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ResumeWatchdog#isResumeGap} reads the idle gap between ticks (previous tick's end to this
 * tick's start). A pause grows that gap; a server stall inside a tick must not count, or the hold it
 * triggers loads chunks, stalls again, and re-triggers itself. {@link ResumeWatchdog#classifyGap}
 * then splits a long gap by whether the server ran its pause loop: a stall between ticks (player log
 * 23 Oct 2026) grows the gap too, but never pauses.
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

    @Test
    @DisplayName("long gap with the pause loop seen → PAUSE (#547 hold)")
    void pauseWithPausedTick() {
        assertEquals(ResumeWatchdog.GapKind.PAUSE, ResumeWatchdog.classifyGap(1_000 * MS, 3_288 * MS, true));
        assertEquals(ResumeWatchdog.GapKind.PAUSE, ResumeWatchdog.classifyGap(1_000 * MS, 600_000 * MS, true));
    }

    @Test
    @DisplayName("long gap without the pause loop → STALL (latest.log-37: 2647 ms and 4039 ms, 'Can't keep up')")
    void stallBetweenTicks() {
        assertEquals(ResumeWatchdog.GapKind.STALL, ResumeWatchdog.classifyGap(1_000 * MS, 3_647 * MS, false));
        assertEquals(ResumeWatchdog.GapKind.STALL, ResumeWatchdog.classifyGap(1_000 * MS, 5_039 * MS, false));
    }

    @Test
    @DisplayName("short gap → NONE whether or not a paused tick was seen")
    void shortGapIsNone() {
        assertEquals(ResumeWatchdog.GapKind.NONE, ResumeWatchdog.classifyGap(1_000 * MS, 1_050 * MS, false));
        assertEquals(ResumeWatchdog.GapKind.NONE, ResumeWatchdog.classifyGap(1_000 * MS, 1_050 * MS, true));
    }

    @Test
    @DisplayName("no baseline → NONE")
    void noBaselineIsNone() {
        assertEquals(ResumeWatchdog.GapKind.NONE, ResumeWatchdog.classifyGap(0L, 10_000 * MS, true));
    }
}
