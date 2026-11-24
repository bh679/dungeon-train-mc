package games.brennan.dungeontrain.combat;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link MobDigGoal#blocksLane}, {@link MobDigGoal#isLowProfile} and
 * {@link MobDigGoal#isStepUp}: what a digging mob counts as in its way, so it walks through doors and
 * over carpet instead of mining them.
 */
final class MobDigGoalPassableTest {

    private static final double WIDTH = 0.6;
    private static final double DOOR = 3.0 / 16.0;
    /** A door panel against the west side of its cell (x = 0..3/16), spanning north-south. */
    private static final VoxelShape WEST_PANEL = Shapes.box(0, 0, 0, DOOR, 1, 1);

    @Test
    @DisplayName("a door panel across the way blocks a mob walking east")
    void closedDoorAcross() {
        assertTrue(MobDigGoal.blocksLane(WEST_PANEL, Direction.Axis.X, WIDTH));
    }

    @Test
    @DisplayName("the same panel beside the way (open door, or closed door passed side-on) does not")
    void doorAlongside() {
        assertFalse(MobDigGoal.blocksLane(WEST_PANEL, Direction.Axis.Z, WIDTH));
    }

    @Test
    @DisplayName("a panel on the far side, across the way, still blocks")
    void panelFarSide() {
        assertTrue(MobDigGoal.blocksLane(Shapes.box(1 - DOOR, 0, 0, 1, 1, 1), Direction.Axis.X, WIDTH));
    }

    @Test
    @DisplayName("a full block blocks either way; empty never does")
    void fullAndEmpty() {
        assertTrue(MobDigGoal.blocksLane(Shapes.block(), Direction.Axis.X, WIDTH));
        assertTrue(MobDigGoal.blocksLane(Shapes.block(), Direction.Axis.Z, WIDTH));
        assertFalse(MobDigGoal.blocksLane(Shapes.empty(), Direction.Axis.X, WIDTH));
    }

    @Test
    @DisplayName("moving vertically, any collision blocks")
    void vertical() {
        assertTrue(MobDigGoal.blocksLane(WEST_PANEL, null, WIDTH));
    }

    @Test
    @DisplayName("carpet and thin snow are coverings; a bottom slab is not")
    void lowProfile() {
        assertTrue(MobDigGoal.isLowProfile(1.0 / 16.0));
        assertTrue(MobDigGoal.isLowProfile(2.0 / 16.0));
        assertFalse(MobDigGoal.isLowProfile(0.5));
        assertFalse(MobDigGoal.isLowProfile(1.0));
    }

    @Test
    @DisplayName("a carpeted block with room above is a step up, not a wall")
    void carpetedStep() {
        assertTrue(MobDigGoal.isStepUp(65.0625, 64.0, true, true));
    }

    @Test
    @DisplayName("a two-high wall, a step with no headroom, and a slab on a block are not steps")
    void notSteps() {
        assertFalse(MobDigGoal.isStepUp(65.0, 64.0, false, true));
        assertFalse(MobDigGoal.isStepUp(65.0, 64.0, true, false));
        assertFalse(MobDigGoal.isStepUp(65.5, 64.0, true, true));
    }

    @Test
    @DisplayName("carpet in a 2-high doorway stops a 1.95-tall mob, so it is mined; a 1.8-tall one fits")
    void carpetInLowDoorway() {
        double floor = 64.0, carpetTop = 64.0625, lintel = 66.0;
        assertTrue(MobDigGoal.carpetIsInTheWay(carpetTop, floor, 1.95, lintel));
        assertFalse(MobDigGoal.carpetIsInTheWay(carpetTop, floor, 1.8, lintel));
    }

    @Test
    @DisplayName("a carpeted step under a lintel 3 above the floor: fits at 1.8, carpet in the way at 1.95")
    void carpetedStepUnderLintel() {
        double step = 65.0, carpetTop = 65.0625, lintel = 67.0;
        assertTrue(MobDigGoal.fits(carpetTop, 1.8, lintel));
        assertFalse(MobDigGoal.fits(carpetTop, 1.95, lintel));
        assertTrue(MobDigGoal.carpetIsInTheWay(carpetTop, step, 1.95, lintel));
    }

    @Test
    @DisplayName("no ceiling, or one too low even on the bare block: carpet is not what is in the way")
    void carpetNotTheProblem() {
        assertFalse(MobDigGoal.carpetIsInTheWay(64.0625, 64.0, 1.95, Double.POSITIVE_INFINITY));
        assertFalse(MobDigGoal.carpetIsInTheWay(64.0625, 64.0, 1.95, 65.5));
    }

    @Test
    @DisplayName("a path ending in the target's column, within a block of height, reaches it")
    void pathEnd() {
        assertTrue(MobDigGoal.endsAtTarget(3, 64, 7, 3, 64, 7));
        assertTrue(MobDigGoal.endsAtTarget(3, 65, 7, 3, 64, 7));
        assertTrue(MobDigGoal.endsAtTarget(3, 63, 7, 3, 64, 7));
        assertFalse(MobDigGoal.endsAtTarget(4, 64, 7, 3, 64, 7));
        assertFalse(MobDigGoal.endsAtTarget(3, 66, 7, 3, 64, 7));
    }
}
