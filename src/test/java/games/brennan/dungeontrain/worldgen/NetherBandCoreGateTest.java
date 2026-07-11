package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the pure {@link NetherBand#isInNetherBiome(WorldGenCycle, int)} overload that the corridor
 * cleanup uses for its hoisted, per-column band gate. It must stay byte-identical to the live
 * predicate — End band wins, then {@code netherRamp >= NETHER_CORE_RAMP} — so the section sweep never
 * skips a real Nether-core column (which would leave basalt on the tracks).
 */
final class NetherBandCoreGateTest {

    /** Nether band on, End band off: startX 0, owGap 100, 3 mountain stages of 40, coreFade 50, coreHold 200. */
    private static final WorldGenCycle NETHER_ONLY =
        new WorldGenCycle(0L, 100, 40, new int[] {1, 2, 4}, 0, 0, 50, 200, 0, 0, 0, 0, 0, 0);

    /** Both bands present, so the sweep below also exercises the End-wins exclusion branch. */
    private static final WorldGenCycle NETHER_AND_END =
        new WorldGenCycle(0L, 100, 40, new int[] {1, 2, 4}, 0, 0, 50, 200, 100, 40, 200, 0, 0, 0);

    @Test
    @DisplayName("overload == spec predicate (End-wins ∧ netherRamp ≥ core) across a full cycle, both bands")
    void matchesSpecAcrossSweep() {
        long period = NETHER_AND_END.period();
        for (int x = -50; x <= period + 50; x++) {
            boolean expected = NETHER_AND_END.endMiddleRamp(x) <= 0.0
                && NETHER_AND_END.netherRamp(x) >= NetherBand.NETHER_CORE_RAMP;
            assertEquals(expected, NetherBand.isInNetherBiome(NETHER_AND_END, x), "mismatch at worldX=" + x);
        }
    }

    @Test
    @DisplayName("true in the real-Nether core, false in the overworld gap")
    void coreVsGap() {
        // Core centre: netherStart(owGap 100) + riseLen(120) + megaHold(0) + coreFade(50) + coreHold/2(100) = 370.
        assertTrue(NetherBand.isInNetherBiome(NETHER_ONLY, 370), "core column should read as Nether biome");
        // Overworld gap before the band (cycle offset < owGap).
        assertFalse(NetherBand.isInNetherBiome(NETHER_ONLY, 50), "overworld-gap column is not Nether");
    }

    @Test
    @DisplayName("isInNetherBand = netherrack present (netherRamp > 0); it is a superset of the biome core")
    void bandIsWiderThanCore() {
        long period = NETHER_ONLY.period();
        boolean foundBandButNotCore = false;
        for (int x = -50; x <= period + 50; x++) {
            boolean band = NetherBand.isInNetherBand(NETHER_ONLY, x);
            boolean core = NetherBand.isInNetherBiome(NETHER_ONLY, x);
            // Spec: End-wins, then netherRamp > 0.
            boolean expected = NETHER_ONLY.endMiddleRamp(x) <= 0.0 && NETHER_ONLY.netherRamp(x) > 0.0;
            assertEquals(expected, band, "band spec mismatch at worldX=" + x);
            // Every core column is also in-band (the train phase gate must cover the whole core).
            if (core) assertTrue(band, "core column must also be in-band at worldX=" + x);
            if (band && !core) foundBandButNotCore = true;
        }
        // The netherrack crossfade (0 < netherRamp < 0.5) is the whole point: in-band, below the core —
        // so a NETHER-gated template spawns there instead of only deep in the core.
        assertTrue(foundBandButNotCore,
            "the netherrack crossfade must read as in-band while below the biome core");
    }
}
