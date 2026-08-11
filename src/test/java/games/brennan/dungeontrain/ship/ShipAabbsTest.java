package games.brennan.dungeontrain.ship;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link ShipAabbs} — the sanity test applied to a ship's world AABB before a
 * caller derives a world position from it.
 *
 * <p>Exercises the loose-bounds overload, which carries the whole rule; the {@code AABBdc} one is a
 * null check and six accessor calls, and JOML is not on the test classpath. No NeoForge
 * bootstrap.</p>
 */
final class ShipAabbsTest {

    @Test
    @DisplayName("an ordinary carriage-sized box is usable")
    void realBoxIsNotDegenerate() {
        // Roughly a real carriage group: 9 long, 6 tall, 7 wide, out along the track.
        assertFalse(ShipAabbs.isDegenerate(581.0, 77.0, 0.0, 590.0, 83.0, 7.0));
    }

    @Test
    @DisplayName("the all-zero box Sable returns before a sub-level ticks is rejected")
    void allZeroIsDegenerate() {
        assertTrue(ShipAabbs.isDegenerate(0, 0, 0, 0, 0, 0));
    }

    @Test
    @DisplayName("a zero-sized box away from the origin is rejected too — extent, not coordinates")
    void zeroSizedAwayFromOriginIsDegenerate() {
        assertTrue(ShipAabbs.isDegenerate(581.0, 77.0, 0.0, 581.0, 77.0, 0.0));
    }

    @Test
    @DisplayName("a single flat axis is enough to reject: a carriage is solid on all three")
    void anyFlatAxisIsDegenerate() {
        assertTrue(ShipAabbs.isDegenerate(581.0, 77.0, 0.0, 590.0, 77.0, 7.0), "flat on Y");
        assertTrue(ShipAabbs.isDegenerate(581.0, 77.0, 0.0, 581.0, 83.0, 7.0), "flat on X");
        assertTrue(ShipAabbs.isDegenerate(581.0, 77.0, 0.0, 590.0, 83.0, 0.0), "flat on Z");
    }

    @Test
    @DisplayName("a box thinner than the epsilon counts as flat, so float noise cannot sneak one past")
    void subEpsilonExtentIsDegenerate() {
        assertTrue(ShipAabbs.isDegenerate(581.0, 77.0, 0.0, 590.0, 77.0 + 1e-12, 7.0));
    }

    @Test
    @DisplayName("a legitimately thin but real box is kept — the epsilon is not a size policy")
    void millimetreExtentIsNotDegenerate() {
        assertFalse(ShipAabbs.isDegenerate(581.0, 77.0, 0.0, 590.0, 77.001, 7.0));
    }

    @Test
    @DisplayName("a negative-extent box is rejected on magnitude, not sign")
    void invertedBoundsAreJudgedByMagnitude() {
        assertFalse(ShipAabbs.isDegenerate(590.0, 83.0, 7.0, 581.0, 77.0, 0.0));
    }
}
