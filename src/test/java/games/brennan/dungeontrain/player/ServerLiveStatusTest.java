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
}
