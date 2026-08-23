package games.brennan.dungeontrain.narrative;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    @DisplayName("uuidParam: emits &uuid= for a real player, nothing for blank/null (optional end-to-end)")
    void uuidParam() {
        assertEquals("&uuid=0123abcd", SharedBookPool.uuidParam("0123abcd"));
        // Dash-stripped 32-hex is the shape WorldLanguage.hostUuidConsented produces.
        assertEquals("&uuid=00112233445566778899aabbccddeeff",
                SharedBookPool.uuidParam("00112233445566778899aabbccddeeff"));
        assertEquals("", SharedBookPool.uuidParam(""), "blank → no param (relay window stays unpersonalised)");
        assertEquals("", SharedBookPool.uuidParam("   "));
        assertEquals("", SharedBookPool.uuidParam(null));
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

    @Test
    @DisplayName("the political tag parses when present and defaults to false when absent")
    void politicalTagParsing() {
        SharedBookPool.applyResponse("{\"ok\":true,\"total\":2,\"books\":["
                + "{\"id\":1,\"title\":\"t1\",\"author\":\"a\",\"lang\":\"en_us\",\"pages\":[\"p\"],\"political\":true},"
                + "{\"id\":2,\"title\":\"t2\",\"author\":\"a\",\"lang\":\"en_us\",\"pages\":[\"p\"]}]}", "en_us");
        List<SharedBookPool.PoolBook> books = SharedBookPool.snapshot();
        assertEquals(2, books.size());
        assertTrue(books.get(0).political());
        // Absent is the shape EVERY untagged book arrives in, and the shape a relay too old to know
        // about the tag sends for all of them — it must never read as tagged.
        assertFalse(books.get(1).political());
    }

    @Test
    @DisplayName("a non-boolean political value is treated as untagged rather than throwing")
    void politicalTagGarbageIsUntagged() {
        SharedBookPool.applyResponse("{\"ok\":true,\"total\":1,\"books\":["
                + "{\"id\":1,\"title\":\"t1\",\"author\":\"a\",\"lang\":\"en_us\",\"pages\":[\"p\"],\"political\":null}]}", "en_us");
        assertEquals(1, SharedBookPool.snapshot().size(), "the book still parses");
        assertFalse(SharedBookPool.snapshot().get(0).political());
    }

    @Test
    @DisplayName("A book's moderation status is parsed, and an absent one reads as released")
    void statusParsing() {
        // `status` rides along only on the writer's-own-shelf response (mine=1); every ordinary pool
        // response omits it entirely.
        SharedBookPool.applyResponse("{\"ok\":true,\"books\":[" +
            "{\"id\":1,\"title\":\"A\",\"author\":\"A\",\"pages\":[\"p\"],\"status\":\"pending\"}," +
            "{\"id\":2,\"title\":\"B\",\"author\":\"A\",\"pages\":[\"p\"],\"status\":\"rejected\"}," +
            "{\"id\":3,\"title\":\"C\",\"author\":\"A\",\"pages\":[\"p\"]}]}", "en_us");
        List<SharedBookPool.PoolBook> books = SharedBookPool.snapshot();
        assertEquals("pending", byId(books, 1).status());
        assertEquals("rejected", byId(books, 2).status());
        assertEquals(SharedBookPool.STATUS_APPROVED, byId(books, 3).status());
        assertTrue(byId(books, 1).isWithheld());
        assertFalse(byId(books, 3).isWithheld());
    }

    @Test
    @DisplayName("An absent or blank status is released; an UNRECOGNISED one is shown as released but never shelved")
    void statusGarbageResolvesBothWaysOnPurpose() {
        SharedBookPool.applyResponse("{\"ok\":true,\"books\":[" +
            "{\"id\":1,\"title\":\"A\",\"author\":\"A\",\"pages\":[\"p\"],\"status\":null}," +
            "{\"id\":2,\"title\":\"B\",\"author\":\"A\",\"pages\":[\"p\"],\"status\":\"  \"}," +
            "{\"id\":3,\"title\":\"C\",\"author\":\"A\",\"pages\":[\"p\"],\"status\":\"sixth_state_from_a_newer_relay\"}]}",
            "en_us");

        // Null and blank are simply "the relay said nothing" — an ordinary community book, both ways.
        for (int id : new int[] {1, 2}) {
            assertFalse(byId(SharedBookPool.snapshot(), id).isWithheld());
            assertFalse(BookModerationState.fromStatus(byId(SharedBookPool.snapshot(), id).status()).isWithheld());
        }

        // A status this jar does not recognise splits the two questions apart, which is the point:
        // nothing is tinted or claimed about it (that failure would be loud and wrong), but it is
        // still kept off a shelf other players can read (that failure would be quiet and worse).
        SharedBookPool.PoolBook unknown = byId(SharedBookPool.snapshot(), 3);
        assertEquals(BookModerationState.APPROVED, BookModerationState.fromStatus(unknown.status()),
            "an unknown state must not invent a verdict to show the reader");
        assertTrue(unknown.isWithheld(), "...but must not be shelved where strangers can read it");
    }

    private static SharedBookPool.PoolBook byId(List<SharedBookPool.PoolBook> books, int id) {
        return books.stream().filter(b -> b.id() == id).findFirst().orElseThrow();
    }
}
