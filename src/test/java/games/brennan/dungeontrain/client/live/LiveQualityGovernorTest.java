package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveQualityGovernorTest {

    private static final long T0 = 1_000_000L;
    private static final long AFTER_COOLDOWN = T0 + LiveQualityGovernor.COOLDOWN_MS;

    private static LiveUploadHealth.Snapshot health(int samples, double utilisation, int kbps) {
        return new LiveUploadHealth.Snapshot(samples, utilisation, kbps);
    }

    @Test
    void ladderStepsDownFromTheCeilingAndStopsAtTheFloor() {
        assertArrayEquals(new int[]{2500, 2000, 1550, 1200, 900, 650, 500}, LiveQualityGovernor.ladder(2500));
        assertArrayEquals(new int[]{800, 640, 500}, LiveQualityGovernor.ladder(800));
        assertArrayEquals(new int[]{500}, LiveQualityGovernor.ladder(300));
    }

    @Test
    void aHealthyUploadStaysAtTheCeiling() {
        LiveQualityGovernor g = new LiveQualityGovernor(2500, 2500, 96, T0);
        assertEquals(OptionalInt.empty(), g.decide(health(10, 0.3, 9000), AFTER_COOLDOWN));
        assertEquals(2500, g.kbps());
        assertFalse(g.lowered());
    }

    @Test
    void aSaturatedUploadDropsStraightToTheRungItsSpeedCarries() {
        // saojianshiren's case: ~2.65 Mbit/s up for a 2.6 Mbit/s stream.
        LiveQualityGovernor g = new LiveQualityGovernor(2500, 2500, 96, T0);
        assertEquals(OptionalInt.of(1550), g.decide(health(2, 1.05, 2650), AFTER_COOLDOWN));
        assertTrue(g.lowered());
    }

    @Test
    void aVerySlowUploadGoesToTheBottomRung() {
        LiveQualityGovernor g = new LiveQualityGovernor(2500, 2500, 96, T0);
        assertEquals(OptionalInt.of(500), g.decide(health(3, 2.4, 700), AFTER_COOLDOWN));
    }

    @Test
    void stepsAtLeastOneRungEvenWhenTheSpeedLooksEnough() {
        // Saturated though the PUTs measure fast: latency-bound small segments. Still go down one.
        LiveQualityGovernor g = new LiveQualityGovernor(2500, 2500, 0, T0);
        assertEquals(OptionalInt.of(2000), g.decide(health(2, 0.95, 20_000), AFTER_COOLDOWN));
    }

    @Test
    void needsTwoSamplesAndTheCooldownBeforeActing() {
        LiveQualityGovernor g = new LiveQualityGovernor(2500, 2500, 96, T0);
        assertEquals(OptionalInt.empty(), g.decide(health(1, 1.5, 1000), AFTER_COOLDOWN));
        assertEquals(OptionalInt.empty(), g.decide(health(5, 1.5, 1000), T0 + 1000));
        assertTrue(g.decide(health(2, 1.5, 1000), AFTER_COOLDOWN).isPresent());
        // Just switched: the new rate's own segments speak first.
        assertEquals(OptionalInt.empty(), g.decide(health(5, 1.5, 1000), AFTER_COOLDOWN + 1000));
    }

    @Test
    void stepsBackUpOneRungAfterMinutesOfHeadroom() {
        LiveQualityGovernor g = new LiveQualityGovernor(2500, 1200, 96, T0);
        long early = T0 + LiveQualityGovernor.UP_AFTER_MS - 1;
        assertEquals(OptionalInt.empty(), g.decide(health(20, 0.2, 9000), early));
        long later = T0 + LiveQualityGovernor.UP_AFTER_MS;
        assertEquals(OptionalInt.empty(), g.decide(health(5, 0.2, 9000), later), "too few samples");
        assertEquals(OptionalInt.of(1550), g.decide(health(6, 0.2, 9000), later));
    }

    @Test
    void doesNotStepUpWhenTheNextRungWouldCrowdTheUpload() {
        LiveQualityGovernor g = new LiveQualityGovernor(2500, 1200, 96, T0);
        // 0.5 now -> ~0.64 at 1550: over the 0.55 bar.
        assertEquals(OptionalInt.empty(), g.decide(health(10, 0.5, 3000), T0 + LiveQualityGovernor.UP_AFTER_MS));
        assertEquals(1200, g.kbps());
    }

    @Test
    void neverAboveTheCeilingNorBelowTheBottom() {
        LiveQualityGovernor top = new LiveQualityGovernor(2500, 2500, 0, T0);
        assertEquals(OptionalInt.empty(), top.decide(health(50, 0.01, 99_000), T0 + 10 * LiveQualityGovernor.UP_AFTER_MS));
        LiveQualityGovernor bottom = new LiveQualityGovernor(2500, 500, 0, T0);
        assertEquals(OptionalInt.empty(), bottom.decide(health(5, 3.0, 100), AFTER_COOLDOWN));
    }

    @Test
    void startsFromTheRememberedSpeed() {
        assertEquals(2500, LiveQualityGovernor.startKbps(2500, 0, 96), "nothing remembered");
        assertEquals(2500, LiveQualityGovernor.startKbps(2500, 20_000, 96));
        assertEquals(1550, LiveQualityGovernor.startKbps(2500, 2650, 96));
        assertEquals(650, LiveQualityGovernor.startKbps(2500, 1000, 96));
        assertEquals(500, LiveQualityGovernor.startKbps(2500, 600, 96));
    }

    @Test
    void aSlowRememberedLinkStartsAtASmallerPicture() {
        assertEquals(1280, LiveQualityGovernor.startWidth(1280, 0));
        assertEquals(1280, LiveQualityGovernor.startWidth(1280, 5000));
        assertEquals(854, LiveQualityGovernor.startWidth(1280, 1200));
        assertEquals(640, LiveQualityGovernor.startWidth(640, 1200), "never wider than the player's own");
    }
}
