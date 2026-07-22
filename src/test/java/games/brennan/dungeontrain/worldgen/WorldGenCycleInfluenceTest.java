package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Seam-safety proof for the {@link WorldGenCycle#netherInfluence}/{@link WorldGenCycle#endSegmentInfluence}
 * early-out predicates: a {@code false} MUST guarantee every ramp a hot path could evaluate in the
 * covered window is exactly 0 — a false negative would silently seam terrain in shipped worlds, the
 * worst bug class this feature can produce. Stride-1 sweeps over ≥2 full periods (plus a pre-anchor
 * stretch) on full / single-band / band-disabled / phase-shifted cycles, so period wrap-around and
 * every gating combination are pinned. False positives are fine (they only skip the optimisation);
 * a usefulness check still asserts the predicate does go false in the overworld gaps.
 */
final class WorldGenCycleInfluenceTest {

    /** The wavyX margin callers pass — pinned to the live bound so a drifting constant fails here. */
    private static final int MARGIN = NetherMountainTerrain.maxEdgeShift();

    /** Everything on: nether + End + upside-down (with exit fade/gap) + chuncks (fade/lead-gap). */
    private static final WorldGenCycle FULL = new WorldGenCycle(
            1000L, 300, 40, new int[] {1, 5, 20}, 0, 60, 50, 200, 100, 40, 200,
            50, 200, 150, 800, 400, 100, 120, 0.3, 0.5, 0);

    /** Nether only (End disabled) — the End-segment predicate must be constantly false. */
    private static final WorldGenCycle NETHER_ONLY =
            new WorldGenCycle(1000L, 300, 40, new int[] {1, 5, 20}, 0, 60, 50, 200, 0, 0, 0, 0, 0, 0, 0);

    /** End only (nether disabled) — the nether predicate must be constantly false. */
    private static final WorldGenCycle END_ONLY =
            new WorldGenCycle(1000L, 300, 0, new int[] {1}, 0, 0, 0, 0, 100, 40, 200, 0, 0, 0, 0);

    /** Both bands with a phase shift, so the window math is exercised off its natural alignment. */
    private static final WorldGenCycle PHASE_SHIFTED =
            new WorldGenCycle(1000L, 300, 40, new int[] {1, 5, 20}, 0, 60, 50, 200, 100, 40, 200, 0, 0, 0, 137);

    private static final WorldGenCycle[] CYCLES = {FULL, NETHER_ONLY, END_ONLY, PHASE_SHIFTED};

    @Test
    @DisplayName("!netherInfluence(x, margin) ⇒ netherHeightRamp/netherRamp == 0 across the whole ±margin window")
    void netherInfluenceIsConservative() {
        for (WorldGenCycle c : CYCLES) {
            long period = c.period();
            long lo = c.startX() - period;                    // includes a pre-anchor stretch
            long hi = c.startX() + 2 * period + 2L * MARGIN;  // ≥ 2 full periods → wrap covered
            for (long x = lo; x <= hi; x++) {                 // stride 1 — no sampling gaps
                if (c.netherInfluence(x, MARGIN)) continue;
                for (long xp = x - MARGIN; xp <= x + MARGIN; xp++) {
                    assertEquals(0.0, c.netherHeightRamp((int) xp), 0.0,
                            "false negative: heightRamp non-zero at x'=" + xp + " inside window of x=" + x);
                    assertEquals(0.0, c.netherRamp((int) xp), 0.0,
                            "false negative: netherRamp non-zero at x'=" + xp + " inside window of x=" + x);
                }
            }
        }
    }

    @Test
    @DisplayName("!endSegmentInfluence(x) ⇒ endMiddleRamp/endIslandRamp == 0 at x")
    void endInfluenceIsConservative() {
        for (WorldGenCycle c : CYCLES) {
            long period = c.period();
            long lo = c.startX() - period;
            long hi = c.startX() + 2 * period;
            for (long x = lo; x <= hi; x++) {
                if (c.endSegmentInfluence(x)) continue;
                assertEquals(0.0, c.endMiddleRamp((int) x), 0.0,
                        "false negative: endMiddleRamp non-zero at x=" + x);
                assertEquals(0.0, c.endIslandRamp((int) x), 0.0,
                        "false negative: endIslandRamp non-zero at x=" + x);
            }
        }
    }

    @Test
    @DisplayName("membership completeness: any non-zero ramp implies the matching influence is true")
    void influenceCoversAllNonZeroRamps() {
        for (WorldGenCycle c : CYCLES) {
            long period = c.period();
            for (long x = c.startX() - period; x <= c.startX() + 2 * period; x++) {
                if (c.netherHeightRamp((int) x) > 0.0 || c.netherRamp((int) x) > 0.0) {
                    assertTrue(c.netherInfluence(x, 0), "netherInfluence(x,0) must cover x=" + x);
                }
                if (c.endMiddleRamp((int) x) > 0.0 || c.endIslandRamp((int) x) > 0.0) {
                    assertTrue(c.endSegmentInfluence(x), "endSegmentInfluence must cover x=" + x);
                }
            }
        }
    }

    @Test
    @DisplayName("usefulness: the predicates DO go false in the overworld gaps (early-out actually fires)")
    void predicatesGoFalseOffBand() {
        // FULL's leading owGap is offset [0,300) from the anchor (phaseShift 0): its middle, minus the
        // margin, must be provably off-band or the whole optimisation is a no-op.
        assertFalse(FULL.netherInfluence(FULL.startX() + 150, MARGIN), "owGap centre should be off-band");
        assertFalse(FULL.endSegmentInfluence(FULL.startX() + 150), "owGap centre should be off-End");
        // Pre-anchor world is plain overworld regardless of margin.
        assertFalse(FULL.netherInfluence(FULL.startX() - 5000, MARGIN));
        assertFalse(FULL.endSegmentInfluence(FULL.startX() - 5000));
        // Disabled segments never influence anywhere.
        long p = NETHER_ONLY.period();
        for (long x = NETHER_ONLY.startX(); x < NETHER_ONLY.startX() + p; x += 7) {
            assertFalse(NETHER_ONLY.endSegmentInfluence(x), "End disabled ⇒ never influences, x=" + x);
        }
        for (long x = END_ONLY.startX(); x < END_ONLY.startX() + END_ONLY.period(); x += 7) {
            assertFalse(END_ONLY.netherInfluence(x, MARGIN), "nether disabled ⇒ never influences, x=" + x);
        }
    }

    @Test
    @DisplayName("empty cycle and pre-anchor windows: predicates are false, including a window straddling the anchor")
    void degenerateCases() {
        // Empty cycle (period 0): predicates are false everywhere, no div/mod-by-zero.
        WorldGenCycle empty = new WorldGenCycle(0L, 0, 0, new int[] {1}, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        assertFalse(empty.netherInfluence(0, MARGIN));
        assertFalse(empty.endSegmentInfluence(0));

        // Window straddling the anchor: x just before startX with the margin reaching past it must still
        // be evaluated (clamped, not discarded) — the leading owGap keeps it false, but a band starting
        // at offset 0 must be caught. Build one with owGap=0 so the nether band starts AT the anchor.
        WorldGenCycle noGap = new WorldGenCycle(1000L, 0, 40, new int[] {1, 5, 20}, 0, 60, 50, 200, 0, 0, 0, 0, 0, 0, 0);
        assertTrue(noGap.netherInfluence(noGap.startX() - MARGIN, MARGIN),
                "window reaching the anchor must see a band starting at offset 0");
        assertFalse(noGap.netherInfluence(noGap.startX() - MARGIN - 1, MARGIN),
                "window ending just before the anchor sees nothing");
    }
}
