package games.brennan.dungeontrain.perf;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure-logic tests for perf-test mode. No Minecraft bootstrap — {@code parseSeed} is deliberately
 * expressible over primitives so it can be exercised here, matching the pure-logic-only constraint
 * on this source set.
 */
class PerfTestModeTest {

    // ------------------------------------------------------------------ seed resolution

    @Test
    void unsetPropertyUsesTheDefaultSeed() {
        assertEquals(PerfTestMode.DEFAULT_SEED,
            PerfTestMode.parseSeed(null, PerfTestMode.DEFAULT_SEED));
        assertEquals(PerfTestMode.DEFAULT_SEED,
            PerfTestMode.parseSeed("", PerfTestMode.DEFAULT_SEED));
        assertEquals(PerfTestMode.DEFAULT_SEED,
            PerfTestMode.parseSeed("   ", PerfTestMode.DEFAULT_SEED));
    }

    @Test
    void explicitSeedOverridesTheDefault() {
        assertEquals(123L, PerfTestMode.parseSeed("123", PerfTestMode.DEFAULT_SEED));
        assertEquals(-42L, PerfTestMode.parseSeed("-42", PerfTestMode.DEFAULT_SEED));
        assertEquals(123L, PerfTestMode.parseSeed("  123  ", PerfTestMode.DEFAULT_SEED));
    }

    @Test
    void unparseableSeedFallsBackRatherThanThrowing() {
        // A typo in a benchmark command should not stop the game launching.
        assertEquals(PerfTestMode.DEFAULT_SEED,
            PerfTestMode.parseSeed("not-a-number", PerfTestMode.DEFAULT_SEED));
        assertEquals(PerfTestMode.DEFAULT_SEED,
            PerfTestMode.parseSeed("12.5", PerfTestMode.DEFAULT_SEED));
    }

    @Test
    void defaultSeedIsFixed() {
        // Pinned deliberately: changing it silently invalidates comparisons against runs recorded
        // earlier. If this test fails, that is the reminder — not a nuisance.
        assertEquals(8675309031337L, PerfTestMode.DEFAULT_SEED);
    }

    // Quiet game rules are no longer a String table — applyQuietRules uses typed
    // GameRules.Key constants, which cannot be exercised without a Minecraft bootstrap that this
    // source set deliberately does not have. That path is verified in-game instead (all five rules
    // confirmed applied on a dedicated server).
}
