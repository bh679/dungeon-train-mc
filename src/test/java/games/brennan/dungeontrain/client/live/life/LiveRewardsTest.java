package games.brennan.dungeontrain.client.live.life;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveRewardsTest {

    private static final long NOW = Instant.parse("2026-10-17T00:00:00Z").toEpochMilli();

    @Test
    void anAbsentOrMalformedBlockMeansNothingIsOn() {
        assertEquals(LiveRewards.NONE, LiveRewards.parseBody("{\"ok\":true}"));
        assertEquals(LiveRewards.NONE, LiveRewards.parseBody("{\"ok\":true,\"rewards\":{}}"));
        assertEquals(LiveRewards.NONE, LiveRewards.parseBody("not json"));
        assertFalse(LiveRewards.NONE.active(LiveRewards.QUICKER_STREAMS, NOW));
    }

    @Test
    void activeUntilReadsMillisAnInstantOrADate() {
        LiveRewards r = LiveRewards.parseBody("{\"rewards\":["
            + "{\"id\":\"quicker_streams\",\"activeUntil\":\"2026-11-12\",\"by\":\"Kestrel\",\"queued\":[{\"by\":\"Moth\"}]},"
            + "{\"id\":\"replays_kept\",\"activeUntil\":\"2026-11-03T10:00:00Z\",\"by\":\"Moth\"},"
            + "{\"id\":\"two_streams\",\"activeUntil\":" + (NOW - 1) + "},"
            + "{\"id\":\"hd_streams\",\"activeUntil\":\"garbage\"},"
            + "{\"activeUntil\":1}]}");
        assertEquals(4, r.rewards().size());
        assertTrue(r.active(LiveRewards.QUICKER_STREAMS, NOW));
        assertEquals(List.of("Moth"), r.find(LiveRewards.QUICKER_STREAMS).queuedBy());
        assertEquals("Kestrel", r.find(LiveRewards.QUICKER_STREAMS).by());
        assertTrue(r.active(LiveRewards.REPLAYS_KEPT, NOW));
        assertFalse(r.active(LiveRewards.TWO_STREAMS, NOW));
        assertFalse(r.active(LiveRewards.HD_STREAMS, NOW));
        assertFalse(r.active(LiveRewards.VOICE_ON_STREAM, NOW));
    }
}
