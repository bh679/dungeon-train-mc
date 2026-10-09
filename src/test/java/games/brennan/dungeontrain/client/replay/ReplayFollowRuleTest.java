package games.brennan.dungeontrain.client.replay;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ReplayFollowRuleTest {

    @Test
    void carriageEntryIsAJump() {
        // ~100 blocks down into the twin, a little sideways.
        assertTrue(ReplayFollowRule.isCarriageJump(new Vec3(300, 80, 20), new Vec3(302, -22, 21)));
    }

    @Test
    void carriageExitIsAJumpToo() {
        assertTrue(ReplayFollowRule.isCarriageJump(new Vec3(302, -22, 21), new Vec3(340, 80, 20)));
    }

    @Test
    void upsideDownAtticCountsAsWell() {
        assertTrue(ReplayFollowRule.isCarriageJump(new Vec3(0, 60, 0), new Vec3(0, 180, 0)));
    }

    @Test
    void ridingTheTrainIsNotAJump() {
        assertFalse(ReplayFollowRule.isCarriageJump(new Vec3(300, 80, 20), new Vec3(302.1, 80, 20)));
    }

    @Test
    void longHorizontalMoveWithoutHeightChangeIsNotAJump() {
        assertFalse(ReplayFollowRule.isCarriageJump(new Vec3(0, 80, 0), new Vec3(60, 81, 0)));
    }

    @Test
    void fallingOffTheTrainIsNotAJump() {
        // One tick of free fall is a few blocks; far below the swap threshold.
        assertFalse(ReplayFollowRule.isCarriageJump(new Vec3(0, 80, 0), new Vec3(0, 76, 0)));
    }

    @Test
    void nullsAreNotAJump() {
        assertFalse(ReplayFollowRule.isCarriageJump(null, new Vec3(0, 0, 0)));
        assertFalse(ReplayFollowRule.isCarriageJump(new Vec3(0, 0, 0), null));
    }
}
