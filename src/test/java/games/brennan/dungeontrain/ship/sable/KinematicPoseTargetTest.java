package games.brennan.dungeontrain.ship.sable;

import org.joml.Vector3d;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The exact carriage pose that replaces Rapier's float read-back. Plain doubles, no bootstrap. */
final class KinematicPoseTargetTest {

    private static final double EPS = 1e-12;

    @Test
    @DisplayName("nothing recorded is never fresh")
    void emptyIsStale() {
        KinematicPoseTarget target = new KinematicPoseTarget();
        assertFalse(target.isFreshFor(0L));
        assertFalse(target.isFreshFor(Long.MAX_VALUE));
    }

    @Test
    @DisplayName("a record is fresh only for the tick it was taken on")
    void freshOnlyForItsTick() {
        KinematicPoseTarget target = new KinematicPoseTarget();
        target.record(new Vector3d(), new Vector3d(), 1000L);
        assertTrue(target.isFreshFor(1000L));
        assertFalse(target.isFreshFor(1001L));
        assertFalse(target.isFreshFor(999L));
    }

    @Test
    @DisplayName("substeps advance the teleported pose by velocity times their share of the tick")
    void predictsPerSubstep() {
        KinematicPoseTarget target = new KinematicPoseTarget();
        target.record(new Vector3d(494_086.25, 78.0, 3.5), new Vector3d(2.0, 0.0, 0.0), 7L);
        Vector3d out = new Vector3d();
        assertEquals(494_086.30, target.predict(0.5, out).x, EPS);
        assertEquals(494_086.35, target.predict(1.0, out).x, EPS);
        assertEquals(78.0, out.y, EPS);
        assertEquals(3.5, out.z, EPS);
    }

    @Test
    @DisplayName("past the end of the substep loop the prediction holds at one full tick")
    void clampsPastOneTick() {
        KinematicPoseTarget target = new KinematicPoseTarget();
        target.record(new Vector3d(100.0, 0.0, 0.0), new Vector3d(8.0, 0.0, 0.0), 7L);
        Vector3d out = new Vector3d();
        assertEquals(100.4, target.predict(1.5, out).x, EPS);
        assertEquals(100.0, target.predict(-1.0, out).x, EPS);
    }

    @Test
    @DisplayName("predicting writes only the destination, never the record")
    void predictDoesNotMutateRecord() {
        KinematicPoseTarget target = new KinematicPoseTarget();
        target.record(new Vector3d(10.0, 0.0, 0.0), new Vector3d(20.0, 0.0, 0.0), 7L);
        target.predict(1.0, new Vector3d());
        assertEquals(11.0, target.predict(1.0, new Vector3d()).x, EPS);
    }
}
