package games.brennan.dungeontrain.narrative;

import games.brennan.dungeontrain.narrative.SharedBookPool.PoolBook;
import games.brennan.dungeontrain.narrative.SharedBookSelector.PlayerContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The English mix-in: a non-English player whose only unread in-language books are their own gets
 * English books mixed in, half the time; everyone else is untouched.
 */
final class SharedBookSelectorEnglishMixTest {

    private static final String ME = "Writer";

    private static PoolBook book(int id, String lang, String author) {
        return book(id, lang, author, true);
    }

    private static PoolBook book(int id, String lang, String author, boolean kidSafe) {
        return new PoolBook(id, "t" + id, author, List.of("p"), lang, 0, kidSafe, false,
                SharedBookPool.STATUS_UNKNOWN, false, 0, 0);
    }

    private static PlayerContext player(String lang, IntPredicate read, boolean kid) {
        return new PlayerContext(lang, read, id -> false, id -> 0, 0, 10, kid, false,
                SharedBookSelector.isOwnBy(ME));
    }

    /** Ukrainian pool: 1-3 mine (unread), 10 someone else's. */
    private static final List<PoolBook> UK_POOL = List.of(
            book(1, "uk_ua", ME), book(2, "uk_ua", ME), book(3, "uk_ua", ME), book(10, "uk_ua", "Other"));
    private static final List<PoolBook> EN_WINDOW = List.of(
            book(100, "en_us", "Anglo"), book(101, "en_us", "Saxon"));

    private static Set<Integer> picks(List<PoolBook> pool, List<PoolBook> english, PlayerContext ctx) {
        Set<Integer> out = new HashSet<>();
        for (long seed = 0; seed < 200; seed++) {
            out.add(SharedBookSelector.select(pool, english, ctx, seed).orElseThrow().id());
        }
        return out;
    }

    @Test
    @DisplayName("an unread in-language book by someone else → no English, today's chain")
    void othersUnreadBlocksMix() {
        PlayerContext ctx = player("uk_ua", id -> false, false);
        Set<Integer> got = picks(UK_POOL, EN_WINDOW, ctx);
        assertTrue(got.stream().noneMatch(id -> id >= 100), "no English while book 10 is unread: " + got);
        assertFalse(SharedBookSelector.needsEnglishFallback(UK_POOL, List.of(), ctx));
    }

    @Test
    @DisplayName("only own books unread in-language → mix of own and English")
    void ownOnlyMixesEnglish() {
        PlayerContext ctx = player("uk_ua", id -> id == 10, false);
        Set<Integer> got = picks(UK_POOL, EN_WINDOW, ctx);
        assertTrue(got.containsAll(Set.of(1, 2, 3, 100, 101)), "own + English both served: " + got);
        assertFalse(got.contains(10), "the read in-language book is not re-served while unread remain");
    }

    @Test
    @DisplayName("mix is roughly 50/50 across seeds")
    void mixIsHalfAndHalf() {
        PlayerContext ctx = player("uk_ua", id -> id == 10, false);
        int english = 0;
        for (long seed = 0; seed < 2000; seed++) {
            if (SharedBookSelector.select(UK_POOL, EN_WINDOW, ctx, seed).orElseThrow().id() >= 100) english++;
        }
        assertTrue(english > 800 && english < 1200, "expected ~1000 English of 2000, got " + english);
    }

    @Test
    @DisplayName("no English window yet → today's behaviour, and the caller is told to fetch")
    void emptyWindowFallsThrough() {
        PlayerContext ctx = player("uk_ua", id -> id == 10, false);
        Set<Integer> got = picks(UK_POOL, List.of(), ctx);
        assertEquals(Set.of(1, 2, 3), got);
        assertTrue(SharedBookSelector.needsEnglishFallback(UK_POOL, List.of(), ctx));
        assertFalse(SharedBookSelector.needsEnglishFallback(UK_POOL, EN_WINDOW, ctx),
                "a window with servable English needs no fetch");
    }

    @Test
    @DisplayName("everything in-language read → unread English served")
    void allReadServesEnglish() {
        PlayerContext ctx = player("uk_ua", id -> id < 100, false);
        assertEquals(Set.of(100, 101), picks(UK_POOL, EN_WINDOW, ctx));
    }

    @Test
    @DisplayName("English books already read or written by the player are not mixed in")
    void englishFilteredByReadAndOwn() {
        List<PoolBook> english = List.of(book(100, "en_us", "Anglo"), book(101, "en_us", ME), book(102, "en_us", "X"));
        PlayerContext ctx = player("uk_ua", id -> id == 10 || id == 102, false);
        Set<Integer> got = picks(UK_POOL, english, ctx);
        assertTrue(got.contains(100));
        assertFalse(got.contains(101), "own English book is not 'English mixed in'");
        assertFalse(got.contains(102), "read English book is skipped");
    }

    @Test
    @DisplayName("Kid mode filters the English window too")
    void kidModeFiltersEnglish() {
        List<PoolBook> english = List.of(book(100, "en_us", "Anglo", false), book(101, "en_us", "Saxon", true));
        PlayerContext ctx = player("uk_ua", id -> id == 10, true);
        Set<Integer> got = picks(UK_POOL, english, ctx);
        assertTrue(got.contains(101));
        assertFalse(got.contains(100));
    }

    @Test
    @DisplayName("an English player is never mixed")
    void englishPlayerUnaffected() {
        List<PoolBook> pool = List.of(book(1, "en_us", ME), book(2, "en_us", ME));
        PlayerContext ctx = player("en_us", id -> false, false);
        assertEquals(Set.of(1, 2), picks(pool, List.of(book(100, "en_us", "Anglo")), ctx));
        assertFalse(SharedBookSelector.needsEnglishFallback(pool, List.of(), ctx));
    }

    @Test
    @DisplayName("no own-book knowledge → behaves as before")
    void noOwnKnowledgeUnchanged() {
        PlayerContext ctx = new PlayerContext("uk_ua", id -> id == 10, id -> false, id -> 0, 0, 10, false, false);
        Set<Integer> got = picks(UK_POOL, EN_WINDOW, ctx);
        assertTrue(got.stream().noneMatch(id -> id >= 100), "unknown authorship never mixes: " + got);
    }

    @Test
    @DisplayName("authorship match is case-insensitive and blank never matches")
    void isOwnBy() {
        assertTrue(SharedBookSelector.isOwnBy("writer").test(book(1, "uk_ua", ME)));
        assertFalse(SharedBookSelector.isOwnBy("").test(book(1, "uk_ua", "")));
        assertFalse(SharedBookSelector.isOwnBy(ME).test(book(1, "uk_ua", null)));
    }
}
