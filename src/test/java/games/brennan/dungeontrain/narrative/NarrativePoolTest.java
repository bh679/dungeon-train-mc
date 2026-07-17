package games.brennan.dungeontrain.narrative;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link NarrativePool#applyResponse} (the relay {@code /narratives/pool} JSON parser) and the
 * snapshot readers ({@link NarrativePool#resolve}, {@link NarrativePool#pickUnstarted},
 * {@link NarrativePool#approvedTotal}) directly — no server / network needed. Static pool state is reset
 * before each test.
 */
final class NarrativePoolTest {

    @BeforeEach
    void reset() {
        NarrativePool.clear();
    }

    @Test
    @DisplayName("applyResponse: parses series + letters and captures the approved-letter total")
    void parsesSeries() {
        String body = "{\"ok\":true,\"total\":5,\"series\":["
                + "{\"seriesId\":\"s1\",\"author\":\"Alice\",\"letters\":["
                + "{\"letterIndex\":1,\"title\":\"One\",\"pages\":[\"p1\"]},"
                + "{\"letterIndex\":2,\"title\":\"Two\",\"pages\":[\"p2a\",\"p2b\"]}]}]}";
        NarrativePool.applyResponse(body);

        assertEquals(5, NarrativePool.approvedTotal(), "total is the approved-LETTER count, not series");
        assertFalse(NarrativePool.isEmpty());
        Optional<NarrativePool.Series> s = NarrativePool.resolve("s1");
        assertTrue(s.isPresent());
        assertEquals("Alice", s.get().author());
        assertEquals(2, s.get().letters().size());
        assertEquals(1, s.get().letters().get(0).letterIndex());
        assertEquals(List.of("p2a", "p2b"), s.get().letters().get(1).pages());
        assertTrue(NarrativePool.resolve("nope").isEmpty());
    }

    @Test
    @DisplayName("applyResponse: total is captured even when the series window is empty")
    void totalWithoutWindow() {
        NarrativePool.applyResponse("{\"ok\":true,\"total\":7,\"series\":[]}");
        assertEquals(7, NarrativePool.approvedTotal());
        assertTrue(NarrativePool.isEmpty());
    }

    @Test
    @DisplayName("applyResponse: a series with no letters is dropped (not servable)")
    void dropsEmptySeries() {
        String body = "{\"ok\":true,\"total\":1,\"series\":["
                + "{\"seriesId\":\"empty\",\"author\":\"X\",\"letters\":[]},"
                + "{\"seriesId\":\"ok\",\"author\":\"Y\",\"letters\":[{\"letterIndex\":1,\"pages\":[\"p\"]}]}]}";
        NarrativePool.applyResponse(body);
        assertTrue(NarrativePool.resolve("empty").isEmpty(), "a series with no approved letters is not servable");
        assertTrue(NarrativePool.resolve("ok").isPresent());
    }

    @Test
    @DisplayName("applyResponse: a not-ok / missing-series reply keeps the last good snapshot")
    void notOkKeepsSnapshot() {
        NarrativePool.applyResponse("{\"ok\":true,\"total\":2,\"series\":["
                + "{\"seriesId\":\"keep\",\"letters\":[{\"letterIndex\":1,\"pages\":[\"p\"]}]}]}");
        assertTrue(NarrativePool.resolve("keep").isPresent());
        NarrativePool.applyResponse("{\"ok\":false}");
        assertTrue(NarrativePool.resolve("keep").isPresent(), "snapshot survives a not-ok reply");
        NarrativePool.applyResponse("{\"ok\":true}");
        assertTrue(NarrativePool.resolve("keep").isPresent(), "snapshot survives a missing-series reply");
    }

    @Test
    @DisplayName("an empty series array clears the snapshot (relay owns starvation-recycle server-side)")
    void emptySeriesClearsSnapshot() {
        String good = "{\"ok\":true,\"total\":1,\"series\":["
                + "{\"seriesId\":\"s\",\"letters\":[{\"letterIndex\":1,\"pages\":[\"p\"]}]}]}";
        NarrativePool.applyResponse(good);
        assertTrue(NarrativePool.resolve("s").isPresent());
        // With server-side session state a zero-series reply is unambiguous — the relay recycles an
        // exhausted session itself, so an empty window means a genuinely empty pool → clear the snapshot.
        NarrativePool.applyResponse("{\"ok\":true,\"total\":0,\"series\":[]}");
        assertTrue(NarrativePool.isEmpty(), "an empty reply clears the snapshot");
    }

    @Test
    @DisplayName("pickUnstarted: deterministic, skips excluded/started series, empty when all excluded")
    void pickUnstartedFilters() {
        String body = "{\"ok\":true,\"total\":2,\"series\":["
                + "{\"seriesId\":\"a\",\"letters\":[{\"letterIndex\":1,\"pages\":[\"p\"]}]},"
                + "{\"seriesId\":\"b\",\"letters\":[{\"letterIndex\":1,\"pages\":[\"p\"]}]}]}";
        NarrativePool.applyResponse(body);

        Optional<NarrativePool.Series> pick1 = NarrativePool.pickUnstarted(42L, Set.of());
        Optional<NarrativePool.Series> pick2 = NarrativePool.pickUnstarted(42L, Set.of());
        assertTrue(pick1.isPresent());
        assertEquals(pick1.get().seriesId(), pick2.get().seriesId(), "same seed → same pick (deterministic)");

        Optional<NarrativePool.Series> other = NarrativePool.pickUnstarted(42L, Set.of(pick1.get().seriesId()));
        assertTrue(other.isPresent());
        assertNotEquals(pick1.get().seriesId(), other.get().seriesId(), "excluded series is skipped");

        assertTrue(NarrativePool.pickUnstarted(42L, Set.of("a", "b")).isEmpty(), "all started → nothing to start");
    }

    @Test
    @DisplayName("langParam: emits &lang= for a real locale, nothing for blank/null (back-compat)")
    void langParam() {
        assertEquals("&lang=en_us", NarrativePool.langParam("en_us"));
        assertEquals("&lang=zh_cn", NarrativePool.langParam("zh_cn"));
        assertEquals("", NarrativePool.langParam(""), "blank → no param (relay stays unfiltered)");
        assertEquals("", NarrativePool.langParam("   "));
        assertEquals("", NarrativePool.langParam(null));
    }
}
