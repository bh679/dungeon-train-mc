package games.brennan.dungeontrain.ship.sable;

import dev.ryanhcode.sable.sublevel.entity_collision.SubLevelEntityCollision.CollisionInfo;
import dev.ryanhcode.sable.sublevel.entity_collision.SubLevelEntityCollision.FirstCollisionInfo;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The "is this collision really the player pushing into the ladder?" rule. Pure, so it runs
 * without a Minecraft/Sable bootstrap; the mixin that consumes it is verified in-game.
 */
class SableClimbPushTest {

    /** A wall ahead of the entity on +X: its push on the entity points toward -X. */
    private static final Vector3d PUSH_FROM_PLUS_X = new Vector3d(-1, 0, 0);

    private static CollisionInfo shipPush(Vec3 ownMotion, Vector3d pushDirection) {
        CollisionInfo info = new CollisionInfo();
        info.subLevelHorizontalCollision = true;
        info.horizontalCollision = false;
        info.preDeltaMovement = ownMotion;
        info.firstCollisions = Collections.singletonMap(null,
                new FirstCollisionInfo(new Vector3d(), pushDirection, true, false, null));
        return info;
    }

    @Test
    @DisplayName("No collision is never climb input")
    void noCollision() {
        assertFalse(SableClimbPush.countsAsPush(false, null));
        assertFalse(SableClimbPush.countsAsPush(false, shipPush(new Vec3(0.1, 0, 0), PUSH_FROM_PLUS_X)));
    }

    @Test
    @DisplayName("Without Sable info the vanilla flag passes through")
    void noInfoPassesThrough() {
        assertTrue(SableClimbPush.countsAsPush(true, null));
    }

    @Test
    @DisplayName("A world-block clip is vanilla's own collision and still climbs")
    void worldClipClimbs() {
        CollisionInfo info = new CollisionInfo();
        info.horizontalCollision = true;
        assertTrue(SableClimbPush.countsAsPush(true, info));
        info.subLevelHorizontalCollision = true;
        assertTrue(SableClimbPush.countsAsPush(true, info));
    }

    @Test
    @DisplayName("A moving carriage pushing a still rider is not climb input")
    void shipPushOnStillRiderDoesNotClimb() {
        assertFalse(SableClimbPush.countsAsPush(true, shipPush(Vec3.ZERO, PUSH_FROM_PLUS_X)));
        assertFalse(SableClimbPush.countsAsPush(true, shipPush(new Vec3(0.001, -0.15, 0), PUSH_FROM_PLUS_X)));
    }

    @Test
    @DisplayName("Walking into the ladder on a moving carriage climbs")
    void ownMotionIntoWallClimbs() {
        assertTrue(SableClimbPush.countsAsPush(true, shipPush(new Vec3(0.1, 0, 0), PUSH_FROM_PLUS_X)));
    }

    @Test
    @DisplayName("Walking along or away from the ladder wall does not climb")
    void motionAlongOrAwayDoesNotClimb() {
        assertFalse(SableClimbPush.countsAsPush(true, shipPush(new Vec3(0, 0, 0.1), PUSH_FROM_PLUS_X)));
        assertFalse(SableClimbPush.countsAsPush(true, shipPush(new Vec3(-0.1, 0, 0), PUSH_FROM_PLUS_X)));
    }

    @Test
    @DisplayName("A push with no recorded direction is not climb input")
    void pushWithoutDirectionDoesNotClimb() {
        CollisionInfo info = new CollisionInfo();
        info.subLevelHorizontalCollision = true;
        info.preDeltaMovement = new Vec3(0.1, 0, 0);
        assertFalse(SableClimbPush.countsAsPush(true, info));
    }
}
