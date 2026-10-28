package games.brennan.dungeontrain.worldgen;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpheresCometsTest {

    @Test
    void firstLapWaitsForTheVoidSky() {
        assertFalse(SpheresComets.active(0L, 0.0, 1.0));
        assertFalse(SpheresComets.active(0L, 0.49, 1.0));
        assertTrue(SpheresComets.active(0L, 0.5, 1.0));
        assertFalse(SpheresComets.active(0L, 1.0, 0.0), "the End band's own sky is not the spheres band");
        assertEquals(0.0, SpheresComets.chancePerTick(0L, 0.2, 1.0));
    }

    @Test
    void laterLapsFallFromTheBandStart() {
        assertTrue(SpheresComets.active(1L, 0.0, 0.01));
        assertTrue(SpheresComets.active(4L, 0.0, 1.0));
        assertFalse(SpheresComets.active(1L, 1.0, 0.0));
    }

    @Test
    void intervalClimbsEachLapToAFloor() {
        assertEquals(20.0, SpheresComets.intervalSeconds(0L), 1e-9);
        assertEquals(5.0, SpheresComets.intervalSeconds(1L), 1e-9);
        assertEquals(2.5, SpheresComets.intervalSeconds(2L), 1e-9);
        assertEquals(2.0, SpheresComets.intervalSeconds(3L), 1e-9);
        assertEquals(2.0, SpheresComets.intervalSeconds(1_000L), 1e-9);
    }

    @Test
    void chanceIsOnePerIntervalOfTicks() {
        assertEquals(1.0 / 400.0, SpheresComets.chancePerTick(0L, 1.0, 1.0), 1e-12);
        assertEquals(1.0 / 100.0, SpheresComets.chancePerTick(1L, 0.0, 1.0), 1e-12);
    }

    @Test
    void missesEndOnceWellBelowTheTrain() {
        assertFalse(SpheresComets.fellPastTrain(70.0, 78.0), "a near miss beside the deck still flies");
        assertFalse(SpheresComets.fellPastTrain(62.0, 78.0));
        assertTrue(SpheresComets.fellPastTrain(61.9, 78.0));
    }

    @Test
    void eachCometSpinsAboutItsOwnFixedAxis() {
        Vector3f axis = SpheresComets.spinAxis(7);
        assertEquals(1.0, axis.length(), 1e-5);
        assertEquals(axis, SpheresComets.spinAxis(7), "same comet, same axis for every player");
        assertNotEquals(axis, SpheresComets.spinAxis(8));
    }

    @Test
    void oneTurnEverySpinPeriod() {
        float quarter = SpheresComets.spinAngle(2, SpheresComets.SPIN_TICKS_PER_TURN / 4.0);
        assertEquals(Math.PI / 2.0, quarter, 1e-5);
        assertEquals(0.0, SpheresComets.spinAngle(2, SpheresComets.SPIN_TICKS_PER_TURN), 1e-5);
        assertEquals(-quarter, SpheresComets.spinAngle(3, SpheresComets.SPIN_TICKS_PER_TURN / 4.0), 1e-5);
    }

    @Test
    void shakeIsHardestAtTheImpactAndGoneBeyondItsRadius() {
        assertEquals(1.0, SpheresComets.shakeStrength(0.0), 1e-9);
        assertTrue(SpheresComets.shakeStrength(5.0) > SpheresComets.shakeStrength(20.0));
        assertTrue(SpheresComets.shakeStrength(20.0) > SpheresComets.shakeStrength(39.0));
        assertEquals(0.0, SpheresComets.shakeStrength(SpheresComets.SHAKE_RADIUS_BLOCKS), 1e-9);
        assertEquals(0.0, SpheresComets.shakeStrength(200.0), 1e-9);
    }

    @Test
    void roarBuildsSteadilyAllTheWayIn() {
        assertEquals(0.0, SpheresComets.roarBuild(SpheresComets.ROAR_REACH_BLOCKS), 1e-9);
        assertEquals(0.0, SpheresComets.roarBuild(300.0), 1e-9);
        assertEquals(1.0, SpheresComets.roarBuild(0.0), 1e-9);
        double last = -1.0;
        for (double d = 128.0; d >= 0.0; d -= 8.0) {
            double now = SpheresComets.roarBuild(d);
            assertTrue(now > last, "roar keeps rising as the comet closes, at " + d);
            last = now;
        }
        assertEquals(0.5F, SpheresComets.roarPitch(200.0), 1e-6);
        assertEquals(1.0F, SpheresComets.roarPitch(0.0), 1e-6);
    }

    @Test
    void distantCometsFollowTheRealOnesFewerEarly() {
        assertEquals(0.0, SpheresComets.distantChancePerTick(0L, 0.2, 1.0), "none before the void sky");
        assertEquals(0.0, SpheresComets.distantChancePerTick(4L, 1.0, 0.0), "none outside the band");
        assertEquals(SpheresComets.chancePerTick(0L, 1.0, 1.0) * SpheresComets.DISTANT_PER_REAL_EARLY,
            SpheresComets.distantChancePerTick(0L, 1.0, 1.0), 1e-12);
        assertEquals(SpheresComets.chancePerTick(1L, 0.0, 1.0) * SpheresComets.DISTANT_PER_REAL,
            SpheresComets.distantChancePerTick(1L, 0.0, 1.0), 1e-12);
    }

    @Test
    void distantCometsGrowInRatherThanAppear() {
        assertEquals(0.0, SpheresComets.distantGrowth(0.0), 1e-12);
        assertEquals(0.5, SpheresComets.distantGrowth(SpheresComets.DISTANT_GROW_TICKS / 2.0), 1e-12);
        assertEquals(1.0, SpheresComets.distantGrowth(SpheresComets.DISTANT_GROW_TICKS), 1e-12);
        assertEquals(1.0, SpheresComets.distantGrowth(500.0), 1e-12);
        assertTrue(SpheresComets.distantGrowth(5.0) < SpheresComets.distantGrowth(10.0));
    }

    @Test
    void nearMissesAreTheCometsNotAimedAtTheTrain() {
        assertEquals(SpheresComets.chancePerTick(1L, 0.0, 1.0) * 2.0 / 3.0,
            SpheresComets.nearMissChancePerTick(1L, 0.0, 1.0, 0.0), 1e-12);
        assertEquals(SpheresComets.chancePerTick(0L, 1.0, 1.0) * (1.0 - 1.0 / 60.0),
            SpheresComets.nearMissChancePerTick(0L, 1.0, 1.0, 0.0), 1e-12);
        assertEquals(0.0, SpheresComets.nearMissChancePerTick(0L, 0.2, 1.0, 0.0), "none before the void sky");
    }

    @Test
    void firstLapAimsMoreAtTheTrainTheDeeperYouGo() {
        assertEquals(1.0 / 60.0, SpheresComets.aimedShare(0L, 0.0), 1e-12);
        assertEquals(1.0 / 3.0, SpheresComets.aimedShare(0L, 1.0), 1e-12);
        assertEquals((1.0 / 60.0 + 1.0 / 3.0) / 2.0, SpheresComets.aimedShare(0L, 0.5), 1e-12);
        assertEquals(1.0 / 3.0, SpheresComets.aimedShare(0L, 3.0), 1e-12, "clamped past the end");
        assertEquals(1.0 / 3.0, SpheresComets.aimedShare(1L, 0.0), 1e-12, "later laps ignore depth");
        assertEquals(1.0 / 3.0, SpheresComets.aimedShare(5L, 1.0), 1e-12);
    }

    @Test
    void depthRunsAcrossTheEndSkyStretch() {
        assertEquals(0.0, SpheresComets.firstLapDepth(-1L, 1000L, 3000L), 1e-12, "outside the core");
        assertEquals(0.0, SpheresComets.firstLapDepth(500L, 1000L, 3000L), 1e-12);
        assertEquals(0.5, SpheresComets.firstLapDepth(2000L, 1000L, 3000L), 1e-12);
        assertEquals(1.0, SpheresComets.firstLapDepth(3500L, 1000L, 3000L), 1e-12);
    }
}
