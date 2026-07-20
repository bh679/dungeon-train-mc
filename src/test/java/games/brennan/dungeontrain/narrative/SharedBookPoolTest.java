package games.brennan.dungeontrain.narrative;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link SharedBookPool#langParam} (the {@code /books/pool} language query fragment) and the
 * snapshot ACCUMULATION contract in {@link SharedBookPool#applyResponse}.
 *
 * <p>Accumulation is the subtle one. The relay hands over one weight tier at a time and marks those ids
 * "offered" for the session as it does, while the refresh timer fires on a fixed cadence regardless of
 * whether the game consumed the window. Replacing wholesale therefore stranded curated books until the
 * session recycled. These tests pin the merge, the bound, and the language-change reset.</p>
 */
final class SharedBookPoolTest {

    @BeforeEach
    void reset() {
        SharedBookPool.clear();
    }

    /** Minimal well-formed pool response carrying the given ids. */
    private static String body(int... ids) {
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"total\":100,\"books\":[");
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"id\":").append(ids[i])
              .append(",\"title\":\"t").append(ids[i])
              .append("\",\"author\":\"a\",\"lang\":\"en_us\",\"pages\":[\"p\"]}");
        }
        return sb.append("]}").toString();
    }

    private static List<Integer> snapshotIds() {
        return SharedBookPool.snapshot().stream().map(SharedBookPool.PoolBook::id).toList();
    }

    @Test
    @DisplayName("langParam: emits &lang= for a real locale, nothing for blank/null (back-compat)")
    void langParam() {
        assertEquals("&lang=en_us", SharedBookPool.langParam("en_us"));
        assertEquals("&lang=pt_br", SharedBookPool.langParam("pt_br"));
        assertEquals("", SharedBookPool.langParam(""), "blank → no param (relay stays unfiltered)");
        assertEquals("", SharedBookPool.langParam("   "));
        assertEquals("", SharedBookPool.langParam(null));
    }

    @Test
    @DisplayName("successive fetches ACCUMULATE — a window that arrived is never stranded by the next one")
    void accumulatesAcrossFetches() {
        SharedBookPool.applyResponse(body(1, 2, 3), "en_us");   // weight-5 tier
        SharedBookPool.applyResponse(body(4, 5, 6), "en_us");   // weight-4 tier, timer fired early
        assertEquals(List.of(1, 2, 3, 4, 5, 6), snapshotIds(),
            "the first window must survive: the relay will not re-offer those ids this session");
    }

    @Test
    @DisplayName("re-offered ids do not duplicate, and keep their original position")
    void mergeDedupesById() {
        SharedBookPool.applyResponse(body(1, 2, 3), "en_us");
        SharedBookPool.applyResponse(body(3, 4), "en_us"); // 3 seen again after a session recycle
        assertEquals(List.of(1, 2, 3, 4), snapshotIds());
    }

    @Test
    @DisplayName("a language change REPLACES — accumulated books are the wrong language now")
    void languageChangeReplaces() {
        SharedBookPool.applyResponse(body(1, 2, 3), "en_us");
        SharedBookPool.applyResponse(body(31, 32), "zh_cn");
        assertEquals(List.of(31, 32), snapshotIds(), "English books must not linger for a Chinese player");
    }

    @Test
    @DisplayName("an empty window keeps what we have (same language) rather than wiping loot")
    void emptyWindowKeepsAccumulated() {
        SharedBookPool.applyResponse(body(1, 2, 3), "en_us");
        SharedBookPool.applyResponse(body(), "en_us");
        assertEquals(List.of(1, 2, 3), snapshotIds(),
            "an empty mid-session window is not evidence the earlier books stopped existing");
    }

    @Test
    @DisplayName("an empty window DOES clear on a language change")
    void emptyWindowClearsOnLanguageChange() {
        SharedBookPool.applyResponse(body(1, 2, 3), "en_us");
        SharedBookPool.applyResponse(body(), "ko_kr");
        assertTrue(SharedBookPool.isEmpty(), "no books for the new language → serve nothing rather than the old language");
    }

    @Test
    @DisplayName("accumulation is bounded: oldest entries evicted past MAX_SNAPSHOT")
    void boundedByMaxSnapshot() {
        int n = SharedBookPool.MAX_SNAPSHOT;
        int[] first = new int[n];
        for (int i = 0; i < n; i++) first[i] = i + 1;
        SharedBookPool.applyResponse(body(first), "en_us");
        assertEquals(n, SharedBookPool.snapshot().size());
        SharedBookPool.applyResponse(body(n + 1, n + 2), "en_us");
        assertEquals(n, SharedBookPool.snapshot().size(), "stays capped");
        List<Integer> ids = snapshotIds();
        assertEquals(3, ids.get(0), "the two oldest were evicted");
        assertTrue(ids.containsAll(List.of(n + 1, n + 2)), "the newest arrivals are retained");
    }

    @Test
    @DisplayName("a malformed reply keeps the last good snapshot")
    void malformedReplyKeepsSnapshot() {
        SharedBookPool.applyResponse(body(1, 2), "en_us");
        SharedBookPool.applyResponse("{\"ok\":true,\"total\":5}", "en_us"); // no books array
        assertEquals(List.of(1, 2), snapshotIds());
    }
}
