package games.brennan.dungeontrain.debug;

import games.brennan.dungeontrain.debug.BackwardGenTrace.Reason;
import games.brennan.dungeontrain.debug.BackwardGenTrace.Sample;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-core coverage for the backward-generation trace: the emission throttle,
 * the blocked-tick accounting, and the derived discriminators a stalled ride is
 * read by ({@code span} / {@code skew}).
 *
 * <p>Deliberately no level, no Sable, no train — the throttle is the only part
 * that can silently lose the one line that matters (the transition into the
 * stall), so it is the part worth testing without a game running.</p>
 */
class BackwardGenTraceTest {

    /** A reason CHANGE must always emit, however recently the last line went out. */
    @Test
    void reasonChangeAlwaysEmits() {
        assertTrue(BackwardGenTrace.shouldEmit(Reason.SPAWNED, Reason.EDGE_DEFER, 100L, 101L));
        assertTrue(BackwardGenTrace.shouldEmit(Reason.EDGE_DEFER, Reason.GATE_CULL_LATCH, 100L, 100L));
    }

    /** The very first sample for a train (no previous reason) always emits. */
    @Test
    void firstSampleEmits() {
        assertTrue(BackwardGenTrace.shouldEmit(null, Reason.NO_NEED, Long.MIN_VALUE, 0L));
    }

    /** An unchanged reason is throttled to one line per sample period. */
    @Test
    void unchangedReasonIsThrottledToThePeriod() {
        assertFalse(BackwardGenTrace.shouldEmit(Reason.EDGE_DEFER, Reason.EDGE_DEFER,
            100L, 100L + BackwardGenTrace.SAMPLE_PERIOD_TICKS - 1));
        assertTrue(BackwardGenTrace.shouldEmit(Reason.EDGE_DEFER, Reason.EDGE_DEFER,
            100L, 100L + BackwardGenTrace.SAMPLE_PERIOD_TICKS));
    }

    /** No active block ⇒ zero blocked ticks; an active one counts from its start. */
    @Test
    void blockedForCountsFromTheBlockStart() {
        assertEquals(0L, BackwardGenTrace.blockedFor(null, 500L));
        assertEquals(0L, BackwardGenTrace.blockedFor(500L, 500L));
        assertEquals(120L, BackwardGenTrace.blockedFor(380L, 500L));
        // Defensive: a world reload can walk the game clock backwards.
        assertEquals(0L, BackwardGenTrace.blockedFor(600L, 500L));
    }

    /** Only the reasons that mean "no group was added" count as blocking. */
    @Test
    void spawnReasonsAreNotBlocking() {
        assertFalse(Reason.SPAWNED.isBlocking());
        assertFalse(Reason.FILL_RUN.isBlocking());
        assertTrue(Reason.NO_NEED.isBlocking());
        assertTrue(Reason.EDGE_DEFER.isBlocking());
        assertTrue(Reason.GATE_CULL_LATCH.isBlocking());
        assertTrue(Reason.CHUNKGEN_DEFER.isBlocking());
    }

    /**
     * {@code span} is the ghost-anchor discriminator (registry min sitting below
     * the visible tail) and {@code skew} the frame-divergence one. Both are
     * derived, so a sign error would silently mis-diagnose the ride.
     */
    @Test
    void spanAndSkewAreDerivedFromTheRightPair() {
        Sample s = sample(Reason.NO_NEED, /*playerPIdx*/ -40, /*occupied*/ -43,
            /*registryMin*/ -60, /*visibleTail*/ -48);
        assertEquals(12, s.span());   // visibleTail − registryMin: 12 ghost indices
        assertEquals(-3, s.skew());   // occupied − playerPIdx: the lead frame reads 3 high
    }

    /** An unknown occupied group reports zero skew rather than throwing. */
    @Test
    void skewIsZeroWhenTheOccupiedGroupIsUnknown() {
        assertEquals(0, sample(Reason.NOT_NEAR, 0, null, 0, 0).skew());
    }

    /** The formatted line carries the fields the analysis script parses. */
    @Test
    void formatCarriesTheDiscriminatorFields() {
        String line = sample(Reason.EDGE_DEFER, -40, -43, -60, -48).format(UUID.nameUUIDFromBytes(new byte[]{1}));
        assertTrue(line.contains("[bwdgen]"), line);
        assertTrue(line.contains("reason=EDGE_DEFER"), line);
        assertTrue(line.contains("span=12"), line);
        assertTrue(line.contains("skew=-3"), line);
        assertTrue(line.contains("registryMin=-60"), line);
        assertTrue(line.contains("visibleTail=-48"), line);
    }

    private static Sample sample(Reason reason, int playerPIdx, Integer occupied,
                                 int registryMin, int visibleTail) {
        return new Sample(
            1234L, reason, 0L, playerPIdx, occupied, 512.5,
            /*minNeeded*/ -55, registryMin, visibleTail,
            /*registryCount*/ 20, /*visibleCount*/ 16,
            /*anchor*/ registryMin - 3, /*deficit*/ 5,
            /*ticksPending*/ -1L, /*latchAge*/ -1L, /*edgeSub*/ null,
            /*forceLoaded*/ 4, /*chunkWait*/ -1L, /*targetCount*/ 30);
    }
}
