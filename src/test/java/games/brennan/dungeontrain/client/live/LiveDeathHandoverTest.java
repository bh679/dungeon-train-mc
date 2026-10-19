package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveDeathHandoverTest {

    @Test
    void targetIsTwoSegmentsPastTheDeathAndItsBeat() {
        // 10 s segments: a death at 34 s is in segment 3, its beat (36 s) too.
        assertEquals(5, LiveDeathHandover.targetSegment(34.0, 10));
        // The beat crosses into the next segment.
        assertEquals(6, LiveDeathHandover.targetSegment(39.0, 10));
        // Right at a boundary.
        assertEquals(6, LiveDeathHandover.targetSegment(38.0, 10));
        // Dying at once, and short segments.
        assertEquals(2, LiveDeathHandover.targetSegment(0.0, 10));
        // 2 s segments (Quicker Streams): two segments is only 4 s, so the slack is 6 s = 3 segments.
        assertEquals(9, LiveDeathHandover.targetSegment(11.0, 2));
        assertEquals(2, LiveDeathHandover.slackSegments(10));
        assertEquals(3, LiveDeathHandover.slackSegments(2));
        assertEquals(2, LiveDeathHandover.slackSegments(3));
    }

    @Test
    void waitsForTheTargetSegment() {
        long target = LiveDeathHandover.targetSegment(34.0, 10);
        assertFalse(LiveDeathHandover.ready(-1, target, 0, 1000));
        assertFalse(LiveDeathHandover.ready(target - 1, target, 0, 30_000));
        assertTrue(LiveDeathHandover.ready(target, target, 0, 30_000));
        assertTrue(LiveDeathHandover.ready(target + 3, target, 0, 30_000));
    }

    @Test
    void goesOutAnywayWhenUploadsStall() {
        assertFalse(LiveDeathHandover.ready(-1, 5, 1000, 1000 + LiveDeathHandover.FALLBACK_MS - 1));
        assertTrue(LiveDeathHandover.ready(-1, 5, 1000, 1000 + LiveDeathHandover.FALLBACK_MS));
    }

    @Test
    void releasesOnce() {
        LiveDeathHandover h = new LiveDeathHandover(34.0, 10, 0);
        assertFalse(h.release(4, 10_000));
        assertTrue(h.release(5, 20_000));
        assertFalse(h.release(6, 30_000));
    }
}
