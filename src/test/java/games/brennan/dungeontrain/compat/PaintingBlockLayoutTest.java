package games.brennan.dungeontrain.compat;

import games.brennan.dungeontrain.compat.PaintingBlockLayout.Cell;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link PaintingBlockLayout#relayout} — the geometry that re-hangs a Fast Paintings block painting
 * after a mirrored / rotated / vertically flipped stamp. Cells go in with their post-transform
 * positions and authored states, exactly as {@code finalizeProcessing} sees them.
 *
 * <p>Ground truth is the mod's own {@code getMasterPos}:
 * {@code master = cell.above(y_offset).relative(facing.getClockWise(), x_offset)}.</p>
 */
final class PaintingBlockLayoutTest {

    private static final Object DATA = "variant";

    private static Cell cell(int x, int y, int z, Direction facing, int xo, int yo, boolean master) {
        return new Cell(new BlockPos(x, y, z), facing, xo, yo, master ? DATA : null);
    }

    /** Every cell must resolve to one master that is itself a cell with offsets 0/0 and the data. */
    private static void assertConsistent(List<Cell> cells) {
        for (Cell c : cells) {
            BlockPos master = c.pos().above(c.yOffset()).relative(c.facing().getClockWise(), c.xOffset());
            Cell m = cells.stream().filter(o -> o.pos().equals(master)).findFirst().orElseThrow(
                () -> new AssertionError(c + " points at " + master + " which is not a cell"));
            assertEquals(0, m.xOffset(), "master x_offset");
            assertEquals(0, m.yOffset(), "master y_offset");
            assertSame(DATA, m.data(), "master holds the block entity");
            if (m != c) assertEquals(null, c.data(), "slave carries no block entity: " + c);
        }
    }

    @Test
    @DisplayName("no transform: the list is returned untouched")
    void identity() {
        List<Cell> in = List.of(cell(0, 0, 0, Direction.SOUTH, 0, 0, true), cell(1, 0, 0, Direction.SOUTH, 1, 0, false));
        assertSame(in, PaintingBlockLayout.relayout(in, Mirror.NONE, Rotation.NONE, false));
    }

    @Test
    @DisplayName("Z mirror (the default contents flip): a 2×1 turns to face the other wall and its master swaps ends")
    void zMirrorTwoWide() {
        // Authored facing south, master at x=0, slave at x+1 (CCW of south = east). The Z mirror moved
        // both to z=5 without touching x.
        List<Cell> out = PaintingBlockLayout.relayout(List.of(
            cell(0, 0, 5, Direction.SOUTH, 0, 0, true),
            cell(1, 0, 5, Direction.SOUTH, 1, 0, false)), Mirror.LEFT_RIGHT, Rotation.NONE, false);

        assertEquals(Direction.NORTH, out.get(0).facing());
        assertEquals(Direction.NORTH, out.get(1).facing());
        // Facing north the run goes west, so the master is now the x=1 cell.
        assertEquals(cell(1, 0, 5, Direction.NORTH, 0, 0, true), out.get(1));
        assertEquals(cell(0, 0, 5, Direction.NORTH, 1, 0, false), out.get(0));
        assertConsistent(out);
    }

    @Test
    @DisplayName("Z mirror on an east-facing 2×2: facing survives, the run along z reverses")
    void zMirrorEastFacing() {
        // Authored: master (5,1,3), run north → (5,1,2); below them (5,0,3),(5,0,2). Mirrored z → -z.
        List<Cell> out = PaintingBlockLayout.relayout(List.of(
            cell(5, 1, -3, Direction.EAST, 0, 0, true),
            cell(5, 1, -2, Direction.EAST, 1, 0, false),
            cell(5, 0, -3, Direction.EAST, 0, 1, false),
            cell(5, 0, -2, Direction.EAST, 1, 1, false)), Mirror.LEFT_RIGHT, Rotation.NONE, false);

        out.forEach(c -> assertEquals(Direction.EAST, c.facing()));
        assertEquals(cell(5, 1, -2, Direction.EAST, 0, 0, true), out.get(1));
        assertEquals(cell(5, 1, -3, Direction.EAST, 1, 0, false), out.get(0));
        assertEquals(cell(5, 0, -2, Direction.EAST, 0, 1, false), out.get(3));
        assertEquals(cell(5, 0, -3, Direction.EAST, 1, 1, false), out.get(2));
        assertConsistent(out);
    }

