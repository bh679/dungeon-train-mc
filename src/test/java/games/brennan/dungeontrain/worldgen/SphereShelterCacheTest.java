package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.worldgen.SphereGravityShelter.Exposure;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Expiry, anchor and capacity rules of the per-entity shelter memo. */
final class SphereShelterCacheTest {

    private static final Object CARRIAGE_A = new Object();
    private static final Object CARRIAGE_B = new Object();

    /** A classifier that counts its calls and answers whatever it is told to. */
    private static final class Scan implements Supplier<Exposure> {
        final AtomicInteger calls = new AtomicInteger();
        Exposure answer = Exposure.RIDER;

        @Override
        public Exposure get() {
            calls.incrementAndGet();
            return answer;
        }
    }

    @Test
    @DisplayName("a verdict is served from the memo for TTL ticks, then rescanned on the tick it expires")
    void expiry() {
        SphereShelterCache cache = new SphereShelterCache(5, 100);
        Scan scan = new Scan();
        assertEquals(Exposure.RIDER, cache.get(7, CARRIAGE_A, 100, scan));
        assertEquals(1, scan.calls.get());
        scan.answer = Exposure.SHELTERED;                      // the world changes; the memo doesn't see it yet
        for (long t = 101; t <= 104; t++) {
            assertEquals(Exposure.RIDER, cache.get(7, CARRIAGE_A, t, scan), "tick " + t + " still cached");
        }
        assertEquals(1, scan.calls.get(), "no rescan inside the TTL");
        assertEquals(Exposure.SHELTERED, cache.get(7, CARRIAGE_A, 105, scan), "expired at now + TTL");
        assertEquals(2, scan.calls.get());
        assertEquals(Exposure.SHELTERED, cache.get(7, CARRIAGE_A, 106, scan));
        assertEquals(2, scan.calls.get(), "the rescan is itself memoised");
    }

    @Test
    @DisplayName("a different anchor (another carriage, or none) rescans at once, even inside the TTL")
    void anchorChange() {
        SphereShelterCache cache = new SphereShelterCache(5, 100);
        Scan scan = new Scan();
        assertEquals(Exposure.RIDER, cache.get(7, CARRIAGE_A, 100, scan));
        scan.answer = Exposure.FREE;
        assertEquals(Exposure.FREE, cache.get(7, null, 101, scan), "stepped off: no tracking carriage");
        assertEquals(2, scan.calls.get());
        scan.answer = Exposure.SHELTERED;
        assertEquals(Exposure.SHELTERED, cache.get(7, CARRIAGE_B, 102, scan), "onto another carriage");
        assertEquals(3, scan.calls.get());
        assertEquals(Exposure.SHELTERED, cache.get(7, CARRIAGE_B, 103, scan), "same carriage: cached");
        assertEquals(3, scan.calls.get());
        assertEquals(Exposure.SHELTERED, cache.get(7, null, 104, scan), "null anchor after a non-null one rescans");
        assertEquals(4, scan.calls.get());
        assertEquals(Exposure.SHELTERED, cache.get(7, null, 105, scan), "null anchor twice: cached");
        assertEquals(4, scan.calls.get());
    }

    @Test
    @DisplayName("invalidate and clear forget entries; other entities are untouched by invalidate")
    void invalidation() {
        SphereShelterCache cache = new SphereShelterCache(5, 100);
        Scan scan = new Scan();
        cache.get(1, CARRIAGE_A, 10, scan);
        cache.get(2, CARRIAGE_A, 10, scan);
        assertEquals(2, cache.size());
        cache.invalidate(1);
        assertEquals(1, cache.size());
        scan.answer = Exposure.FREE;
        assertEquals(Exposure.FREE, cache.get(1, CARRIAGE_A, 11, scan), "the invalidated entity rescans");
        assertEquals(Exposure.RIDER, cache.get(2, CARRIAGE_A, 11, scan), "the other is still cached");
        cache.clear();
        assertEquals(0, cache.size());
        assertEquals(Exposure.FREE, cache.get(2, CARRIAGE_A, 12, scan), "cleared: rescans");
    }

    @Test
    @DisplayName("at the cap: expired entries are dropped first; when none has expired the memo is reset")
    void capacity() {
        SphereShelterCache cache = new SphereShelterCache(5, 8);
        Scan scan = new Scan();
        for (int id = 0; id < 4; id++) cache.get(id, null, 0, scan);     // expire at 5
        for (int id = 4; id < 8; id++) cache.get(id, null, 3, scan);     // expire at 8
        assertEquals(8, cache.size());
        cache.get(100, null, 6, scan);                                  // full; the first four have expired
        assertEquals(5, cache.size(), "four expired dropped, one added");
        for (int id = 4; id < 8; id++) {
            assertTrue(cache.size() >= 5);
            cache.get(id, null, 6, scan);
        }
        int before = scan.calls.get();
        assertEquals(9, scan.calls.get(), "the four survivors were still cached");
        for (int id = 101; id < 104; id++) cache.get(id, null, 6, scan);
        assertEquals(8, cache.size());
        cache.get(200, null, 6, scan);                                  // full, nothing expired: reset
        assertEquals(1, cache.size(), "memo reset to the newcomer");
        assertEquals(before + 4, scan.calls.get());
        cache.get(4, null, 7, scan);
        assertEquals(before + 5, scan.calls.get(), "an evicted entity rescans");
    }

    @Test
    @DisplayName("a known entity refreshing past the cap never triggers eviction")
    void refreshDoesNotEvict() {
        SphereShelterCache cache = new SphereShelterCache(5, 4);
        Scan scan = new Scan();
        for (int id = 0; id < 4; id++) cache.get(id, null, 0, scan);
        cache.get(2, null, 5, scan);                                    // expired: rescans in place
        assertEquals(4, cache.size());
        assertEquals(5, scan.calls.get());
    }
}
