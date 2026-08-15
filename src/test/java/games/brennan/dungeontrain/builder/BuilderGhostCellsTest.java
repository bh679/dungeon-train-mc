package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.train.CarriageContentsPlacer;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which cells the builder is allowed to take away from under an author.
 *
 * <p>Authoring a carriage room from inside lifts the carriage's shell out of the world so the rest
 * of the train is visible from the middle of it. That is only safe because the shell and the room
 * are disjoint sets of blocks — the room is captured from
 * {@link CarriageContentsPlacer#interiorOrigin}'s box, one in from every perimeter wall, and the
 * shell is what is left over.</p>
 *
 * <p>Pinned here rather than trusted, because the failure is silent and terrible: if the two ever
 * overlapped, erasing a wall would start erasing the blocks somebody had just placed in their room,
 * and the first anyone would know is a saved template with holes in it. The renderer cannot catch
 * that — by the time it draws, the blocks are already gone.</p>
 */
final class BuilderGhostCellsTest {

    /** Every dims worth checking: the default, a long one, and the smallest the record allows. */
    private static Stream<CarriageDims> dimensions() {
        return Stream.of(CarriageDims.DEFAULT,
                new CarriageDims(13, 9, 9),
                new CarriageDims(CarriageDims.MIN_LENGTH, CarriageDims.MIN_WIDTH,
                        CarriageDims.MIN_HEIGHT));
    }

    @Test
    @DisplayName("Nothing the shell lift takes is inside the box a carriage room is saved from")
    void liftedCellsNeverOverlapTheSavedRoom() {
        dimensions().forEach(dims -> {
            BlockPos interiorMin = CarriageContentsPlacer.interiorOrigin(BlockPos.ZERO);
            Vec3i interior = CarriageContentsPlacer.interiorSize(dims);

            for (int x = 0; x < dims.length(); x++) {
                for (int y = 0; y < dims.height(); y++) {
                    for (int z = 0; z < dims.width(); z++) {
                        if (!BuilderGhostCells.onSkin(x, y, z, dims)) {
                            continue;
                        }
                        boolean insideSavedRoom =
                                x >= interiorMin.getX() && x < interiorMin.getX() + interior.getX()
                                && y >= interiorMin.getY() && y < interiorMin.getY() + interior.getY()
                                && z >= interiorMin.getZ() && z < interiorMin.getZ() + interior.getZ();
                        assertFalse(insideSavedRoom,
                                "lifting " + x + "," + y + "," + z + " at " + dims
                                        + " would erase a block the room saves");
                    }
                }
            }
        });
    }

    @Test
    @DisplayName("Every cell a carriage room saves survives the lift")
    void theSavedRoomIsNeverLifted() {
        dimensions().forEach(dims -> {
            BlockPos interiorMin = CarriageContentsPlacer.interiorOrigin(BlockPos.ZERO);
            Vec3i interior = CarriageContentsPlacer.interiorSize(dims);

            for (int x = 0; x < interior.getX(); x++) {
                for (int y = 0; y < interior.getY(); y++) {
                    for (int z = 0; z < interior.getZ(); z++) {
                        assertFalse(BuilderGhostCells.onSkin(interiorMin.getX() + x,
                                        interiorMin.getY() + y, interiorMin.getZ() + z, dims),
                                "the room's own cell " + x + "," + y + "," + z + " at " + dims
                                        + " reads as shell");
                    }
                }
            }
        });
    }

    @Test
    @DisplayName("The deck is shell: the whole floor course goes, not just its outer ring")
    void theWholeFloorCourseIsShell() {
        CarriageDims dims = CarriageDims.DEFAULT;
        for (int x = 0; x < dims.length(); x++) {
            for (int z = 0; z < dims.width(); z++) {
                assertTrue(BuilderGhostCells.onSkin(x, 0, z, dims),
                        "floor cell " + x + "," + z + " should be shell");
            }
        }
    }

    @Test
    @DisplayName("Shell and room together account for every cell in the carriage, with no cell in both")
    void shellAndRoomPartitionTheCarriage() {
        dimensions().forEach(dims -> {
            Vec3i interior = CarriageContentsPlacer.interiorSize(dims);
            int cells = dims.length() * dims.height() * dims.width();
            int room = interior.getX() * interior.getY() * interior.getZ();

            int shell = 0;
            for (int x = 0; x < dims.length(); x++) {
                for (int y = 0; y < dims.height(); y++) {
                    for (int z = 0; z < dims.width(); z++) {
                        if (BuilderGhostCells.onSkin(x, y, z, dims)) {
                            shell++;
                        }
                    }
                }
            }
            assertEquals(cells - room, shell,
                    "shell + room should be the whole carriage exactly once at " + dims);
        });
    }

    @Test
    @DisplayName("The smallest legal carriage is all shell but for the two cells it can be a room in")
    void theSmallestCarriageIsNearlyAllShell() {
        CarriageDims smallest = new CarriageDims(CarriageDims.MIN_LENGTH, CarriageDims.MIN_WIDTH,
                CarriageDims.MIN_HEIGHT);
        assertEquals(new Vec3i(2, 1, 1), CarriageContentsPlacer.interiorSize(smallest));

        int shell = 0;
        for (int x = 0; x < smallest.length(); x++) {
            for (int y = 0; y < smallest.height(); y++) {
                for (int z = 0; z < smallest.width(); z++) {
                    if (BuilderGhostCells.onSkin(x, y, z, smallest)) {
                        shell++;
                    }
                }
            }
        }
        // 4×3×3 is 36 cells, of which the 2×1×1 interior is the only thing that isn't skin.
        assertEquals(36 - 2, shell);
    }
}
