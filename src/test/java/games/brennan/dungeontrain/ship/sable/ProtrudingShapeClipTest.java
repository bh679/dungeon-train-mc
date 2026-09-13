package games.brennan.dungeontrain.ship.sable;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtrudingShapeClipTest {

    private static final VoxelShape FENCE_POST = Shapes.box(0.375, 0.0, 0.375, 0.625, 1.5, 0.625);
    private static final VoxelShape BOTTOM_TRAPDOOR = Shapes.box(0.0, 0.0, 0.0, 1.0, 0.1875, 1.0);
    private static final VoxelShape CHAIN = Shapes.box(0.4375, 0.0, 0.4375, 0.5625, 1.0, 0.5625);

    @Test
    void fencePostUnderTrapdoorIsCutAtCubeTop() {
        VoxelShape clipped = ProtrudingShapeClip.clipAgainst(FENCE_POST, BOTTOM_TRAPDOOR);
        assertEquals(1.0, clipped.max(Direction.Axis.Y), 1e-9);
        assertEquals(0.0, clipped.min(Direction.Axis.Y), 1e-9);
        assertEquals(0.375, clipped.min(Direction.Axis.X), 1e-9);
        assertEquals(0.625, clipped.max(Direction.Axis.X), 1e-9);
    }

    @Test
    void nothingAboveLeavesShapeUntouched() {
        assertSame(FENCE_POST, ProtrudingShapeClip.clipAgainst(FENCE_POST, Shapes.empty()));
    }

    @Test
    void narrowBlockAboveDoesNotCoverFootprint() {
        assertSame(FENCE_POST, ProtrudingShapeClip.clipAgainst(FENCE_POST, CHAIN));
    }

    @Test
    void fenceArmSurvivesWhilePostIsClipped() {
        VoxelShape arm = Shapes.box(0.625, 0.0, 0.4375, 1.0, 1.0, 0.5625);
        VoxelShape fence = Shapes.or(FENCE_POST, arm);
        VoxelShape clipped = ProtrudingShapeClip.clipAgainst(fence, BOTTOM_TRAPDOOR);
        assertEquals(1.0, clipped.max(Direction.Axis.Y), 1e-9);
        List<AABB> boxes = clipped.toAabbs();
        assertTrue(boxes.stream().anyMatch(b -> Math.abs(b.maxX - 1.0) < 1e-9), "arm reaching x=1.0 kept: " + boxes);
        assertTrue(boxes.stream().allMatch(b -> b.maxY <= 1.0 + 1e-9), "no box above cube top: " + boxes);
    }

    @Test
    void fullCubeIsUntouched() {
        assertSame(Shapes.block(), ProtrudingShapeClip.clipAgainst(Shapes.block(), BOTTOM_TRAPDOOR));
    }

    @Test
    void boxEntirelyAboveCubeIsDropped() {
        VoxelShape floating = Shapes.or(Shapes.box(0.0, 0.0, 0.0, 1.0, 1.0, 1.0), Shapes.box(0.25, 1.0, 0.25, 0.75, 1.5, 0.75));
        VoxelShape clipped = ProtrudingShapeClip.clipAgainst(floating, BOTTOM_TRAPDOOR);
        assertEquals(1.0, clipped.max(Direction.Axis.Y), 1e-9);
        assertEquals(1, clipped.toAabbs().size());
    }
}
