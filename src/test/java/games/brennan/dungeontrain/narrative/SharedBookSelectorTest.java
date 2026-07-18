package games.brennan.dungeontrain.narrative;

import games.brennan.dungeontrain.narrative.SharedBookPool.Origin;
import games.brennan.dungeontrain.narrative.SharedBookPool.PoolBook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link SharedBookSelector}'s priority chain (design model A):
 * eligible → language bucket → unread-first → effectiveWeight tier → seeded random. The selector is pure,
 * so each case supplies the player state as predicates and asserts which book(s) can survive.
 */
final class SharedBookSelectorTest {

    /** Convenience factory — pages are irrelevant to selection. */
    private static PoolBook book(int id, int weight, Origin origin) {
        return new PoolBook(id, "title" + id, "author", List.of("p"), weight, origin);
    }

    /** A player who has read nothing and been served nothing; current carriage 0, repeat threshold 10. */
    private static SharedBookSelector.PlayerContext freshPlayer() {
        return new SharedBookSelector.PlayerContext(id -> false, id -> false, id -> 0, 0, 10);
    }

    @Test
    @DisplayName("empty / null pool → empty")
    void emptyPool() {
        assertTrue(SharedBookSelector.select(List.of(), freshPlayer(), 1L).isEmpty());
        assertTrue(SharedBookSelector.select(null, freshPlayer(), 1L).isEmpty());
    }

    @Test
    @DisplayName("language bucket dominates weight: a heavy OTHER loses to a light MINE")
    void languageDominatesWeight() {
        List<PoolBook> pool = List.of(
            book(1, 1, Origin.MINE),
            book(2, 99, Origin.OTHER));
        // Try many seeds — OTHER must NEVER be chosen while an in-language book exists.
        for (long seed = 0; seed < 200; seed++) {
            Optional<PoolBook> pick = SharedBookSelector.select(pool, freshPlayer(), seed);
            assertTrue(pick.isPresent());
            assertEquals(1, pick.get().id(), "in-language book must win regardless of OTHER's weight (seed " + seed + ")");
        }
    }

    @Test
    @DisplayName("TRANSLATED -1: a native book edges out a translated book of equal raw weight")
    void translatedMinusOne() {
        List<PoolBook> pool = List.of(
            book(1, 5, Origin.MINE),
            book(2, 5, Origin.TRANSLATED)); // effective 4
        for (long seed = 0; seed < 200; seed++) {
            Optional<PoolBook> pick = SharedBookSelector.select(pool, freshPlayer(), seed);
            assertEquals(1, pick.orElseThrow().id(), "native (eff 5) must beat translated (eff 4)");
        }
    }

    @Test
    @DisplayName("TRANSLATED -1 is only -1: a raw-weight-5 translated book still beats a raw-weight-3 native")
    void translatedStillWinsWhenHeavier() {
        List<PoolBook> pool = List.of(
            book(1, 3, Origin.MINE),        // effective 3
            book(2, 5, Origin.TRANSLATED)); // effective 4
        for (long seed = 0; seed < 200; seed++) {
            assertEquals(2, SharedBookSelector.select(pool, freshPlayer(), seed).orElseThrow().id(),
                "translated eff 4 beats native eff 3 (model A: -1 is a soft nudge)");
        }
    }

    @Test
    @DisplayName("unread-first: within the top language bucket an unread book beats a read one of higher weight")
    void unreadFirst() {
        List<PoolBook> pool = List.of(
            book(1, 9, Origin.MINE),   // read
            book(2, 1, Origin.MINE));  // unread
        Set<Integer> read = new HashSet<>(Set.of(1));
        SharedBookSelector.PlayerContext ctx = new SharedBookSelector.PlayerContext(
            read::contains, id -> false, id -> 0, 0, 10);
        for (long seed = 0; seed < 200; seed++) {
            assertEquals(2, SharedBookSelector.select(pool, ctx, seed).orElseThrow().id(),
                "unread book wins even though the read one is heavier");
        }
    }

