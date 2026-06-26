package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Pure tests for {@link TrainPhase#fadeNetherGate} — the Overworld↔Nether gate dither that powers
 * the world-feature (tunnel / track / pillar) noise-fade across the Nether crossfade. The
 * world-reading wrapper {@link TrainPhase#gatePhaseAt} (and its wiring into
 * {@code GateContext.atWorldX}) needs a live {@code ServerLevel}, so it is covered by the in-game
 * Gate-2 test — same split as {@link TrainPhaseTest} (which leaves {@code phaseAt} to in-game tests).
 */
final class TrainPhaseGateFadeTest {

    @Test
    @DisplayName("VOID / END are never touched, whatever the ramp or noise")
    void nonNetherPhasesUntouched() {
        for (double ramp : new double[] {0.0, 0.25, 0.5, 0.75, 1.0}) {
            for (double noise : new double[] {0.0, 0.5, 0.99}) {
                assertSame(TrainPhase.VOID, TrainPhase.fadeNetherGate(TrainPhase.VOID, ramp, noise));
                assertSame(TrainPhase.END, TrainPhase.fadeNetherGate(TrainPhase.END, ramp, noise));
            }
        }
    }

    @Test
    @DisplayName("Outside the crossfade (ramp 0 or 1) the base phase passes through unchanged")
    void outsideCrossfadeIsHard() {
        // ramp <= 0: pure-overworld approach — base survives regardless of noise.
        assertSame(TrainPhase.OVERWORLD, TrainPhase.fadeNetherGate(TrainPhase.OVERWORLD, 0.0, 0.01));
        assertSame(TrainPhase.OVERWORLD, TrainPhase.fadeNetherGate(TrainPhase.OVERWORLD, 0.0, 0.99));
        // ramp >= 1: real-Nether core — base (NETHER, per phaseAt) survives regardless of noise.
        assertSame(TrainPhase.NETHER, TrainPhase.fadeNetherGate(TrainPhase.NETHER, 1.0, 0.01));
        assertSame(TrainPhase.NETHER, TrainPhase.fadeNetherGate(TrainPhase.NETHER, 1.0, 0.99));
    }

    @Test
    @DisplayName("Inside the crossfade the fade overrides base both ways: noise < ramp => NETHER")
    void insideCrossfadeDithers() {
        // OW-base half of the crossfade (ramp < 0.5): a low noise still flips it to NETHER.
        assertSame(TrainPhase.NETHER, TrainPhase.fadeNetherGate(TrainPhase.OVERWORLD, 0.3, 0.10));
        assertSame(TrainPhase.OVERWORLD, TrainPhase.fadeNetherGate(TrainPhase.OVERWORLD, 0.3, 0.50));
        // NETHER-base half (ramp >= 0.5): a high noise flips it back to OVERWORLD.
        assertSame(TrainPhase.OVERWORLD, TrainPhase.fadeNetherGate(TrainPhase.NETHER, 0.7, 0.90));
        assertSame(TrainPhase.NETHER, TrainPhase.fadeNetherGate(TrainPhase.NETHER, 0.7, 0.50));
    }

    @Test
    @DisplayName("At the midpoint ramp=0.5 the dither splits exactly at noise=0.5 (strict <)")
    void midpointSplit() {
        assertSame(TrainPhase.NETHER, TrainPhase.fadeNetherGate(TrainPhase.OVERWORLD, 0.5, 0.49));
        // noise == ramp is NOT below ramp → OVERWORLD (strict inequality, matches the terrain recolour).
        assertSame(TrainPhase.OVERWORLD, TrainPhase.fadeNetherGate(TrainPhase.OVERWORLD, 0.5, 0.50));
        assertSame(TrainPhase.OVERWORLD, TrainPhase.fadeNetherGate(TrainPhase.NETHER, 0.5, 0.51));
    }

    @Test
    @DisplayName("P(NETHER) tracks the ramp: result is NETHER iff noise < ramp across the whole grid")
    void probabilityTracksRamp() {
        // The fade is monotonic in ramp — raising it only ever turns more noise values NETHER, never
        // fewer — because the decision is exactly `noise < ramp`. Verify that contract over a grid so
        // the deep-crossfade columns read mostly NETHER and the shallow ones mostly OVERWORLD.
        for (int ri = 1; ri <= 9; ri++) {
            double ramp = ri / 10.0;                 // 0.1 .. 0.9, all strictly inside the crossfade
            for (int ni = 0; ni <= 100; ni++) {
                double noise = ni / 100.0;           // 0.00 .. 1.00
                TrainPhase expected = noise < ramp ? TrainPhase.NETHER : TrainPhase.OVERWORLD;
                assertEquals(expected, TrainPhase.fadeNetherGate(TrainPhase.OVERWORLD, ramp, noise),
                    "ramp=" + ramp + " noise=" + noise);
            }
        }
    }
}
