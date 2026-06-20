package games.brennan.dungeontrain.narrative;

import games.brennan.dungeontrain.narrative.DeathLoreStore.Condition;
import games.brennan.dungeontrain.narrative.DeathLoreStore.Context;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the {@link DeathLoreStore.Condition} matcher: the death-count bounds that gate the first-death
 * narrative lines (v0.316.1), and the loot / friends / slain / books gates added for the redesigned
 * death report. All independent of the data pool, the RNG, and any Minecraft bootstrap.
 */
final class DeathLoreStoreConditionTest {

    /** Context fields: (cause, carriage, friends, books, mobs, met, slain, hearts, distance, deaths, loot). */
    private static Context ctx(int friends, int books, int slain, int loot, long deaths) {
        return new Context(null, 1, friends, books, 0, 0, slain, 0, 0.0, deaths, loot);
    }

    private static Context ctxWithDeaths(long deaths) {
        return ctx(0, 0, 0, 0, deaths);
    }

    /** Death-range-only condition; every other gate permissive. */
    private static Condition deathRange(long minDeaths, long maxDeaths) {
        return new Condition(List.of(), 0, Integer.MAX_VALUE, 0, 0, minDeaths, 0, maxDeaths,
                Integer.MAX_VALUE, 0, Integer.MAX_VALUE, 0, Integer.MAX_VALUE);
    }

    /** Condition over the new gates (friends/books/slain/loot); death + carriage + mobs permissive. */
    private static Condition gate(int minFriends, int maxFriends, int minBooks, int maxBooks,
                                  int minSlain, int minLoot, int maxLoot) {
        return new Condition(List.of(), 0, Integer.MAX_VALUE, minFriends, minBooks, 0L, 0, Long.MAX_VALUE,
                maxFriends, minSlain, maxBooks, minLoot, maxLoot);
    }

    private static final int MAX = Integer.MAX_VALUE;

    @Test
    @DisplayName("First-death entry (min_deaths:1, max_deaths:1) matches ONLY the first death")
    void firstDeathBoundMatchesExactlyDeathOne() {
        Condition firstDeath = deathRange(1L, 1L);
        assertTrue(firstDeath.matches(ctxWithDeaths(1L)));
        assertFalse(firstDeath.matches(ctxWithDeaths(2L)));
        assertFalse(firstDeath.matches(ctxWithDeaths(50L)));
    }

    @Test
    @DisplayName("Generic entry (min_deaths:2) is suppressed on the first death, active from the second on")
    void genericMinTwoSkipsFirstDeath() {
        Condition generic = deathRange(2L, Long.MAX_VALUE);
        assertFalse(generic.matches(ctxWithDeaths(1L)));
        assertTrue(generic.matches(ctxWithDeaths(2L)));
        assertTrue(generic.matches(ctxWithDeaths(999L)));
    }

    @Test
    @DisplayName("ANY matches every death regardless of the new gates")
    void anyMatchesAll() {
        assertTrue(Condition.ANY.matches(ctxWithDeaths(1L)));
        assertTrue(Condition.ANY.matches(ctx(99, 99, 99, 9999, 42L)));
    }

    @Test
    @DisplayName("max_friends:0 gates the 'made no friends' line")
    void maxFriendsGate() {
        Condition noFriends = gate(0, 0, 0, MAX, 0, 0, MAX);
        assertTrue(noFriends.matches(ctx(0, 0, 0, 0, 1L)));
        assertFalse(noFriends.matches(ctx(1, 0, 0, 0, 1L)));
    }

    @Test
    @DisplayName("min_slain:5 gates the 'five or more slain' line")
    void minSlainGate() {
        Condition slayer = gate(0, MAX, 0, MAX, 5, 0, MAX);
        assertTrue(slayer.matches(ctx(0, 0, 5, 0, 1L)));
        assertFalse(slayer.matches(ctx(0, 0, 4, 0, 1L)));
    }

    @Test
    @DisplayName("max_books:0 gates 'no books'; min_books:10 gates 'well read'")
    void bookGates() {
        Condition noBooks = gate(0, MAX, 0, 0, 0, 0, MAX);
        assertTrue(noBooks.matches(ctx(0, 0, 0, 0, 1L)));
        assertFalse(noBooks.matches(ctx(0, 1, 0, 0, 1L)));
        Condition wellRead = gate(0, MAX, 10, MAX, 0, 0, MAX);
        assertTrue(wellRead.matches(ctx(0, 10, 0, 0, 1L)));
        assertFalse(wellRead.matches(ctx(0, 9, 0, 0, 1L)));
    }

    @Test
    @DisplayName("loot gates: none (max_loot:0), light (max_loot:9), hoard (min_loot:250)")
    void lootGates() {
        Condition noLoot = gate(0, MAX, 0, MAX, 0, 0, 0);
        assertTrue(noLoot.matches(ctx(0, 0, 0, 0, 1L)));
        assertFalse(noLoot.matches(ctx(0, 0, 0, 1, 1L)));

        Condition light = gate(0, MAX, 0, MAX, 0, 0, 9);
        assertTrue(light.matches(ctx(0, 0, 0, 9, 1L)));
        assertFalse(light.matches(ctx(0, 0, 0, 10, 1L)));

        Condition hoard = gate(0, MAX, 0, MAX, 0, 250, MAX);
        assertTrue(hoard.matches(ctx(0, 0, 0, 250, 1L)));
        assertFalse(hoard.matches(ctx(0, 0, 0, 249, 1L)));
    }
}
