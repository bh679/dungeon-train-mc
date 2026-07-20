package games.brennan.dungeontrain.narrative;

import games.brennan.dungeontrain.narrative.SharedBookPool.PoolBook;
import games.brennan.dungeontrain.narrative.SharedBookSelector.Origin;
import games.brennan.dungeontrain.narrative.SharedBookSelector.PlayerContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link SharedBookSelector}'s priority chain (design model A):
 * eligible → language bucket → unread-first → effectiveWeight tier → seeded random. The selector is pure,
 * so each case supplies the player state directly and asserts which book survives.
 *
 * <p>Origin is DERIVED per-player from the book's {@code lang} vs the player's locale (the relay sends
 * {@code lang}; it does not send a per-player origin), so the language cases here vary {@code lang}.</p>
 */
final class SharedBookSelectorTest {

    private static final String EN = "en_us";

    /** Convenience factory — pages/title are irrelevant to selection. */
    private static PoolBook book(int id, String lang, int weight) {
        return new PoolBook(id, "title" + id, "author", List.of("p"), lang, weight);
    }

    /** An en_us player who has read nothing and been served nothing; carriage 0, repeat threshold 10. */
    private static PlayerContext freshPlayer() {
        return new PlayerContext(EN, id -> false, id -> false, id -> 0, 0, 10);
    }

    @Test
    @DisplayName("empty / null pool → empty")
    void emptyPool() {
        assertTrue(SharedBookSelector.select(List.of(), freshPlayer(), 1L).isEmpty());
        assertTrue(SharedBookSelector.select(null, freshPlayer(), 1L).isEmpty());
    }

    @Test
    @DisplayName("origin is derived per-player: same family → MINE, different → OTHER")
    void originDerivation() {
        PlayerContext en = freshPlayer();
        assertEquals(Origin.MINE, SharedBookSelector.originOf(book(1, "en_us", 1), en));
        assertEquals(Origin.MINE, SharedBookSelector.originOf(book(1, "en_gb", 1), en), "en_gb same family as en_us");
        assertEquals(Origin.MINE, SharedBookSelector.originOf(book(1, null, 1), en), "untagged → English default");
        assertEquals(Origin.OTHER, SharedBookSelector.originOf(book(1, "es_es", 1), en));
        // Same pool book, different player → different origin (proves it is not a stored field).
        PlayerContext es = new PlayerContext("es_es", id -> false, id -> false, id -> 0, 0, 10);
        assertEquals(Origin.MINE, SharedBookSelector.originOf(book(1, "es_es", 1), es));
        assertEquals(Origin.OTHER, SharedBookSelector.originOf(book(1, "en_us", 1), es));
    }

    @Test
    @DisplayName("language bucket dominates weight: a heavy foreign book loses to a light in-language one")
    void languageDominatesWeight() {
        List<PoolBook> pool = List.of(
            book(1, EN, 1),
            book(2, "es_es", 99));
        for (long seed = 0; seed < 200; seed++) {
            Optional<PoolBook> pick = SharedBookSelector.select(pool, freshPlayer(), seed);
            assertEquals(1, pick.orElseThrow().id(),
                "in-language book must win regardless of the foreign book's weight (seed " + seed + ")");
        }
    }

    @Test
    @DisplayName("OTHER is a real fallback: with no in-language book, a foreign one is served")
    void foreignFallback() {
        List<PoolBook> pool = List.of(book(7, "fr_fr", 1), book(8, "ja_jp", 1));
        Optional<PoolBook> pick = SharedBookSelector.select(pool, freshPlayer(), 3L);
        assertTrue(pick.isPresent(), "no in-language book → fall back to OTHER rather than serving nothing");
    }

    @Test
    @DisplayName("unread-first: within the language bucket an unread book beats a read one of higher weight")
    void unreadFirst() {
        List<PoolBook> pool = List.of(
            book(1, EN, 9),   // read
            book(2, EN, 1));  // unread
        Set<Integer> read = new HashSet<>(Set.of(1));
        PlayerContext ctx = new PlayerContext(EN, read::contains, id -> false, id -> 0, 0, 10);
        for (long seed = 0; seed < 200; seed++) {
            assertEquals(2, SharedBookSelector.select(pool, ctx, seed).orElseThrow().id(),
                "unread book wins even though the read one is heavier");
        }
    }

    @Test
    @DisplayName("unread-first is soft: an all-read tier still returns the heaviest read book")
    void allReadFallsBack() {
        List<PoolBook> pool = List.of(book(1, EN, 9), book(2, EN, 1));
        PlayerContext ctx = new PlayerContext(EN, id -> true, id -> false, id -> 0, 0, 10);
        for (long seed = 0; seed < 50; seed++) {
            assertEquals(1, SharedBookSelector.select(pool, ctx, seed).orElseThrow().id(),
                "all read → fall back to the tier, top weight wins");
        }
    }

