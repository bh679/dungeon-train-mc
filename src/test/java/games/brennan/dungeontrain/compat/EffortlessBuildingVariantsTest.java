package games.brennan.dungeontrain.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** The surface-shift rule that moves an Effortless Building shape back onto the blocks it was drawn against. */
final class EffortlessBuildingVariantsTest {

    @Test
    @DisplayName("start cell is air: whole shape moves one step opposite the clicked face")
    void airStartShiftsThroughFace() {
        List<BlockPos> floor = List.of(new BlockPos(0, 5, 0), new BlockPos(1, 5, 0), new BlockPos(1, 5, 1));
        List<BlockPos> shifted = EffortlessBuildingVariants.surfaceShift(floor, true, Direction.UP);
        assertEquals(List.of(new BlockPos(0, 4, 0), new BlockPos(1, 4, 0), new BlockPos(1, 4, 1)), shifted);
    }

    @Test
    @DisplayName("wall drawn against a north face lands on the wall blocks")
    void wallShiftsHorizontally() {
        List<BlockPos> wall = List.of(new BlockPos(3, 1, 2), new BlockPos(3, 2, 2));
        List<BlockPos> shifted = EffortlessBuildingVariants.surfaceShift(wall, true, Direction.NORTH);
        assertEquals(List.of(new BlockPos(3, 1, 3), new BlockPos(3, 2, 3)), shifted);
    }

    @Test
    @DisplayName("start cell is a block (quick-replace): nothing moves")
    void solidStartIsUnchanged() {
        List<BlockPos> cells = List.of(new BlockPos(0, 4, 0));
        assertSame(cells, EffortlessBuildingVariants.surfaceShift(cells, false, Direction.UP));
    }
}
