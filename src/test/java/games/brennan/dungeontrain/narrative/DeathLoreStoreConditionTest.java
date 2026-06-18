package games.brennan.dungeontrain.narrative;

import games.brennan.dungeontrain.narrative.DeathLoreStore.Condition;
import games.brennan.dungeontrain.narrative.DeathLoreStore.Context;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the death-count bounds on {@link DeathLoreStore.Condition} that gate the
 * first-death narrative lines (v0.316.1).
 *
 * <p>The bespoke first-death lives/platform entries carry
 * {@code min_deaths:1, max_deaths:1}, and the generic entries were lifted to
 * {@code min_deaths:2}, so on the very first death ONLY the bespoke entries
 * match (guaranteeing the unique first-death dialogue rather than merely
 * weighting it). These tests lock that partition at the matcher level —
 * independent of the data pool, the RNG, or any Minecraft bootstrap.</p>
 */
final class DeathLoreStoreConditionTest {

    /** A death context that varies only in the lifetime death count; every other gate is neutral. */
    private static Context ctxWithDeaths(long deaths) {
        // (cause, carriage, friends, books, mobs, met, slain, hearts, distance, deaths)
        return new Context(null, 1, 0, 0, 0, 0, 0, 0, 0.0, deaths);
    }

    /** A condition that gates ONLY on the death range; every other field is permissive. */
    private static Condition deathRange(long minDeaths, long maxDeaths) {
        // (causes, minCarriage, maxCarriage, minFriends, minBooks, minDeaths, minMobs, maxDeaths)
        return new Condition(List.of(), 0, Integer.MAX_VALUE, 0, 0, minDeaths, 0, maxDeaths);
    }

    @Test
    @DisplayName("First-death entry (min_deaths:1, max_deaths:1) matches ONLY the first death")
    void firstDeathBoundMatchesExactlyDeathOne() {
        Condition firstDeath = deathRange(1L, 1L);
        assertTrue(firstDeath.matches(ctxWithDeaths(1L)), "must match the very first death");
        assertFalse(firstDeath.matches(ctxWithDeaths(2L)), "must not match the second death");
        assertFalse(firstDeath.matches(ctxWithDeaths(50L)), "must not match a much later death");
    }

    @Test
    @DisplayName("Generic entry (min_deaths:2) is suppressed on the first death, active from the second on")
    void genericMinTwoSkipsFirstDeath() {
        Condition generic = deathRange(2L, Long.MAX_VALUE);
        assertFalse(generic.matches(ctxWithDeaths(1L)), "generic line must NOT fire on the first death");
        assertTrue(generic.matches(ctxWithDeaths(2L)), "generic line fires from the second death");
        assertTrue(generic.matches(ctxWithDeaths(999L)), "generic line stays active for high death counts");
    }

    @Test
    @DisplayName("Absent max_deaths (Long.MAX_VALUE) imposes no upper bound; ANY still matches every death")
    void unboundedMaxDeathsMatchesAll() {
        Condition unbounded = deathRange(0L, Long.MAX_VALUE);
        assertTrue(unbounded.matches(ctxWithDeaths(1L)));
        assertTrue(unbounded.matches(ctxWithDeaths(1_000_000L)));
        // The shared ANY constant must remain all-matching after the new field landed.
        assertTrue(Condition.ANY.matches(ctxWithDeaths(1L)));
        assertTrue(Condition.ANY.matches(ctxWithDeaths(42L)));
    }
}