    @Test
    @DisplayName("unread-first is soft: all-read tier still returns the heaviest read book")
    void allReadFallsBack() {
        List<PoolBook> pool = List.of(
            book(1, 9, Origin.MINE),
            book(2, 1, Origin.MINE));
        SharedBookSelector.PlayerContext ctx = new SharedBookSelector.PlayerContext(
            id -> true /* all read */, id -> false, id -> 0, 0, 10);
        for (long seed = 0; seed < 50; seed++) {
            assertEquals(1, SharedBookSelector.select(pool, ctx, seed).orElseThrow().id(),
                "all read → fall back to the tier, top weight wins");
        }
    }

    @Test
    @DisplayName("dedup: a book served this life is not eligible (near the serve carriage)")
    void dedupWithinLife() {
        List<PoolBook> pool = List.of(
            book(1, 9, Origin.MINE),   // served this life, at carriage 0
            book(2, 1, Origin.MINE));  // not served
        Set<Integer> served = new HashSet<>(Set.of(1));
        Map<Integer, Integer> servedAt = new HashMap<>(Map.of(1, 0));
        IntPredicate wasServed = served::contains;
        IntUnaryOperator servedCarriage = id -> servedAt.getOrDefault(id, 0);
        // current carriage 5, threshold 10 → book 1's carriage is only 5 behind → still ineligible.
        SharedBookSelector.PlayerContext ctx = new SharedBookSelector.PlayerContext(
            id -> false, wasServed, servedCarriage, 5, 10);
        for (long seed = 0; seed < 200; seed++) {
            assertEquals(2, SharedBookSelector.select(pool, ctx, seed).orElseThrow().id(),
                "served-this-life book stays out until its carriage scrolls far behind");
        }
    }

    @Test
    @DisplayName("far-behind escape: an unread served book becomes eligible once its carriage is >= threshold behind")
    void farBehindEscape() {
        List<PoolBook> pool = List.of(book(1, 5, Origin.MINE)); // the only book, served at carriage 0, unread
        Set<Integer> served = new HashSet<>(Set.of(1));
        // current carriage 10, threshold 10 → 10 >= 10 → eligible again.
        SharedBookSelector.PlayerContext ctx = new SharedBookSelector.PlayerContext(
            id -> false /* unread */, served::contains, id -> 0, 10, 10);
        Optional<PoolBook> pick = SharedBookSelector.select(pool, ctx, 1L);
        assertTrue(pick.isPresent(), "unread + carriage 10 behind (>=10) → re-eligible");
        assertEquals(1, pick.get().id());
    }

    @Test
    @DisplayName("far-behind escape does NOT apply to a read book: served + read stays out even far behind")
    void readBookNeverRepeats() {
        List<PoolBook> pool = List.of(book(1, 5, Origin.MINE)); // served + READ
        Set<Integer> served = new HashSet<>(Set.of(1));
        SharedBookSelector.PlayerContext ctx = new SharedBookSelector.PlayerContext(
            id -> true /* read */, served::contains, id -> 0, 999 /* very far */, 10);
        assertTrue(SharedBookSelector.select(pool, ctx, 1L).isEmpty(),
            "a served+read book never repeats within a life, however far behind");
    }

    @Test
    @DisplayName("effectiveWeight helper: translated docked exactly 1")
    void effectiveWeightHelper() {
        assertEquals(5, SharedBookSelector.effectiveWeight(book(1, 5, Origin.MINE)));
        assertEquals(5, SharedBookSelector.effectiveWeight(book(1, 5, Origin.OTHER)));
        assertEquals(4, SharedBookSelector.effectiveWeight(book(1, 5, Origin.TRANSLATED)));
    }

    @Test
    @DisplayName("determinism: same (pool, ctx, seed) always yields the same pick")
    void deterministic() {
        List<PoolBook> pool = List.of(
            book(1, 5, Origin.MINE),
            book(2, 5, Origin.MINE),
            book(3, 5, Origin.MINE));
        Optional<PoolBook> first = SharedBookSelector.select(pool, freshPlayer(), 42L);
        assertNotNull(first);
        for (int i = 0; i < 20; i++) {
            assertEquals(first.orElseThrow().id(),
                SharedBookSelector.select(pool, freshPlayer(), 42L).orElseThrow().id());
        }
    }
}
