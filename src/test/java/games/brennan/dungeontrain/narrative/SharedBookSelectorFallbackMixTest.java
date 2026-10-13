package games.brennan.dungeontrain.narrative;

import games.brennan.dungeontrain.narrative.SharedBookPool.PoolBook;
import games.brennan.dungeontrain.narrative.SharedBookSelector.PlayerContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fallback-language mix: once a player's own books are all that is left unread in their language
 * AND at least 5% of it, picks become 5% own / 95% fallback language. Everyone else is untouched.
 */
final class SharedBookSelectorFallbackMixTest {

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

    /** Ukrainian pool: 1-3 mine, 10 someone else's. */
    private static final List<PoolBook> UK_POOL = List.of(
            book(1, "uk_ua", ME), book(2, "uk_ua", ME), book(3, "uk_ua", ME), book(10, "uk_ua", "Other"));
    private static final List<PoolBook> EN_WINDOW = List.of(
            book(100, "en_us", "Anglo"), book(101, "en_us", "Saxon"));

    private static Set<Integer> picks(List<PoolBook> pool, String family, List<PoolBook> fallback, PlayerContext ctx) {
        Set<Integer> out = new HashSet<>();
        for (long seed = 0; seed < 400; seed++) {
            out.add(SharedBookSelector.select(pool, family, fallback, ctx, seed).orElseThrow().id());
        }
        return out;
    }

    private static int ownPicks(List<PoolBook> pool, String family, List<PoolBook> fallback, PlayerContext ctx, int n) {
        int own = 0;
        for (long seed = 0; seed < n; seed++) {
            PoolBook b = SharedBookSelector.select(pool, family, fallback, ctx, seed).orElseThrow();
            if (ME.equals(b.author())) own++;
        }
        return own;
    }

    @Test
    @DisplayName("an unread in-language book by someone else → no mix, today's chain")
    void othersUnreadBlocksMix() {
        PlayerContext ctx = player("uk_ua", id -> false, false);
        assertTrue(picks(UK_POOL, "en", EN_WINDOW, ctx).stream().noneMatch(id -> id >= 100));
        assertFalse(SharedBookSelector.needsFallbackFetch(UK_POOL, "en", List.of(), ctx));
    }

    @Test
    @DisplayName("only own books unread and ≥5% of the pool → about 5% own, 95% English")
    void mixIsFivePercentOwn() {
        PlayerContext ctx = player("uk_ua", id -> id == 10, false);
        int own = ownPicks(UK_POOL, "en", EN_WINDOW, ctx, 4000);
        assertTrue(own > 120 && own < 280, "expected ~200 own of 4000, got " + own);
        assertTrue(picks(UK_POOL, "en", EN_WINDOW, ctx).containsAll(Set.of(100, 101)));
    }

    @Test
    @DisplayName("own books under 5% of the in-language pool → no mix")
    void belowShareNoMix() {
        List<PoolBook> pool = new ArrayList<>();
        pool.add(book(1, "uk_ua", ME));
        for (int i = 0; i < 20; i++) pool.add(book(10 + i, "uk_ua", "Other" + i)); // 1 / 21 ≈ 4.8%
        PlayerContext ctx = player("uk_ua", id -> id >= 10 && id < 100, false);
        assertEquals(Set.of(1), picks(pool, "en", EN_WINDOW, ctx));
        assertFalse(SharedBookSelector.needsFallbackFetch(pool, "en", List.of(), ctx));
    }

    @Test
    @DisplayName("own books exactly 5% of the in-language pool → mix")
    void atShareMixes() {
        List<PoolBook> pool = new ArrayList<>();
        pool.add(book(1, "uk_ua", ME));
        for (int i = 0; i < 19; i++) pool.add(book(10 + i, "uk_ua", "Other" + i)); // 1 / 20 = 5%
        PlayerContext ctx = player("uk_ua", id -> id >= 10 && id < 100, false);
        assertTrue(picks(pool, "en", EN_WINDOW, ctx).contains(100));
    }

