package games.brennan.dungeontrain.compat.photo;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * The pure geometry of a block photo frame ({@link games.brennan.dungeontrain.block.PhotographFrameBlock}).
 *
 * <p>A frame of size {@code s} (Exposure's 0..2) is a square of {@code s + 1} cells on a wall. It uses
 * Fast Paintings' master/slave convention so {@link games.brennan.dungeontrain.compat.PaintingBlockLayout}
 * re-lays it under a template transform unchanged:</p>
 *
 * <pre>master = cell.above(y_offset).relative(facing.getClockWise(), x_offset)</pre>
 *
 * <p>The master is the top cell at the clockwise end; the frame runs counter-clockwise and down from
 * it. {@code facing} is the way the picture faces — the wall is behind, at {@code facing.getOpposite()}.
 * Exposure's own entity grows up and counter-clockwise from the block the player clicked, so that
 * anchor is the bottom cell at the clockwise end: {@link #masterForAnchor}.</p>
 */
public final class PhotoFrameLayout {

    /** Exposure's largest frame size (3×3). */
    public static final int MAX_SIZE = 2;

    private PhotoFrameLayout() {}

    /** Cells along one edge for an Exposure frame size (0..2). */
    public static int width(int size) {
        return clampSize(size) + 1;
    }

    /** {@code size} clamped to Exposure's 0..2. */
    public static int clampSize(int size) {
        return Math.max(0, Math.min(MAX_SIZE, size));
    }

    /** The master cell of a frame whose bottom clockwise-end cell is {@code anchor}. */
    public static BlockPos masterForAnchor(BlockPos anchor, int size) {
        return anchor.above(width(size) - 1);
    }

    /** The master a cell points at through its offsets. */
    public static BlockPos masterOf(BlockPos cell, Direction facing, int xOffset, int yOffset) {
        return cell.above(yOffset).relative(facing.getClockWise(), xOffset);
    }

    /** The cell at offsets ({@code x}, {@code y}) from {@code master}. */
    public static BlockPos cellAt(BlockPos master, Direction facing, int xOffset, int yOffset) {
        return master.below(yOffset).relative(facing.getCounterClockWise(), xOffset);
    }

    /** One cell of a frame: where it sits and the offsets back to its master. */
    public record Cell(BlockPos pos, int xOffset, int yOffset) {}

    /** Every cell of a frame, master first. */
    public static List<Cell> cells(BlockPos master, Direction facing, int size) {
        int w = width(size);
        List<Cell> out = new ArrayList<>(w * w);
        for (int y = 0; y < w; y++) {
            for (int x = 0; x < w; x++) {
                out.add(new Cell(cellAt(master, facing, x, y), x, y));
            }
        }
        return List.copyOf(out);
    }

    /** The wall cell a frame cell hangs on. */
    public static BlockPos wallBehind(BlockPos cell, Direction facing) {
        return cell.relative(facing.getOpposite());
    }
}
