package games.brennan.dungeontrain.narrative;

import games.brennan.dungeontrain.narrative.SharedBookPool.PoolBook;
import games.brennan.dungeontrain.narrative.SharedBookSelector.PlayerContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Several reading languages: each pick's language is rolled by weight among those with books left. */
final class SharedBookSelectorMultiLanguageTest {

    private static PoolBook book(int id, String lang) {
        return new PoolBook(id, "t" + id, "Author" + id, List.of("p"), lang, 0, true, false,
                SharedBookPool.STATUS_UNKNOWN, false, 0, 0);
    }

    private static List<PoolBook> books(int firstId, int n, String lang) {
        List<PoolBook> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(book(firstId + i, lang));
        return out;
    }

    private static PlayerContext reader(String lang, Map<String, Integer> families) {
        return new PlayerContext(lang, id -> false, id -> false, id -> 0, 0, 10, false, false,
                b -> false, families);
    }

    private static Map<String, Integer> weights(Object... pairs) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) out.put((String) pairs[i], (Integer) pairs[i + 1]);
        return out;
    }

    private static Map<String, Integer> tally(List<PoolBook> pool, PlayerContext ctx, int n) {
        Map<String, Integer> out = new HashMap<>();
        for (long seed = 0; seed < n; seed++) {
            PoolBook b = SharedBookSelector.select(pool, ctx, seed).orElseThrow();
            out.merge(LanguageFamily.of(b.lang()), 1, Integer::sum);
        }
        return out;
    }

    @Test
    @DisplayName("one language: identical picks to the single-language context")
    void singleLanguageUnchanged() {
        List<PoolBook> pool = new ArrayList<>(books(1, 30, "en_us"));
        pool.addAll(books(100, 30, "uk_ua"));
        PlayerContext plain = new PlayerContext("en_us", id -> false, id -> false, id -> 0, 0, 10, false, false,
                b -> false);
        PlayerContext listed = reader("en_us", weights("en", 1));
        for (long seed = 0; seed < 300; seed++) {
            assertEquals(SharedBookSelector.select(pool, plain, seed), SharedBookSelector.select(pool, listed, seed));
        }
    }

    @Test
    @DisplayName("weights 3:1 → about three in four picks from the heavier language")
    void weightedSplit() {
        List<PoolBook> pool = new ArrayList<>(books(1, 40, "en_us"));
        pool.addAll(books(100, 40, "uk_ua"));
        Map<String, Integer> t = tally(pool, reader("uk_ua", weights("uk", 3, "en", 1)), 4000);
        double ukShare = t.get("uk") / 4000.0;
        assertTrue(ukShare > 0.70 && ukShare < 0.80, "uk share " + ukShare);
    }

    @Test
    @DisplayName("equal weights → an even split, whatever the window sizes")
    void equalWeightsEven() {
        List<PoolBook> pool = new ArrayList<>(books(1, 90, "en_us"));
        pool.addAll(books(100, 5, "es_es"));
        Map<String, Integer> t = tally(pool, reader("en_us", weights("en", 1, "es", 1)), 4000);
        double esShare = t.get("es") / 4000.0;
        assertTrue(esShare > 0.45 && esShare < 0.55, "es share " + esShare);
    }

    @Test
    @DisplayName("a language with nothing unread left drops out; its share goes to the others")
    void exhaustedLanguageDropsOut() {
        List<PoolBook> pool = new ArrayList<>(books(1, 20, "en_us"));
        pool.addAll(books(100, 3, "uk_ua"));
        Set<Integer> read = Set.of(100, 101, 102);
        PlayerContext ctx = new PlayerContext("en_us", read::contains, id -> false, id -> 0, 0, 10, false, false,
                b -> false, weights("en", 1, "uk", 5));
        for (long seed = 0; seed < 300; seed++) {
            assertEquals("en", LanguageFamily.of(SharedBookSelector.select(pool, ctx, seed).orElseThrow().lang()));
        }
    }

    @Test
    @DisplayName("books in a language the player doesn't read never come while reading ones have unread books")
    void unlistedLanguageStaysOut() {
        List<PoolBook> pool = new ArrayList<>(books(1, 20, "en_us"));
        pool.addAll(books(100, 20, "uk_ua"));
        pool.addAll(books(200, 20, "fr_fr"));
        Map<String, Integer> t = tally(pool, reader("en_us", weights("en", 1, "uk", 1)), 1000);
        assertFalse(t.containsKey("fr"));
    }

    @Test
    @DisplayName("fallback: never a reading language — English unless read, then the biggest other")
    void fallbackSkipsReadingLanguages() {
        List<BookLanguages.FamilyTotal> totals = List.of(new BookLanguages.FamilyTotal("en", 100),
                new BookLanguages.FamilyTotal("zh", 50), new BookLanguages.FamilyTotal("es", 10));
        assertEquals("en", BookLanguages.fallbackFamilyFor(Set.of("uk", "es"), totals));
        assertEquals("es", BookLanguages.fallbackFamilyFor(Set.of("en", "zh"), totals));
        assertNull(BookLanguages.fallbackFamilyFor(Set.of("en", "zh", "es"), totals));
        PlayerContext ctx = reader("uk_ua", weights("uk", 1, "en", 1));
        assertEquals(0.0, SharedBookSelector.fallbackShare(0, "en", ctx));
    }

    @Test
    @DisplayName("knownEmpty: only when the list is known and the family has no books")
    void knownEmpty() {
        List<BookLanguages.FamilyTotal> totals = List.of(new BookLanguages.FamilyTotal("en", 100));
        assertTrue(BookLanguages.knownEmpty("eo", totals));
        assertFalse(BookLanguages.knownEmpty("en", totals));
        assertFalse(BookLanguages.knownEmpty("eo", List.of()));
    }

    @Test
    @DisplayName("needsFamilyFetch: true for an empty window or one with nothing unread by others")
    void needsFamilyFetch() {
        PlayerContext ctx = new PlayerContext("en_us", id -> id == 100, id -> false, id -> 0, 0, 10, false, false,
                b -> false, weights("en", 1, "uk", 1));
        assertTrue(SharedBookSelector.needsFamilyFetch(List.of(), "uk", ctx));
        assertTrue(SharedBookSelector.needsFamilyFetch(List.of(book(100, "uk_ua")), "uk", ctx));
        assertFalse(SharedBookSelector.needsFamilyFetch(List.of(book(100, "uk_ua"), book(101, "uk_ua")), "uk", ctx));
    }

    @Test
    @DisplayName("withWindows: main first, then each window's new ids")
    void withWindows() {
        List<PoolBook> merged = SharedBookSelector.withWindows(List.of(book(1, "en_us")),
                List.of(List.of(book(1, "en_us"), book(2, "uk_ua")), List.of(book(3, "es_es"))));
        assertEquals(List.of(1, 2, 3), merged.stream().map(PoolBook::id).toList());
    }
}
