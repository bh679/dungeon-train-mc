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
 * [2940,3240), exit crossfade [3240,4040) → the mirrored stretch is [2900,4040); the next repeat's lead
 * starts at 6090.</p>
 */
final class UpsideDownTrackFlattenTest {

    private static final double EPS = 1e-9;
    private static final WorldGenCycle E =
            new WorldGenCycle(1000L, 300, 40, new int[] {1, 5, 20}, 0, 60, 50, 200, 100, 40, 200, 50, 200, 150, 800, 0);
    private static final int STRETCH_START = 2900;
    private static final int STRETCH_END = 4040;   // exclusive

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
    @DisplayName("track weight: 1 near the track, smooth fade, 0 far away — symmetric about the centre")
    void trackWeightFalloff() {
        int c = 2;
        assertEquals(1.0, UpsideDownTrackFlatten.trackWeight(c, c), EPS);
        assertEquals(1.0, UpsideDownTrackFlatten.trackWeight(c + UpsideDownTrackFlatten.TRACK_INNER, c), EPS);
        assertEquals(0.0, UpsideDownTrackFlatten.trackWeight(c + UpsideDownTrackFlatten.TRACK_OUTER, c), EPS);
        assertEquals(0.0, UpsideDownTrackFlatten.trackWeight(c - 5000, c), EPS);
        double prev = 1.0;
        for (int d = 0; d <= UpsideDownTrackFlatten.TRACK_OUTER + 4; d++) {
            double w = UpsideDownTrackFlatten.trackWeight(c + d, c);
            assertEquals(w, UpsideDownTrackFlatten.trackWeight(c - d, c), EPS);
            assertTrue(w <= prev + EPS, "not monotone at d=" + d);
            prev = w;
        }
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
        UpsideDownTrackFlatten.Context on = new UpsideDownTrackFlatten.Context(true, E, 2);
        UpsideDownTrackFlatten.Context off = new UpsideDownTrackFlatten.Context(false, E, 2);
        assertEquals(0.0, UpsideDownTrackFlatten.weight(null, 3000, 2), EPS);
        assertEquals(0.0, UpsideDownTrackFlatten.weight(off, 3000, 2), EPS);
        assertEquals(1.0, UpsideDownTrackFlatten.weight(on, 3000, 2), EPS);
        assertEquals(0.0, UpsideDownTrackFlatten.weight(on, 3000, 2 + 400), EPS);
        assertEquals(0.0, UpsideDownTrackFlatten.weight(on, 1500, 2), EPS);
    }
}
