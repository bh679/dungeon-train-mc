package games.brennan.dungeontrain.perf;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for perf-test mode. No Minecraft bootstrap — {@code parseSeed} and the
 * quiet-rule table are deliberately expressible over primitives and strings so they can be
 * exercised here, matching the pure-logic-only constraint on this source set.
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

    // ------------------------------------------------------------------ quiet game rules

    @Test
    void quietRulesCoverEveryKnownPerTickNoiseSource() {
        Set<String> names = new HashSet<>();
        for (String[] rule : PerfTestMode.QUIET_GAME_RULES) {
            names.add(rule[0]);
        }
        // Each of these varies tick cost independently of the train, so a missing one reintroduces
        // exactly the run-to-run variance this mode exists to remove.
        assertTrue(names.contains("doDaylightCycle"), "daylight drives lighting updates");
        assertTrue(names.contains("doWeatherCycle"), "weather drives precipitation ticks");
        assertTrue(names.contains("doMobSpawning"), "spawning adds unbounded entities to measure");
        assertTrue(names.contains("doFireTick"));
        assertTrue(names.contains("randomTickSpeed"), "random ticks scale with loaded chunks");
    }

    @Test
    void everyQuietRuleIsAWellFormedNameValuePair() {
        for (String[] rule : PerfTestMode.QUIET_GAME_RULES) {
            assertEquals(2, rule.length, "each entry is (name, value)");
            assertTrue(rule[0] != null && !rule[0].isBlank());
            assertTrue(rule[1] != null && !rule[1].isBlank());
        }
    }

    @Test
    void quietRuleNamesAreUnique() {
        long distinct = Arrays.stream(PerfTestMode.QUIET_GAME_RULES).map(r -> r[0]).distinct().count();
        assertEquals(PerfTestMode.QUIET_GAME_RULES.length, distinct,
            "a duplicated rule name means one of them is dead weight");
    }
}
