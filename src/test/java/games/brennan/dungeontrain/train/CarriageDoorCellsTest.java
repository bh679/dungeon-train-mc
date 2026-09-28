package games.brennan.dungeontrain.train;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CarriageDoorCellsTest {

    private static final CarriageDims DEFAULT_DIMS = CarriageDims.DEFAULT;   // 9 × 7 × 7

    @Test
    @DisplayName("One base per end, entrance (-X) first, on the doorway centre line a block up")
    void doorBases_areOneCellAtEachEnd() {
        BlockPos origin = new BlockPos(100, 230, 40);

        List<BlockPos> bases = CarriageDoorCells.doorBases(origin, DEFAULT_DIMS);

        assertEquals(CarriageDoorCells.DOORS_PER_CARRIAGE, bases.size());
        // Z = 40 + 7 / 2 = 43; Y = the row above the floor; X = the two end caps, 0 and length - 1.
        assertEquals(List.of(
            new BlockPos(100, 231, 43),
            new BlockPos(108, 231, 43)), bases);
    }

    @Test
    @DisplayName("The Z line is CarriagePlacer.stateAt's doorZ for every legal width")
    void doorBases_sitOnTheDoorGapColumn() {
        BlockPos origin = new BlockPos(0, 64, 0);
        for (int width = CarriageDims.MIN_WIDTH; width <= CarriageDims.MAX_WIDTH; width++) {
            CarriageDims box = new CarriageDims(DEFAULT_DIMS.length(), width, DEFAULT_DIMS.height());
            // doorZ in CarriagePlacer.placeAt, which stateAt opens the door gap on.
            int doorZ = width / 2;
            for (BlockPos base : CarriageDoorCells.doorBases(origin, box)) {
                assertEquals(doorZ, base.getZ(), "width " + width);
                // stateAt's gap is dy == 1 || dy == 2, and dy == 0 is the floor row.
                assertEquals(65, base.getY(), "width " + width);
            }
        }
    }

    @Test
    @DisplayName("A longer plot puts its exit on the plot's own far end, not the world dims'")
    void doorBases_followThePlotsOwnBox() {
        BlockPos origin = new BlockPos(0, 64, 0);
        CarriageDims longer = new CarriageDims(DEFAULT_DIMS.length() + 4, DEFAULT_DIMS.width(),
            DEFAULT_DIMS.height());

        List<BlockPos> bases = CarriageDoorCells.doorBases(origin, longer);

        assertEquals(0, bases.get(0).getX());
        assertEquals(longer.length() - 1, bases.get(1).getX());
    }

    @Test
    @DisplayName("A missing origin or box yields nothing rather than throwing")
    void doorBases_areEmptyWithoutABox() {
        assertTrue(CarriageDoorCells.doorBases(null, DEFAULT_DIMS).isEmpty());
        assertTrue(CarriageDoorCells.doorBases(new BlockPos(0, 64, 0), null).isEmpty());
    }
}
