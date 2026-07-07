package games.brennan.dungeontrain.ship.sable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static games.brennan.dungeontrain.ship.sable.PhysicsSubstepTuner.HIGH_WATER;
import static games.brennan.dungeontrain.ship.sable.PhysicsSubstepTuner.LOW_SUBSTEPS;
import static games.brennan.dungeontrain.ship.sable.PhysicsSubstepTuner.LOW_WATER;
import static games.brennan.dungeontrain.ship.sable.PhysicsSubstepTuner.decideSubsteps;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic coverage for {@link PhysicsSubstepTuner#decideSubsteps} — the adaptive
 * substep decision for issue #642 (drop 2→1 on long trains, keep full fidelity on
 * short ones, hysteresis in the band). Registry-free ints, so no Minecraft
 * bootstrap — same pattern as {@code TrainPassengerExemptionTest}. Expectations are
 * derived from the package-private thresholds so tuning them here can't stale the
 * test.
 */
final class PhysicsSubstepTunerTest {

    /** The band must have strictly-interior counts for the hysteresis tests. */
    @Test
    @DisplayName("thresholds form a usable hysteresis band")
    void bandIsUsable() {
        assertTrue(HIGH_WATER - LOW_WATER >= 2, "band needs an interior for hysteresis to matter");
        assertTrue(LOW_SUBSTEPS >= 1, "must never drop below one substep");
    }

    @Test
    @DisplayName("at or above HIGH_WATER → drop to LOW_SUBSTEPS, regardless of prior mode")
    void aboveHighWater_drops() {
        assertEquals(LOW_SUBSTEPS, decideSubsteps(HIGH_WATER, 2, false));
        assertEquals(LOW_SUBSTEPS, decideSubsteps(HIGH_WATER + 5, 2, false));
        assertEquals(LOW_SUBSTEPS, decideSubsteps(HIGH_WATER + 5, 2, true));
    }

    @Test
    @DisplayName("at or below LOW_WATER → restore baseline, regardless of prior mode")
    void belowLowWater_restores() {
        assertEquals(2, decideSubsteps(LOW_WATER, 2, true));
        assertEquals(2, decideSubsteps(LOW_WATER - 5, 2, true));
        assertEquals(2, decideSubsteps(LOW_WATER - 5, 2, false));
        assertEquals(2, decideSubsteps(0, 2, false)); // empty train → full baseline
    }

    @Test
    @DisplayName("inside the band → hold the current mode (hysteresis, no flapping)")
    void insideBand_holdsMode() {
        int mid = LOW_WATER + 1; // strictly inside (guarded by bandIsUsable)
        assertEquals(LOW_SUBSTEPS, decideSubsteps(mid, 2, true),  "was lowered → stay lowered");
        assertEquals(2, decideSubsteps(mid, 2, false), "was full → stay full");
    }

    @Test
    @DisplayName("a non-default baseline is honoured on both edges")
    void nonDefaultBaseline() {
        assertEquals(LOW_SUBSTEPS, decideSubsteps(HIGH_WATER, 3, false));
        assertEquals(3, decideSubsteps(LOW_WATER, 3, true));
        assertEquals(3, decideSubsteps(LOW_WATER + 1, 3, false));
    }

    @Test
    @DisplayName("baseline already at/below LOW_SUBSTEPS → nothing to reduce, ever")
    void baselineAtFloor_noOp() {
        assertEquals(LOW_SUBSTEPS, decideSubsteps(HIGH_WATER + 100, LOW_SUBSTEPS, false));
        assertEquals(LOW_SUBSTEPS, decideSubsteps(HIGH_WATER + 100, LOW_SUBSTEPS, true));
        assertEquals(0, decideSubsteps(HIGH_WATER + 100, 0, false));
    }
}