    @Test
    @DisplayName("X mirror on a south-facing 2×1: facing survives, master swaps ends")
    void xMirror() {
        // Authored master x=0, slave x=1; FRONT_BACK maps x → -x.
        List<Cell> out = PaintingBlockLayout.relayout(List.of(
            cell(0, 0, 0, Direction.SOUTH, 0, 0, true),
            cell(-1, 0, 0, Direction.SOUTH, 1, 0, false)), Mirror.FRONT_BACK, Rotation.NONE, false);

        assertEquals(cell(-1, 0, 0, Direction.SOUTH, 0, 0, true), out.get(1));
        assertEquals(cell(0, 0, 0, Direction.SOUTH, 1, 0, false), out.get(0));
        assertConsistent(out);
    }

    @Test
    @DisplayName("180° rotation (X+Z flip): facing reverses, handedness is kept so the master stays put")
    void rotate180() {
        List<Cell> out = PaintingBlockLayout.relayout(List.of(
            cell(0, 0, 0, Direction.SOUTH, 0, 0, true),
            cell(-1, 0, 0, Direction.SOUTH, 1, 0, false)), Mirror.NONE, Rotation.CLOCKWISE_180, false);

        assertEquals(cell(0, 0, 0, Direction.NORTH, 0, 0, true), out.get(0));
        assertEquals(cell(-1, 0, 0, Direction.NORTH, 1, 0, false), out.get(1));
        assertConsistent(out);
    }

    @Test
    @DisplayName("90° rotation: facing and run rotate together")
    void rotate90() {
        // (x,z) → (-z,x): master (0,0,0) → (0,0,0), slave (1,0,0) → (0,0,1).
        List<Cell> out = PaintingBlockLayout.relayout(List.of(
            cell(0, 0, 0, Direction.SOUTH, 0, 0, true),
            cell(0, 0, 1, Direction.SOUTH, 1, 0, false)), Mirror.NONE, Rotation.CLOCKWISE_90, false);

        assertEquals(cell(0, 0, 0, Direction.WEST, 0, 0, true), out.get(0));
        assertEquals(cell(0, 0, 1, Direction.WEST, 1, 0, false), out.get(1));
        assertConsistent(out);
    }

    @Test
    @DisplayName("vertical flip: the master moves back to the top of the column")
    void verticalFlip() {
        // Authored master (0,1,0) over slave (0,0,0); the y flip swapped their rows.
        List<Cell> out = PaintingBlockLayout.relayout(List.of(
            cell(0, 0, 0, Direction.SOUTH, 0, 0, true),
            cell(0, 1, 0, Direction.SOUTH, 0, 1, false)), Mirror.NONE, Rotation.NONE, true);

        assertEquals(cell(0, 1, 0, Direction.SOUTH, 0, 0, true), out.get(1));
        assertEquals(cell(0, 0, 0, Direction.SOUTH, 0, 1, false), out.get(0));
        assertConsistent(out);
    }

    @Test
    @DisplayName("two paintings side by side stay two paintings")
    void twoPaintings() {
        List<Cell> out = PaintingBlockLayout.relayout(List.of(
            cell(0, 0, 0, Direction.SOUTH, 0, 0, true),
            cell(1, 0, 0, Direction.SOUTH, 1, 0, false),
            cell(2, 0, 0, Direction.SOUTH, 0, 0, true),
            cell(3, 0, 0, Direction.SOUTH, 1, 0, false)), Mirror.LEFT_RIGHT, Rotation.NONE, false);

        assertEquals(2, out.stream().filter(Cell::master).count());
        assertConsistent(out);
    }
}
