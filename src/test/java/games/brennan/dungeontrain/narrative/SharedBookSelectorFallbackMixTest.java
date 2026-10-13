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
 * The fallback-language fade: books from another language fade in as the unread in-language books by
 * other writers run out (none below {@link SharedBookSelector#FADE_START_BOOKS} left → all fallback),
 * whatever the player has written; own books are capped at 5% while it is active.
 */
final class SharedBookSelectorFallbackMixTest {

    private static final String ME = "Writer";
    private static final int FALLBACK_ID = 1000;

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

    /** {@code others} unread uk books by other writers (ids 100+), plus {@code own} own uk books (ids 1+). */
    private static List<PoolBook> ukPool(int others, int own) {
        List<PoolBook> out = new ArrayList<>();
        for (int i = 0; i < own; i++) out.add(book(1 + i, "uk_ua", ME));
        for (int i = 0; i < others; i++) out.add(book(100 + i, "uk_ua", "Other" + i));
        return out;
    }

    private static final List<PoolBook> EN_WINDOW = List.of(
            book(FALLBACK_ID, "en_us", "Anglo"), book(FALLBACK_ID + 1, "en_us", "Saxon"));

    private static Set<Integer> picks(List<PoolBook> pool, String family, List<PoolBook> fallback, PlayerContext ctx) {
        Set<Integer> out = new HashSet<>();
        for (long seed = 0; seed < 400; seed++) {
            out.add(SharedBookSelector.select(pool, family, fallback, ctx, seed).orElseThrow().id());
        }
        return out;
    }

    /** {fallback picks, own picks} over {@code n} seeds. */
    private static int[] tally(List<PoolBook> pool, String family, List<PoolBook> fallback, PlayerContext ctx, int n) {
        int fb = 0, own = 0;
        for (long seed = 0; seed < n; seed++) {
            PoolBook b = SharedBookSelector.select(pool, family, fallback, ctx, seed).orElseThrow();
            if (b.id() >= FALLBACK_ID) fb++;
            if (ME.equals(b.author())) own++;
        }
        return new int[] {fb, own};
    }

    @Test
    @DisplayName("fallback share: 0 at 10+ left, 50% at 5, 100% at none; 0 without a usable family")
    void fallbackShareCurve() {
        PlayerContext uk = player("uk_ua", id -> false, false);
        assertEquals(0.0, SharedBookSelector.fallbackShare(10, "en", uk));
        assertEquals(0.0, SharedBookSelector.fallbackShare(25, "en", uk));
        assertEquals(0.5, SharedBookSelector.fallbackShare(5, "en", uk));
        assertEquals(1.0, SharedBookSelector.fallbackShare(0, "en", uk));
        assertEquals(0.0, SharedBookSelector.fallbackShare(0, null, uk));
        assertEquals(0.0, SharedBookSelector.fallbackShare(0, "uk", uk), "own language is no fallback");
    }

    @Test
    @DisplayName("10 or more unread books by others left → no fallback")
    void plentyLeftNoFallback() {
        PlayerContext ctx = player("uk_ua", id -> false, false);
        assertTrue(picks(ukPool(10, 0), "en", EN_WINDOW, ctx).stream().noneMatch(id -> id >= FALLBACK_ID));
        assertFalse(SharedBookSelector.needsFallbackFetch(ukPool(10, 0), "en", List.of(), ctx));
    }

    @Test
    @DisplayName("5 left → about half the picks are the fallback language")
    void halfwayIsHalf() {
        PlayerContext ctx = player("uk_ua", id -> false, false);
        int fb = tally(ukPool(5, 0), "en", EN_WINDOW, ctx, 4000)[0];
        assertTrue(fb > 1800 && fb < 2200, "expected ~2000 fallback of 4000, got " + fb);
    }

    @Test
    @DisplayName("none left, non-writer → every pick is the fallback language")
    void noneLeftNonWriter() {
        List<PoolBook> pool = List.of(book(100, "uk_ua", "Other"), book(101, "uk_ua", "Another"));
        PlayerContext ctx = player("uk_ua", id -> id < FALLBACK_ID, false);
        assertEquals(Set.of(FALLBACK_ID, FALLBACK_ID + 1), picks(pool, "en", EN_WINDOW, ctx));
        assertTrue(SharedBookSelector.needsFallbackFetch(pool, "en", List.of(), ctx));
    }

    @Test
    @DisplayName("none left, writer with unread own books → about 5% own, the rest fallback")
    void noneLeftWriterOwnCapped() {
        PlayerContext ctx = player("uk_ua", id -> false, false);
        int[] t = tally(ukPool(0, 3), "en", EN_WINDOW, ctx, 4000);
        assertTrue(t[1] > 120 && t[1] < 280, "expected ~200 own of 4000, got " + t[1]);
        assertEquals(4000, t[0] + t[1], "only own or fallback when nothing by others is left");
    }

    @Test
    @DisplayName("writing does not change the fade: same books left → same fallback share")
    void writingDoesNotMatter() {
        PlayerContext ctx = player("uk_ua", id -> false, false);
        int nonWriter = tally(ukPool(5, 0), "en", EN_WINDOW, ctx, 4000)[0];
        int writer = tally(ukPool(5, 40), "en", EN_WINDOW, ctx, 4000)[0];
        assertTrue(Math.abs(nonWriter - writer) < 300, "non-writer " + nonWriter + " vs writer " + writer);
    }

    @Test
    @DisplayName("no fallback window yet → today's behaviour, and the caller is told to fetch")
    void emptyWindowFallsThrough() {
        PlayerContext ctx = player("uk_ua", id -> false, false);
        assertEquals(Set.of(1, 2, 3), picks(ukPool(0, 3), "en", List.of(), ctx));
        assertTrue(SharedBookSelector.needsFallbackFetch(ukPool(0, 3), "en", List.of(), ctx));
        assertFalse(SharedBookSelector.needsFallbackFetch(ukPool(0, 3), "en", EN_WINDOW, ctx));
    }

    @Test
    @DisplayName("fallback books already read, written by the player, or in another language are skipped")
    void fallbackFiltered() {
        List<PoolBook> fallback = List.of(book(FALLBACK_ID, "en_us", "Anglo"), book(FALLBACK_ID + 1, "en_us", ME),
                book(FALLBACK_ID + 2, "en_us", "X"), book(FALLBACK_ID + 3, "de_de", "Y"));
        PlayerContext ctx = player("uk_ua", id -> id == 100 || id == FALLBACK_ID + 2, false);
        assertEquals(Set.of(FALLBACK_ID), picks(List.of(book(100, "uk_ua", "O")), "en", fallback, ctx));
    }

    @Test
    @DisplayName("Kid mode filters the fallback window too")
    void kidModeFiltersFallback() {
        List<PoolBook> fallback = List.of(book(FALLBACK_ID, "en_us", "Anglo", false), book(FALLBACK_ID + 1, "en_us", "Saxon", true));
        PlayerContext ctx = player("uk_ua", id -> id == 100, true);
        assertEquals(Set.of(FALLBACK_ID + 1), picks(List.of(book(100, "uk_ua", "O")), "en", fallback, ctx));
    }

    @Test
    @DisplayName("English player who has read everything → the biggest other language")
    void englishPlayerGetsOtherFamily() {
        List<PoolBook> pool = List.of(book(1, "en_us", "Anglo"), book(2, "en_us", "Saxon"));
        List<PoolBook> uk = List.of(book(FALLBACK_ID, "uk_ua", "Taras"), book(FALLBACK_ID + 1, "uk_ua", "Lesya"));
        PlayerContext ctx = player("en_us", id -> id < FALLBACK_ID, false);
        assertEquals(Set.of(FALLBACK_ID, FALLBACK_ID + 1), picks(pool, "uk", uk, ctx));
        assertEquals(Set.of(1, 2), picks(pool, null, List.of(), ctx), "family unknown yet → unchanged");
    }

    @Test
    @DisplayName("authorship match is case-insensitive and blank never matches")
    void isOwnBy() {
        assertTrue(SharedBookSelector.isOwnBy("writer").test(book(1, "uk_ua", ME)));
        assertFalse(SharedBookSelector.isOwnBy("").test(book(1, "uk_ua", "")));
        assertFalse(SharedBookSelector.isOwnBy(ME).test(book(1, "uk_ua", null)));
    }
}
