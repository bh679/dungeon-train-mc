package games.brennan.dungeontrain.player;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A relay "live" answer counts only while fresh; stale or never-fetched reads as not live. */
final class ServerLiveStatusTest {

    @Test
    @DisplayName("live only while the last relay answer is fresh")
    void freshness() {
        long at = 1_000_000L;
        assertTrue(ServerLiveStatus.isFresh(true, at, at + 1_000L));
        assertFalse(ServerLiveStatus.isFresh(true, at, at + ServerLiveStatus.STALE_MS));
        assertFalse(ServerLiveStatus.isFresh(false, at, at + 1_000L));
        assertFalse(ServerLiveStatus.isFresh(true, 0L, 1_000L));
    }

    @Test
    @DisplayName("someone else is live: another local streamer, or a fresh relay answer not naming you")
    void someoneElse() {
        java.util.UUID me = java.util.UUID.randomUUID();
        java.util.UUID other = java.util.UUID.randomUUID();
        assertTrue(ServerLiveStatus.someoneElseLive(other, me, false, null, "Dev"), "another streamer on this server");
        assertFalse(ServerLiveStatus.someoneElseLive(me, me, false, null, "Dev"), "only you, locally");
        assertTrue(ServerLiveStatus.someoneElseLive(null, me, true, "Alex", "Dev"), "relay names someone else");
        assertTrue(ServerLiveStatus.someoneElseLive(null, me, true, null, "Dev"), "relay gives no name");
        assertFalse(ServerLiveStatus.someoneElseLive(null, me, true, "dev", "Dev"), "relay names you");
        assertFalse(ServerLiveStatus.someoneElseLive(null, me, false, "Alex", "Dev"), "stale relay answer");
        assertFalse(ServerLiveStatus.someoneElseLive(null, me, false, null, "Dev"), "nobody live");
    }
}
