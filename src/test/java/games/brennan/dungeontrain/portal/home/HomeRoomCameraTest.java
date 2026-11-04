package games.brennan.dungeontrain.portal.home;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HomeRoomCameraTest {

    /** A 3 wide x 2 tall screen with its bottom-left at {@code origin}, laid along {@code across}. */
    private static List<BlockPos> screen(BlockPos origin, Direction across) {
        return List.of(origin, origin.relative(across), origin.relative(across, 2),
            origin.above(), origin.above().relative(across), origin.above().relative(across, 2));
    }

    @Test
    void southFacingScreenStandsTheViewerSouthLookingNorth() {
        // Screen on the north wall at z=1, x 4..6, y 2..3; floor surface at y=1.
        HomeRoomCamera.Pose pose = HomeRoomCamera.facing(screen(new BlockPos(4, 2, 1), Direction.EAST), Direction.SOUTH, 1);
        assertEquals(5.5, pose.x(), 1e-9);
        assertEquals(1, pose.y(), 1e-9);
        assertEquals(1.5 + 0.5 + HomeRoomCamera.STAND_OFF, pose.z(), 1e-9);
        assertEquals(0f, net.minecraft.util.Mth.wrapDegrees(180f - pose.yaw()), 1e-4, "looking north");
        // Eye at y=2.62 and the screen's centre at y=3: looking slightly up.
        assertTrue(pose.pitch() < 0, "pitch up is negative");
        assertTrue(pose.pitch() > -20);
    }

    @Test
    void everyFacingLooksBackAtTheScreen() {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            Direction across = facing.getClockWise();
            HomeRoomCamera.Pose pose = HomeRoomCamera.facing(screen(new BlockPos(10, 5, 10), across), facing, 4);
            assertEquals(0f, net.minecraft.util.Mth.wrapDegrees(facing.getOpposite().toYRot() - pose.yaw()), 1e-4, facing.toString());
            // The stand point is out from the screen along its facing.
            double centreX = 10 + 0.5 + across.getStepX();
            double centreZ = 10 + 0.5 + across.getStepZ();
            assertEquals(centreX + facing.getStepX() * (0.5 + HomeRoomCamera.STAND_OFF), pose.x(), 1e-9);
            assertEquals(centreZ + facing.getStepZ() * (0.5 + HomeRoomCamera.STAND_OFF), pose.z(), 1e-9);
        }
    }

    @Test
    void refusesAnEmptyScreen() {
        assertThrows(IllegalArgumentException.class, () -> HomeRoomCamera.facing(List.of(), Direction.NORTH, 0));
    }
}
