package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.net.relay.LiveFeedClient.Ahead;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.SignedPut;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveAheadStockTest {

    private static Ahead batch(long from, int count, long expiresAt) {
        Map<String, SignedPut> urls = new LinkedHashMap<>();
        for (long n = from; n < from + count; n++) {
            String name = String.format("seg%05d.ts", n);
            urls.put(name, new SignedPut("https://r2.invalid/" + name, Map.of(), expiresAt));
        }
        urls.put(LivePlaylist.FILE_NAME, new SignedPut("https://r2.invalid/pl", Map.of(), expiresAt));
        return new Ahead(from, expiresAt, urls);
    }

    @Test
    void emptyStockWantsMoreAndHoldsNothing() {
        LiveAheadStock s = new LiveAheadStock();
        assertTrue(s.wantsMore(0));
        assertFalse(s.holding(0));
        assertEquals(-1, s.from());
    }

    @Test
    void urlsExpireAMarginBeforeTheRelaySays() {
        LiveAheadStock s = new LiveAheadStock();
        s.add(batch(5, 2, 100_000));
        long edge = 100_000 - LiveAheadStock.EXPIRY_MARGIN_MS;
        assertNotNull(s.segment("seg00005.ts", edge - 1));
        assertNull(s.segment("seg00005.ts", edge));
        assertNull(s.playlist(edge));
        assertTrue(s.wantsMore(edge));
    }

    @Test
    void aSegmentUrlIsSpentOnceButThePlaylistUrlIsReused() {
        LiveAheadStock s = new LiveAheadStock();
        s.add(batch(0, 4, 100_000));
        s.take("seg00000.ts");
        s.take(LivePlaylist.FILE_NAME);
        assertNull(s.segment("seg00000.ts", 0));
        assertNotNull(s.playlist(0));
        assertEquals(3, s.usable(0));
    }

    @Test
    void wantsMoreOnceUnderHalfIsLeft() {
        LiveAheadStock s = new LiveAheadStock();
        s.add(batch(0, 4, 100_000));
        assertFalse(s.wantsMore(0));
        s.prune(Set.of("seg00000.ts", "seg00001.ts"), 0);
        assertFalse(s.wantsMore(0), "half left is enough");
        s.take("seg00002.ts");
        assertTrue(s.wantsMore(0));
    }

    @Test
    void aNewerBatchReplacesTheSameFiles() {
        LiveAheadStock s = new LiveAheadStock();
        s.add(batch(0, 2, 50_000));
        s.add(batch(1, 2, 200_000));
        assertEquals(1, s.from());
        assertEquals(200_000, s.segment("seg00001.ts", 0).expiresAt());
        assertEquals(50_000, s.segment("seg00000.ts", 0).expiresAt());
    }
}
