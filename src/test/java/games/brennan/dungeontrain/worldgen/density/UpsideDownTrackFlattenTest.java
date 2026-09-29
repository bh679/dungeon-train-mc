package games.brennan.dungeontrain.worldgen.density;

import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-math tests for {@link UpsideDownTrackFlatten} — the upside-down band's keep-mountains-off-the-track
 * erosion weighting. No NeoForge bootstrap.
 *
 * <p>Fixture (same as {@code WorldGenCycleTest}'s exit-crossfade case): entry lead [2900,2940), band
 * [2940,3190) (entry fade + core — no trailing fade before the crossfade), exit crossfade [3190,3990) →
 * the mirrored stretch is [2900,3990); the next repeat's lead starts at 6040.</p>
 */
final class UpsideDownTrackFlattenTest {

    private static final double EPS = 1e-9;
    private static final WorldGenCycle E =
            new WorldGenCycle(1000L, 300, 40, new int[] {1, 5, 20}, 0, 60, 50, 200, 100, 40, 200, 50, 200, 150, 800, 0);
    private static final int STRETCH_START = 2900;
    private static final int STRETCH_END = 3990;   // exclusive

    @Test
    @DisplayName("stretch membership covers lead, band and exit crossfade — nothing either side")
    void stretchMembership() {
        assertFalse(E.isInUpsideDownStretch(STRETCH_START - 1));
        assertTrue(E.isInUpsideDownStretch(STRETCH_START));
        assertTrue(E.isInUpsideDownStretch(3000));
        assertTrue(E.isInUpsideDownStretch(3500));
        assertTrue(E.isInUpsideDownStretch(STRETCH_END - 1));
        assertFalse(E.isInUpsideDownStretch(STRETCH_END));
    }

    @Test
    @DisplayName("influence early-out is conservative: never false within BAND_RAMP of the stretch")
    void influenceIsConservative() {
        int r = UpsideDownTrackFlatten.BAND_RAMP;
        for (int x = 0; x < 12000; x++) {
            if (!E.upsideDownInfluence(x, r)) {
                assertEquals(0.0, UpsideDownTrackFlatten.bandWeight(E, x), EPS, "x=" + x);
                for (int d = -r; d <= r; d += 4) {
                    assertFalse(E.isInUpsideDownStretch(x + d), "x=" + x + " d=" + d);
                }
            }
        }
        assertFalse(E.upsideDownInfluence(1500, r));   // deep in the first overworld gap / Nether
    }

    @Test
    @DisplayName("band weight: 1 in the stretch, smooth ramp outside, 0 beyond the ramp")
    void bandWeightRamps() {
        int r = UpsideDownTrackFlatten.BAND_RAMP;
        assertEquals(1.0, UpsideDownTrackFlatten.bandWeight(E, STRETCH_START), EPS);
        assertEquals(1.0, UpsideDownTrackFlatten.bandWeight(E, 3500), EPS);
        assertEquals(1.0, UpsideDownTrackFlatten.bandWeight(E, STRETCH_END - 1), EPS);
        assertEquals(0.0, UpsideDownTrackFlatten.bandWeight(E, STRETCH_START - r - 1), EPS);
        assertEquals(0.0, UpsideDownTrackFlatten.bandWeight(E, STRETCH_END - 1 + r + 1), EPS);
        assertEquals(0.5, UpsideDownTrackFlatten.bandWeight(E, STRETCH_START - r / 2), EPS);

        // Continuous and monotone across both ramps: no jump bigger than one ramp step.
        double maxStep = 1.6 / r;   // smoothstep slope peaks at 1.5/r
        double prev = UpsideDownTrackFlatten.bandWeight(E, STRETCH_START - r - 10);
        for (int x = STRETCH_START - r - 9; x <= STRETCH_START; x++) {
            double w = UpsideDownTrackFlatten.bandWeight(E, x);
            assertTrue(w >= prev - EPS, "rising ramp not monotone at x=" + x);
            assertTrue(w - prev <= maxStep, "rising ramp jumps at x=" + x);
            prev = w;
        }
        prev = UpsideDownTrackFlatten.bandWeight(E, STRETCH_END - 1);
        for (int x = STRETCH_END; x <= STRETCH_END + r + 10; x++) {
            double w = UpsideDownTrackFlatten.bandWeight(E, x);
            assertTrue(w <= prev + EPS, "falling ramp not monotone at x=" + x);
            assertTrue(prev - w <= maxStep, "falling ramp jumps at x=" + x);
            prev = w;
        }
    }

    @Test
    @DisplayName("track weight: 1 near the track, smooth fade, 0 beyond the edge — at both edge extremes")
    void trackWeightFalloff() {
        int c = 2;
        for (double open : new double[] {0.0, 0.5, 1.0}) {
            assertEquals(1.0, UpsideDownTrackFlatten.trackWeight(c, c, open), EPS);
            assertEquals(1.0, UpsideDownTrackFlatten.trackWeight(c + UpsideDownTrackFlatten.TRACK_INNER_MIN, c, open), EPS);
            assertEquals(0.0, UpsideDownTrackFlatten.trackWeight(c + UpsideDownTrackFlatten.TRACK_OUTER_MAX, c, open), EPS);
            double prev = 1.0;
            for (int d = 0; d <= UpsideDownTrackFlatten.TRACK_OUTER_MAX + 4; d++) {
                double w = UpsideDownTrackFlatten.trackWeight(c + d, c, open);
                assertEquals(w, UpsideDownTrackFlatten.trackWeight(c - d, c, open), EPS);
                assertTrue(w <= prev + EPS, "not monotone at d=" + d + " open=" + open);
                prev = w;
            }
        }
        // Tightest edge: mountains may stand one chunk from the track; widest: flat out to 32, gone by 160.
        assertEquals(0.0, UpsideDownTrackFlatten.trackWeight(c + UpsideDownTrackFlatten.TRACK_OUTER_MIN, c, 0.0), EPS);
        assertEquals(1.0, UpsideDownTrackFlatten.trackWeight(c + UpsideDownTrackFlatten.TRACK_INNER_MAX, c, 1.0), EPS);
        assertTrue(UpsideDownTrackFlatten.trackWeight(c + 100, c, 1.0) > 0.0);
    }

    @Test
    @DisplayName("edge openness wanders smoothly along X, reaches both extremes, and differs per side")
    void edgeOpennessIsNoisyAndSmooth() {
        long seed = 1450L;
        double lo = 1.0, hi = 0.0, maxStep = 0.0;
        int differ = 0;
        double prev = UpsideDownTrackFlatten.edgeOpenness(seed, 0, true);
        for (int x = 1; x < 20000; x++) {
            double o = UpsideDownTrackFlatten.edgeOpenness(seed, x, true);
            assertTrue(o >= 0.0 && o <= 1.0);
            lo = Math.min(lo, o);
            hi = Math.max(hi, o);
            maxStep = Math.max(maxStep, Math.abs(o - prev));
            prev = o;
            if (Math.abs(o - UpsideDownTrackFlatten.edgeOpenness(seed, x, false)) > 0.2) differ++;
        }
        assertTrue(lo < 0.05, "edge should pull in tight somewhere: min=" + lo);
        assertTrue(hi > 0.95, "edge should open wide somewhere: max=" + hi);
        assertTrue(maxStep < 0.1, "edge should be smooth along X: step=" + maxStep);
        assertTrue(differ > 2000, "the two sides should wander independently");
        assertEquals(UpsideDownTrackFlatten.edgeOpenness(seed, 1234, true),
                UpsideDownTrackFlatten.edgeOpenness(seed, 1234, true), EPS);   // deterministic
    }

    @Test
    @DisplayName("apply only ever raises erosion toward the floor, and is identity at weight 0")
    void applyNeverLowers() {
        double floor = UpsideDownTrackFlatten.EROSION_FLOOR;
        for (double e = -1.0; e <= 1.0; e += 0.05) {
            assertEquals(e, UpsideDownTrackFlatten.apply(e, 0.0), EPS);
            for (double w = 0.0; w <= 1.0; w += 0.1) {
                double out = UpsideDownTrackFlatten.apply(e, w);
                assertTrue(out >= e - EPS);
                assertTrue(out <= Math.max(e, floor) + EPS);
            }
            assertEquals(Math.max(e, floor), UpsideDownTrackFlatten.apply(e, 1.0), EPS);
        }
    }

    @Test
    @DisplayName("combined weight is 0 without a context, disabled, or far from the track")
    void combinedWeight() {
        UpsideDownTrackFlatten.Context on = new UpsideDownTrackFlatten.Context(true, E, 2, 1450L);
        UpsideDownTrackFlatten.Context off = new UpsideDownTrackFlatten.Context(false, E, 2, 1450L);
        double mountain = UpsideDownTrackFlatten.MOUNTAIN_EROSION - 0.1;
        assertEquals(0.0, UpsideDownTrackFlatten.weight(null, 3000, 2, mountain), EPS);
        assertEquals(0.0, UpsideDownTrackFlatten.weight(off, 3000, 2, mountain), EPS);
        assertEquals(1.0, UpsideDownTrackFlatten.weight(on, 3000, 2, mountain), EPS);
        assertEquals(0.0, UpsideDownTrackFlatten.weight(on, 3000, 2 + 400, mountain), EPS);
        assertEquals(0.0, UpsideDownTrackFlatten.weight(on, 1500, 2, mountain), EPS);
        // the line runs through hills-or-flatter: nothing is flattened, even in the band
        assertEquals(0.0, UpsideDownTrackFlatten.weight(on, 3000, 2, UpsideDownTrackFlatten.HILL_EROSION), EPS);
        assertEquals(0.0, UpsideDownTrackFlatten.weight(on, 3000, 2, 0.3), EPS);
    }

    @Test
    @DisplayName("the mountain gate: shut at hills-or-flatter, open at mountain erosion, monotone between")
    void mountainGate() {
        assertEquals(0.0, UpsideDownTrackFlatten.mountainGate(UpsideDownTrackFlatten.HILL_EROSION), EPS);
        assertEquals(0.0, UpsideDownTrackFlatten.mountainGate(0.5), EPS);
        assertEquals(1.0, UpsideDownTrackFlatten.mountainGate(UpsideDownTrackFlatten.MOUNTAIN_EROSION), EPS);
        assertEquals(1.0, UpsideDownTrackFlatten.mountainGate(-0.9), EPS);
        double prev = 0.0;
        for (double e = UpsideDownTrackFlatten.HILL_EROSION; e >= UpsideDownTrackFlatten.MOUNTAIN_EROSION; e -= 0.005) {
            double g = UpsideDownTrackFlatten.mountainGate(e);
            assertTrue(g >= prev - EPS, "monotone at " + e);
            prev = g;
        }
    }
}
