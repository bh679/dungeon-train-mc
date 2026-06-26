package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cache-behaviour tests for {@link WorldGenCycle#fromConfig()} memoisation (the per-config-load
 * cache that replaces millions of per-column rebuilds during worldgen).
 *
 * <p>{@code fromConfig} reads the GLOBAL COMMON config; in a plain JUnit run the spec is unloaded,
 * so the getters fall back to the {@code DEFAULT_*} constants and the build is deterministic. These
 * tests assert the cache <em>contract</em> (identity + invalidation rebuild), not specific layout
 * values — those are covered by {@link WorldGenCycleTest} via the record constructor.</p>
 */
final class WorldGenCycleCacheTest {

    /** The cache is static — start every test from a clean slate. */
    @BeforeEach
    void clearCache() {
        WorldGenCycle.invalidateCache();
    }

    @Test
    @DisplayName("fromConfig memoises — repeated calls return the SAME instance")
    void memoised() {
        WorldGenCycle a = WorldGenCycle.fromConfig();
        WorldGenCycle b = WorldGenCycle.fromConfig();
        assertSame(a, b, "second fromConfig() should return the cached instance, not a rebuild");
    }

    @Test
    @DisplayName("invalidateCache forces a fresh, behaviourally-identical rebuild")
    void invalidateRebuilds() {
        WorldGenCycle a = WorldGenCycle.fromConfig();
        WorldGenCycle.invalidateCache();
        WorldGenCycle b = WorldGenCycle.fromConfig();

        assertNotSame(a, b, "after invalidation fromConfig() should build a new instance");

        // NOTE: WorldGenCycle's record equals() compares the int[] stageMultipliers by reference,
        // so two fresh builds are never .equals — assert behavioural equivalence instead (same
        // global config in → identical layout out).
        assertEquals(a.period(), b.period(), "period");
        assertEquals(a.netherLen(), b.netherLen(), "netherLen");
        assertEquals(a.endLen(), b.endLen(), "endLen");
        assertEquals(a.riseLen(), b.riseLen(), "riseLen");
        assertTrue(Arrays.equals(a.stageMultipliers(), b.stageMultipliers()), "stageMultipliers");
        for (int x = 0; x <= 40_000; x += 2_500) {
            assertEquals(a.netherRamp(x), b.netherRamp(x), 1e-9, "netherRamp@" + x);
            assertEquals(a.endMiddleRamp(x), b.endMiddleRamp(x), 1e-9, "endMiddleRamp@" + x);
            assertEquals(a.endIslandRamp(x), b.endIslandRamp(x), 1e-9, "endIslandRamp@" + x);
        }
    }
}