    @Test
    @DisplayName("no fallback window yet → today's behaviour, and the caller is told to fetch")
    void emptyWindowFallsThrough() {
        PlayerContext ctx = player("uk_ua", id -> id == 10, false);
        assertEquals(Set.of(1, 2, 3), picks(UK_POOL, "en", List.of(), ctx));
        assertTrue(SharedBookSelector.needsFallbackFetch(UK_POOL, "en", List.of(), ctx));
        assertFalse(SharedBookSelector.needsFallbackFetch(UK_POOL, "en", EN_WINDOW, ctx));
    }

    @Test
    @DisplayName("everything in-language read → fallback served")
    void allReadServesFallback() {
        PlayerContext ctx = player("uk_ua", id -> id < 100, false);
        assertEquals(Set.of(100, 101), picks(UK_POOL, "en", EN_WINDOW, ctx));
    }

    @Test
    @DisplayName("fallback books already read, written by the player, or in another language are skipped")
    void fallbackFiltered() {
        List<PoolBook> fallback = List.of(book(100, "en_us", "Anglo"), book(101, "en_us", ME),
                book(102, "en_us", "X"), book(103, "de_de", "Y"));
        PlayerContext ctx = player("uk_ua", id -> id == 10 || id == 102, false);
        Set<Integer> got = picks(UK_POOL, "en", fallback, ctx);
        assertTrue(got.contains(100));
        assertFalse(got.contains(101));
        assertFalse(got.contains(102));
        assertFalse(got.contains(103));
    }

    @Test
    @DisplayName("Kid mode filters the fallback window too")
    void kidModeFiltersFallback() {
        List<PoolBook> fallback = List.of(book(100, "en_us", "Anglo", false), book(101, "en_us", "Saxon", true));
        PlayerContext ctx = player("uk_ua", id -> id == 10, true);
        Set<Integer> got = picks(UK_POOL, "en", fallback, ctx);
        assertTrue(got.contains(101));
        assertFalse(got.contains(100));
    }

    @Test
    @DisplayName("English player mostly own → the biggest other language is mixed in")
    void englishPlayerGetsOtherFamily() {
        List<PoolBook> pool = List.of(book(1, "en_us", ME), book(2, "en_us", ME), book(3, "en_us", "Other"));
        List<PoolBook> de = List.of(book(200, "de_de", "Hans"));
        PlayerContext ctx = player("en_us", id -> id == 3, false);
        int own = ownPicks(pool, "de", de, ctx, 2000);
        assertTrue(own < 200, "mostly German, got " + own + " own of 2000");
        assertEquals(Set.of(1, 2), picks(pool, null, List.of(), ctx), "family unknown yet → unchanged");
    }

    @Test
    @DisplayName("fallback family equal to the player's own language → no mix")
    void sameFamilyNoMix() {
        PlayerContext ctx = player("en_us", id -> id == 3, false);
        List<PoolBook> pool = List.of(book(1, "en_us", ME), book(3, "en_us", "Other"));
        assertEquals(Set.of(1), picks(pool, "en", List.of(book(100, "en_us", "Anglo")), ctx));
    }

    @Test
    @DisplayName("no own-book knowledge → behaves as before")
    void noOwnKnowledgeUnchanged() {
        PlayerContext ctx = new PlayerContext("uk_ua", id -> id == 10, id -> false, id -> 0, 0, 10, false, false);
        assertTrue(picks(UK_POOL, "en", EN_WINDOW, ctx).stream().noneMatch(id -> id >= 100));
    }

    @Test
    @DisplayName("authorship match is case-insensitive and blank never matches")
    void isOwnBy() {
        assertTrue(SharedBookSelector.isOwnBy("writer").test(book(1, "uk_ua", ME)));
        assertFalse(SharedBookSelector.isOwnBy("").test(book(1, "uk_ua", "")));
        assertFalse(SharedBookSelector.isOwnBy(ME).test(book(1, "uk_ua", null)));
    }
}
