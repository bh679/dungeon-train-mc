package games.brennan.dungeontrain.client;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeathCameraFlyTest {

    private static final double EPS = 1.0e-9;

    @Test
    void onlyTheSpotShotFliesIntoTheKiller() {
        List<DeathShot> seq = DeathCameraCycle.buildSequence(false, true, true, true);
        assertEquals("spot", seq.get(0).label());
        assertEquals("killer", seq.get(1).label());
        assertTrue(DeathCameraFly.fliesInto(seq.get(0), seq.get(1)), "body spot to killer");
        assertFalse(DeathCameraFly.fliesInto(seq.get(1), seq.get(2)), "killer to mob stays a cut");
        assertFalse(DeathCameraFly.fliesInto(seq.get(1), seq.get(0)), "stepping back is a cut");
        assertFalse(DeathCameraFly.fliesInto(null, seq.get(1)), "nothing drawn yet: no flight");
        DeathShot hazardSpot = DeathCameraCycle.buildSequence(false, true, false, true, false, true).get(0);
        assertEquals(DeathShot.Target.HAZARD, hazardSpot.target());
        assertTrue(DeathCameraFly.fliesInto(hazardSpot, seq.get(1)), "the killing block's spot flies too");
    }

    @Test
    void flightStartsAndLandsExactly() {
        Vector3d from = new Vector3d(0, 0, 0);
        Vector3d to = new Vector3d(10, 2, -4);
        assertEquals(0.0, DeathCameraFly.lens(from, to, 0.0).distance(from), EPS);
        assertEquals(0.0, DeathCameraFly.lens(from, to, 1.0).distance(to), EPS);
        assertEquals(0.0, DeathCameraFly.aim(from, to, 0.0).distance(from), EPS);
        assertEquals(0.0, DeathCameraFly.aim(from, to, 1.0).distance(to), EPS);
    }

    @Test
    void longFlightRisesFullyMidway() {
        Vector3d from = new Vector3d(0, 0, 0);
        Vector3d to = new Vector3d(20, 0, 0);
        Vector3d mid = DeathCameraFly.lens(from, to, 0.5);
        assertEquals(10.0, mid.x, EPS);
        assertEquals(DeathCameraFly.FLY_LIFT, mid.y, EPS);
    }

    @Test
    void shortHopBarelyRises() {
        Vector3d mid = DeathCameraFly.lens(new Vector3d(0, 0, 0), new Vector3d(0.8, 0, 0), 0.5);
        assertTrue(mid.y < DeathCameraFly.FLY_LIFT * 0.2, "a 0.8-block hop should not bob");
    }

    @Test
    void easeIsSmoothAndMonotonic() {
        assertEquals(0.0, DeathCameraFly.ease(0.0), EPS);
        assertEquals(0.5, DeathCameraFly.ease(0.5), EPS);
        assertEquals(1.0, DeathCameraFly.ease(1.0), EPS);
        double prev = -1;
        for (int i = 0; i <= 20; i++) {
            double e = DeathCameraFly.ease(i / 20.0);
            assertTrue(e >= prev);
            prev = e;
        }
        assertEquals(1.0, DeathCameraFly.progress(DeathCameraFly.FLY_TICKS * 2), EPS);
    }

    @Test
    void orbitRunsFromTheEndNearerTheIncomingLens() {
        Vector3d subject = new Vector3d(0, 0, 0);
        // Arc from angle 0 (+X) a quarter turn to +Z; a lens coming from +Z should start there.
        assertTrue(DeathCameraFly.reverseSweep(subject, 5, 2, 0.0, Math.PI / 2, new Vector3d(0, 2, 9)));
        assertFalse(DeathCameraFly.reverseSweep(subject, 5, 2, 0.0, Math.PI / 2, new Vector3d(9, 2, 0)));
    }

    @Test
    void noBlackAroundAFlight() {
        int hold = 60;
        for (double t = 0; t <= hold; t += 0.5) {
            assertEquals(0.0f, DeathCameraCycle.blackAlpha(t, hold, false, false), "t=" + t);
        }
        assertEquals(1.0f, DeathCameraCycle.blackAlpha(hold, hold, false, true), "a cut still fades out");
    }
}
