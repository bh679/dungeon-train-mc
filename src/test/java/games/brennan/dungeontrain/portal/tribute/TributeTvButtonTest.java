package games.brennan.dungeontrain.portal.tribute;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TributeTvButtonTest {

    @Test
    void bundledRoomButtonSitsInFrontOfTheMiddleStand() {
        // tribute.nbt: the TV is x 3..7, y 2..4, z 1, facing south; the stand is the stair row at y 1.
        BoundingBox tv = new BoundingBox(3, 2, 1, 7, 4, 1);
        assertEquals(new BlockPos(5, 1, 2), TributeTvButton.buttonCell(tv, Direction.SOUTH));
    }

    @Test
    void everyFacingPutsTheButtonUnderTheMiddleAndInFront() {
        BoundingBox alongX = new BoundingBox(10, 5, 20, 14, 7, 20);
        assertEquals(new BlockPos(12, 4, 19), TributeTvButton.buttonCell(alongX, Direction.NORTH));
        assertEquals(new BlockPos(12, 4, 21), TributeTvButton.buttonCell(alongX, Direction.SOUTH));
        BoundingBox alongZ = new BoundingBox(30, 5, 0, 30, 7, 4);
        assertEquals(new BlockPos(31, 4, 2), TributeTvButton.buttonCell(alongZ, Direction.EAST));
        assertEquals(new BlockPos(29, 4, 2), TributeTvButton.buttonCell(alongZ, Direction.WEST));
    }

    @Test
    void verticalFacingHasNoButton() {
        assertNull(TributeTvButton.buttonCell(new BoundingBox(0, 0, 0, 2, 1, 0), Direction.UP));
    }

    @Test
    void theButtonFindsItsOwnTvAgain() {
        BoundingBox tv = new BoundingBox(3, 2, 1, 7, 4, 1);
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            BlockPos cell = TributeTvButton.buttonCell(tv, facing);
            assertNotNull(cell);
            if (facing.getAxis() == Direction.Axis.X) continue; // a 1-deep TV along X faces along Z in practice
            assertTrue(tv.isInside(TributeTvButton.tvAbove(cell, facing)), facing + " -> " + cell);
        }
        BoundingBox side = new BoundingBox(30, 5, 0, 30, 7, 4);
        for (Direction facing : new Direction[] {Direction.EAST, Direction.WEST}) {
            assertTrue(side.isInside(TributeTvButton.tvAbove(TributeTvButton.buttonCell(side, facing), facing)), facing.toString());
        }
    }
}
