package games.brennan.dungeontrain.client.menu.blockvariant;

import games.brennan.dungeontrain.editor.RotationApplier;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Geometry of the Z menu's rotation-options popup — the one layout the renderer draws and the
 * raycaster hit-tests, so a click can never land one button off.
 *
 * <p>Facing / axis blocks get a 3×2 grid (top row positive UP / EAST / SOUTH, bottom row negative
 * DOWN / WEST / NORTH). {@link RotationApplier#isCompass} blocks — floor heads, standing banners
 * and signs — get a 3×3 compass rose (NW N NE / W · E / SW S SE) with an empty centre. Each cell
 * holds the {@link games.brennan.dungeontrain.editor.VariantRotation#dirMask()} slot it toggles,
 * or -1 for none.</p>
 */
final class RotationPopupLayout {

    private static final int[][] FACING_GRID = {
        { Direction.UP.ordinal(), Direction.EAST.ordinal(), Direction.SOUTH.ordinal() },
        { Direction.DOWN.ordinal(), Direction.WEST.ordinal(), Direction.NORTH.ordinal() }
    };

    /** Compass slots: S=0 SW=1 W=2 NW=3 N=4 NE=5 E=6 SE=7 (see {@link RotationApplier}). */
    private static final int[][] COMPASS_GRID = {
        { 3, 4, 5 },
        { 2, -1, 6 },
        { 1, 0, 7 }
    };

    private RotationPopupLayout() {}

    /** Slot grid for {@code state}, row-major from the top. */
    static int[][] grid(BlockState state) {
        return RotationApplier.isCompass(state) ? COMPASS_GRID : FACING_GRID;
    }

    /** Popup bounds for one row's direction cell: {@code {left, bottom, right, top}}. */
    static double[] bounds(int[][] grid, int rowIndex, double colActualW, double gridTop, double halfW) {
        int col = rowIndex / BlockVariantMenu.ROWS_PER_COLUMN;
        int row = rowIndex % BlockVariantMenu.ROWS_PER_COLUMN;
        double colXL = -halfW + col * colActualW;
        double rowTop = gridTop - row * BlockVariantMenuRenderer.ROW_HEIGHT;

        double popupW = grid[0].length * BlockVariantMenuRenderer.POPUP_BUTTON_SIZE + 0.04;
        double popupH = grid.length * BlockVariantMenuRenderer.POPUP_BUTTON_SIZE + 0.04;
        double popupCX = colXL + colActualW
            - BlockVariantMenuRenderer.ROT_DIRS_CELL_WIDTH / 2.0
            - BlockVariantMenuRenderer.WEIGHT_CELL_WIDTH;
        double popupBot = rowTop + 0.02;
        double popupL = popupCX - popupW / 2.0;
        return new double[] { popupL, popupBot, popupL + popupW, popupBot + popupH };
    }

    /** Button bounds for grid cell {@code (gx, gy)}: {@code {left, bottom, right, top}}. */
    static double[] button(double[] popup, int gx, int gy) {
        double size = BlockVariantMenuRenderer.POPUP_BUTTON_SIZE;
        double bL = popup[0] + 0.02 + gx * size;
        double bTop = popup[3] - 0.02 - gy * size;
        return new double[] { bL, bTop - size + 0.005, bL + size - 0.005, bTop };
    }
}