    @Test
    @DisplayName("weight tier: within one language bucket the heavier book wins")
    void weightTier() {
        List<PoolBook> pool = List.of(book(1, EN, 5), book(2, EN, 3));
        for (long seed = 0; seed < 100; seed++) {
            assertEquals(1, SharedBookSelector.select(pool, freshPlayer(), seed).orElseThrow().id());
        }
    }

    @Test
    @DisplayName("dedup: a book served this life is not eligible while near its serve carriage")
    void dedupWithinLife() {
        List<PoolBook> pool = List.of(book(1, EN, 9), book(2, EN, 1));
        Set<Integer> served = new HashSet<>(Set.of(1));
        Map<Integer, Integer> servedAt = new HashMap<>(Map.of(1, 0));
        // current carriage 5, threshold 10 → book 1 is only 5 behind → still ineligible.
        PlayerContext ctx = new PlayerContext(EN, id -> false, served::contains,
            id -> servedAt.getOrDefault(id, 0), 5, 10);
        for (long seed = 0; seed < 200; seed++) {
            assertEquals(2, SharedBookSelector.select(pool, ctx, seed).orElseThrow().id(),
                "served-this-life book stays out until its carriage scrolls far behind");
        }
    }

    @Test
    @DisplayName("far-behind escape: an unread served book is eligible once its carriage is >= threshold behind")
    void farBehindEscape() {
        List<PoolBook> pool = List.of(book(1, EN, 5));
        Set<Integer> served = new HashSet<>(Set.of(1));
        // current carriage 10, threshold 10 → 10 >= 10 → eligible again.
        PlayerContext ctx = new PlayerContext(EN, id -> false, served::contains, id -> 0, 10, 10);
        Optional<PoolBook> pick = SharedBookSelector.select(pool, ctx, 1L);
        assertTrue(pick.isPresent(), "unread + carriage 10 behind (>=10) → re-eligible");
        assertEquals(1, pick.get().id());
    }

    @Test
    @DisplayName("exhaustion relaxes dedup: a served+read book repeats rather than yielding no pick")
    void exhaustionRelaxesDedup() {
        // Served AND read, so neither plain eligibility nor the far-behind escape admits it. Returning
        // empty here would make the caller keep its built-in placeholder — mod content out of a
        // player-book slot — so the dedup is relaxed instead.
        List<PoolBook> pool = List.of(book(1, EN, 5));
        Set<Integer> served = new HashSet<>(Set.of(1));
        PlayerContext ctx = new PlayerContext(EN, id -> true, served::contains, id -> 0, 0, 10);
        Optional<PoolBook> pick = SharedBookSelector.select(pool, ctx, 1L);
        assertTrue(pick.isPresent(), "a repeated community book beats falling back to a built-in");
        assertEquals(1, pick.get().id());
    }

    @Test
    @DisplayName("exhaustion still respects the rest of the chain: relaxed pick prefers in-language")
    void exhaustionKeepsLanguagePriority() {
        // Everything served+read → relaxed, but the language bucket must still win.
        List<PoolBook> pool = List.of(book(1, "ja_jp", 9), book(2, EN, 1));
        PlayerContext ctx = new PlayerContext(EN, id -> true, id -> true, id -> 0, 0, 10);
        for (long seed = 0; seed < 100; seed++) {
            assertEquals(2, SharedBookSelector.select(pool, ctx, seed).orElseThrow().id(),
                "relaxation reconsiders the pool, it does not bypass the language bucket");
        }
    }

    @Test
    @DisplayName("empty pool is the ONLY no-pick case — that is what reserves the built-in fallback")
    void onlyEmptyPoolYieldsNoPick() {
        PlayerContext everythingSeen = new PlayerContext(EN, id -> true, id -> true, id -> 0, 0, 10);
        assertTrue(SharedBookSelector.select(List.of(), everythingSeen, 1L).isEmpty());
        // ...but any non-empty pool always yields something, however thoroughly seen.
        assertTrue(SharedBookSelector.select(List.of(book(1, "fr_fr", 0)), everythingSeen, 1L).isPresent());
    }

    @Test
    @DisplayName("determinism: same (pool, ctx, seed) always yields the same pick")
    void deterministic() {
        List<PoolBook> pool = List.of(book(1, EN, 5), book(2, EN, 5), book(3, EN, 5));
        int first = SharedBookSelector.select(pool, freshPlayer(), 42L).orElseThrow().id();
        for (int i = 0; i < 20; i++) {
            assertEquals(first, SharedBookSelector.select(pool, freshPlayer(), 42L).orElseThrow().id());
        }
    }
}
