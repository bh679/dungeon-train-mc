package games.brennan.dungeontrain.portal;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The rule a portal swap applies to a traveller's velocity when it takes them off the train.
 *
 * <p>The cases that matter are the two failure modes either side of the correct one: removing
 * nothing leaves a player sliding down the twin corridor at track speed, and removing too much
 * launches them backwards out of a room they only walked into.</p>
 */
class PortalTransitVelocityTest {

    /** A train doing 0.4 blocks a tick down +X, the shape of DT's constant-velocity trains. */
    private static final Vec3 TRAIN = new Vec3(0.4, 0.0, 0.0);

    /**
     * The real thing: DT's default train is 2.0 blocks per second, which is 0.1 per tick — and the
     * per-tick frame is the only one this class may be given. Passing the per-second value made the
     * clamp twenty times too generous and took a walking player's own speed away with the train's,
     * which is the bug this case is here to keep out. See {@code TrainTransformProvider.PHYSICS_DT}.
     */
    private static final Vec3 REAL_TRAIN = new Vec3(2.0 / 20.0, 0.0, 0.0);

    private static void assertVec(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x, actual.x, 1e-9, "x");
        assertEquals(expected.y, actual.y, 1e-9, "y");
        assertEquals(expected.z, actual.z, 1e-9, "z");
    }

    @Test
    @DisplayName("somebody merely standing on the train arrives at rest")
    void carriedOnly() {
        assertVec(Vec3.ZERO, PortalTransitVelocity.withoutCarrier(TRAIN, TRAIN));
    }

    @Test
    @DisplayName("sprinting the way the train is going keeps the sprint, loses the train")
    void carriedPlusInput() {
        Vec3 delta = new Vec3(0.4 + 0.28, 0.0, 0.0);
        assertVec(new Vec3(0.28, 0.0, 0.0), PortalTransitVelocity.withoutCarrier(delta, TRAIN));
    }

    @Test
    @DisplayName("running against the train is left alone — never launched backwards")
    void againstTravel() {
        Vec3 delta = new Vec3(-0.2, 0.0, 0.0);
        assertVec(delta, PortalTransitVelocity.withoutCarrier(delta, TRAIN));
    }

    @Test
    @DisplayName("a carry that never reached deltaMovement removes nothing")
    void noCarriedComponent() {
        Vec3 delta = new Vec3(0.0, -0.08, 0.15);
        assertVec(delta, PortalTransitVelocity.withoutCarrier(delta, TRAIN));
    }

    @Test
    @DisplayName("only the train's axis is touched — falling and cross-track motion survive")
    void otherAxesUntouched() {
        Vec3 delta = new Vec3(0.4, -0.31, 0.12);
        assertVec(new Vec3(0.0, -0.31, 0.12), PortalTransitVelocity.withoutCarrier(delta, TRAIN));
    }

    @Test
    @DisplayName("never removes more than the train is doing")
    void neverOverRemoves() {
        Vec3 delta = new Vec3(0.1, 0.0, 0.0);
        assertVec(new Vec3(0.0, 0.0, 0.0), PortalTransitVelocity.withoutCarrier(delta, TRAIN));
    }

    @Test
    @DisplayName("at the real train speed, a walking player keeps their own walk")
    void realTrainSpeedKeepsInput() {
        // 0.18/tick is what the dev client logged for a player walking into the corridor.
        Vec3 delta = new Vec3(0.18, 0.0, 0.0);
        assertVec(new Vec3(0.08, 0.0, 0.0),
            PortalTransitVelocity.withoutCarrier(delta, REAL_TRAIN));
    }

    @Test
    @DisplayName("a parked train changes nothing")
    void parkedCarrier() {
        Vec3 delta = new Vec3(0.21, 0.0, -0.05);
        assertSame(delta, PortalTransitVelocity.withoutCarrier(delta, Vec3.ZERO));
    }

    @Test
    @DisplayName("works on a diagonal carrier, not just the axis-aligned case")
    void diagonalCarrier() {
        Vec3 carrier = new Vec3(0.3, 0.0, 0.4);
        assertVec(Vec3.ZERO, PortalTransitVelocity.withoutCarrier(carrier, carrier));
    }
}
