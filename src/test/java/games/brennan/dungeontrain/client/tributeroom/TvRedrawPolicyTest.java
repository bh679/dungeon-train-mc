package games.brennan.dungeontrain.client.tributeroom;

import org.junit.jupiter.api.Test;

import static games.brennan.dungeontrain.client.tributeroom.TvRedrawPolicy.*;
import static org.junit.jupiter.api.Assertions.*;

class TvRedrawPolicyTest {

    private static final long NOW = 1_000_000L;
    private static final long NEVER = Long.MIN_VALUE;
    private static final double NEAR = 4 * 4;
    private static final double FAR = 40 * 40;

    @Test
    void neverSeenIsNeverDrawn() {
        assertFalse(shouldDraw(NOW, NEVER, NEVER, true, true, NEAR));
    }

    @Test
    void unseenForLongerThanTheGraceIsNotDrawnEvenWhenDirtyOrAimed() {
        long seen = NOW - SEEN_GRACE_MS - 1;
        assertFalse(shouldDraw(NOW, seen, NEVER, true, true, NEAR));
    }

    @Test
    void seenWithNoPictureYetDrawsAtOnce() {
        assertTrue(shouldDraw(NOW, NOW, NEVER, false, false, FAR));
    }

    @Test
    void aimedDrawsEveryFrame() {
        assertTrue(shouldDraw(NOW, NOW, NOW, false, true, FAR));
    }

    @Test
    void dirtyDrawsAtOnce() {
        assertTrue(shouldDraw(NOW, NOW, NOW, true, false, FAR));
    }

    @Test
    void nearRedrawsAtTheNearInterval() {
        assertFalse(shouldDraw(NOW, NOW, NOW - NEAR_INTERVAL_MS + 1, false, false, NEAR));
        assertTrue(shouldDraw(NOW, NOW, NOW - NEAR_INTERVAL_MS, false, false, NEAR));
    }

    @Test
    void farRedrawsLessOften() {
        assertFalse(shouldDraw(NOW, NOW, NOW - NEAR_INTERVAL_MS, false, false, FAR));
        assertTrue(shouldDraw(NOW, NOW, NOW - FAR_INTERVAL_MS, false, false, FAR));
    }

    @Test
    void releasesOnlyAfterLongUnseen() {
        assertFalse(shouldRelease(NOW, NOW - RELEASE_AFTER_MS));
        assertTrue(shouldRelease(NOW, NOW - RELEASE_AFTER_MS - 1));
        assertTrue(shouldRelease(NOW, NEVER));
    }
}
