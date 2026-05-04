package games.brennan.dungeontrain.client.menu.blockvariant;

import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.editor.RotationApplier;
import games.brennan.dungeontrain.editor.VariantRotation;
import games.brennan.dungeontrain.net.BlockVariantSyncPacket;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.List;

/**
 * Camera-ray hover detection for {@link BlockVariantMenu}. Mirrors
 * {@link games.brennan.dungeontrain.client.menu.parts.PartPositionMenuRaycast}
 * with the new toolbar (5 cells: Copy/Add/Remove/Clear/Close) and the
 * per-row Lock cell (replacing side-mode).
 */
public final class BlockVariantMenuRaycast {

    private static final double MAX_REACH = 12.0;

    private BlockVariantMenuRaycast() {}

    public static void updateHovered() {
        if (!BlockVariantMenu.isActive() || BlockVariantMenu.localPos() == null) {
            BlockVariantMenu.setHovered(BlockVariantMenu.Hit.NONE);
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 rayOrigin = camera.getPosition();
        Vector3f lookF = camera.getLookVector();
        Vec3 rayDir = new Vec3(lookF.x, lookF.y, lookF.z);

        Vec3 anchor = BlockVariantMenu.anchorPos();
        Vec3 right = BlockVariantMenu.anchorRight();
        Vec3 up = BlockVariantMenu.anchorUp();
        Vec3 normal = BlockVariantMenu.anchorNormal();

        Vec3 offset = rayOrigin.subtract(anchor);
        double oz = offset.dot(normal);
        double dz = rayDir.dot(normal);

        if (Math.abs(dz) < 1.0e-6) {
            BlockVariantMenu.setHovered(BlockVariantMenu.Hit.NONE);
            return;
        }
        double t = -oz / dz;
        if (t < 0 || t > MAX_REACH) {
            BlockVariantMenu.setHovered(BlockVariantMenu.Hit.NONE);
            return;
        }

        double ox = offset.dot(right);
        double oy = offset.dot(up);
        double dx = rayDir.dot(right);
        double dy = rayDir.dot(up);
        double hitX = ox + t * dx;
        double hitY = oy + t * dy;

        // Match the world-space scale applied uniformly by
        // {@link BlockVariantMenuRenderer} — divide the hit point so the
        // unscaled layout constants below still correspond to visible cells.
        double worldScale = ClientDisplayConfig.getWorldspaceScale();
        if (worldScale != 1.0) {
            hitX /= worldScale;
            hitY /= worldScale;
        }

        if (BlockVariantMenu.screen() == BlockVariantMenu.Screen.ROOT) {
            BlockVariantMenu.setHovered(rootHit(hitX, hitY));
        } else {
            BlockVariantMenu.setHovered(searchHit(hitX, hitY));
        }
    }

    private static BlockVariantMenu.Hit rootHit(double hitX, double hitY) {
        List<BlockVariantSyncPacket.Entry> entries = BlockVariantMenu.entries();
        int n = entries.size();
        int colCount = Math.max(1, (n + BlockVariantMenu.ROWS_PER_COLUMN - 1) / BlockVariantMenu.ROWS_PER_COLUMN);
        double panelW = Math.max(BlockVariantMenuRenderer.MIN_PANEL_WIDTH, colCount * BlockVariantMenuRenderer.COLUMN_WIDTH);
        int displayedRows = Math.min(n, BlockVariantMenu.ROWS_PER_COLUMN);
        if (displayedRows == 0) displayedRows = 1;
        double gridH = displayedRows * BlockVariantMenuRenderer.ROW_HEIGHT;
        double panelH = BlockVariantMenuRenderer.HEADER_HEIGHT + BlockVariantMenuRenderer.TOOLBAR_HEIGHT + gridH;
        double halfW = panelW / 2.0;
        double halfH = panelH / 2.0;
        double colActualW = panelW / colCount;

        double gridTopAbs = halfH - BlockVariantMenuRenderer.HEADER_HEIGHT - BlockVariantMenuRenderer.TOOLBAR_HEIGHT;

        // Popup modal — when open, the popup absorbs every hit inside the
        // menu panel. Buttons toggle directions; anywhere else inside the
        // panel closes the popup. The underlying toolbar / row cells
        // never see the click. Outside the panel returns NONE (no action,
        // popup stays open).
        int popupRow = BlockVariantMenu.rotPopupRowIndex();
        if (popupRow >= 0 && popupRow < n) {
            BlockVariantMenu.Hit popupHit = popupHit(hitX, hitY, popupRow, entries.get(popupRow),
                colActualW, gridTopAbs, halfW);
            if (popupHit != BlockVariantMenu.Hit.NONE) return popupHit;
            if (hitX >= -halfW && hitX <= halfW && hitY >= -halfH && hitY <= halfH) {
                return new BlockVariantMenu.Hit(BlockVariantMenu.CellKind.ROT_DIR_OPTION, popupRow, -2);
            }
            return BlockVariantMenu.Hit.NONE;
        }

        if (hitX < -halfW || hitX > halfW || hitY < -halfH || hitY > halfH) {
            return BlockVariantMenu.Hit.NONE;
        }

        double headerBottom = halfH - BlockVariantMenuRenderer.HEADER_HEIGHT;
        double toolbarBottom = headerBottom - BlockVariantMenuRenderer.TOOLBAR_HEIGHT;

        if (hitY > headerBottom) return BlockVariantMenu.Hit.NONE;

        // Toolbar — 7 cells: Copy | Save | Add | Lock | Remove | Clear | X.
        if (hitY > toolbarBottom) {
            double cellW = panelW / 7.0;
            int idx = (int) Math.floor((hitX + halfW) / cellW);
            if (idx < 0) idx = 0;
            if (idx > 6) idx = 6;
            BlockVariantMenu.CellKind kind = switch (idx) {
                case 0 -> BlockVariantMenu.CellKind.COPY;
                case 1 -> BlockVariantMenu.CellKind.SAVE;
                case 2 -> BlockVariantMenu.CellKind.ADD;
                case 3 -> BlockVariantMenu.CellKind.LOCK;
                case 4 -> BlockVariantMenu.CellKind.REMOVE;
                case 5 -> BlockVariantMenu.CellKind.CLEAR;
                default -> BlockVariantMenu.CellKind.CLOSE;
            };
            return new BlockVariantMenu.Hit(kind, -1);
        }

        // Grid
        double gridTop = toolbarBottom;
        double yFromGridTop = gridTop - hitY;
        if (yFromGridTop < 0 || yFromGridTop > gridH) return BlockVariantMenu.Hit.NONE;
        int row = (int) Math.floor(yFromGridTop / BlockVariantMenuRenderer.ROW_HEIGHT);
        int col = (int) Math.floor((hitX + halfW) / colActualW);
        if (col < 0 || col >= colCount) return BlockVariantMenu.Hit.NONE;
        int idx = col * BlockVariantMenu.ROWS_PER_COLUMN + row;
        if (idx >= n) return BlockVariantMenu.Hit.NONE;

        boolean removeMode = BlockVariantMenu.removeMode();
        double colXL = -halfW + col * colActualW;
        double colXR = colXL + colActualW;
        double xCellW = removeMode ? BlockVariantMenuRenderer.X_CELL_WIDTH : 0.0;
        double weightCellR = colXR - xCellW;
        double weightCellL = weightCellR - BlockVariantMenuRenderer.WEIGHT_CELL_WIDTH;

        BlockVariantSyncPacket.Entry entry = entries.get(idx);
        BlockState parsed = BlockVariantMenu.parseState(entry.stateString());
        boolean rotatable = parsed != null && RotationApplier.canRotate(parsed);
        VariantRotation.Mode rowMode = BlockVariantMenuRenderer.decodeMode(entry.rotMode());
        boolean showDirs = rotatable && rowMode != VariantRotation.Mode.RANDOM;
        double rotDirsCellR = weightCellL;
        double rotDirsCellL = showDirs ? rotDirsCellR - BlockVariantMenuRenderer.ROT_DIRS_CELL_WIDTH : rotDirsCellR;
        double rotModeCellR = rotDirsCellL;
        double rotModeCellL = rotatable ? rotModeCellR - BlockVariantMenuRenderer.ROT_MODE_CELL_WIDTH : rotModeCellR;

        if (removeMode && hitX >= colXR - BlockVariantMenuRenderer.X_CELL_WIDTH) {
            return new BlockVariantMenu.Hit(BlockVariantMenu.CellKind.ENTRY_REMOVE_X, idx);
        }
        if (hitX >= weightCellL && hitX <= weightCellR) {
            return new BlockVariantMenu.Hit(BlockVariantMenu.CellKind.ENTRY_WEIGHT, idx);
        }
        if (rotatable && hitX >= rotDirsCellL && hitX <= rotDirsCellR) {
            return new BlockVariantMenu.Hit(BlockVariantMenu.CellKind.ENTRY_ROT_DIRS, idx);
        }
        if (rotatable && hitX >= rotModeCellL && hitX <= rotModeCellR) {
            return new BlockVariantMenu.Hit(BlockVariantMenu.CellKind.ENTRY_ROT_MODE, idx);
        }
        return new BlockVariantMenu.Hit(BlockVariantMenu.CellKind.ENTRY_NAME, idx);
    }

    /**
     * Hit-test the OPTIONS-mode popup. Geometry mirrors
     * {@code BlockVariantMenuRenderer#drawRotationOptionsPopup}. Returns
     * {@link BlockVariantMenu.Hit#NONE} if the ray missed the popup.
     */
    private static BlockVariantMenu.Hit popupHit(double hitX, double hitY, int rowIndex,
                                                 BlockVariantSyncPacket.Entry entry,
                                                 double colActualW, double gridTop, double halfW) {
        BlockState parsed = BlockVariantMenu.parseState(entry.stateString());
        if (parsed == null) return BlockVariantMenu.Hit.NONE;
        int col = rowIndex / BlockVariantMenu.ROWS_PER_COLUMN;
        int row = rowIndex % BlockVariantMenu.ROWS_PER_COLUMN;
        double colXL = -halfW + col * colActualW;
        double rowTop = gridTop - row * BlockVariantMenuRenderer.ROW_HEIGHT;

        double popupW = 3 * BlockVariantMenuRenderer.POPUP_BUTTON_SIZE + 0.04;
        double popupH = 2 * BlockVariantMenuRenderer.POPUP_BUTTON_SIZE + 0.04;
        double popupCX = colXL + colActualW
            - BlockVariantMenuRenderer.ROT_DIRS_CELL_WIDTH / 2.0
            - BlockVariantMenuRenderer.WEIGHT_CELL_WIDTH;
        double popupBot = rowTop + 0.02;
        double popupTop = popupBot + popupH;
        double popupL = popupCX - popupW / 2.0;
        double popupR = popupL + popupW;

        if (hitX < popupL || hitX > popupR || hitY < popupBot || hitY > popupTop) {
            return BlockVariantMenu.Hit.NONE;
        }

        Direction[][] grid = {
            { Direction.UP, Direction.EAST, Direction.SOUTH },
            { Direction.DOWN, Direction.WEST, Direction.NORTH }
        };
        for (int gy = 0; gy < 2; gy++) {
            for (int gx = 0; gx < 3; gx++) {
                double bL = popupL + 0.02 + gx * BlockVariantMenuRenderer.POPUP_BUTTON_SIZE;
                double bR = bL + BlockVariantMenuRenderer.POPUP_BUTTON_SIZE - 0.005;
                double bTop = popupTop - 0.02 - gy * BlockVariantMenuRenderer.POPUP_BUTTON_SIZE;
                double bBot = bTop - BlockVariantMenuRenderer.POPUP_BUTTON_SIZE + 0.005;
                if (hitX >= bL && hitX <= bR && hitY >= bBot && hitY <= bTop) {
                    return new BlockVariantMenu.Hit(
                        BlockVariantMenu.CellKind.ROT_DIR_OPTION,
                        rowIndex,
                        grid[gy][gx].ordinal());
                }
            }
        }
        // Hit the popup background but not a button — still consume so the
        // click closes the popup (handled by the input dispatcher).
        return new BlockVariantMenu.Hit(BlockVariantMenu.CellKind.ROT_DIR_OPTION, rowIndex, -1);
    }

    private static BlockVariantMenu.Hit searchHit(double hitX, double hitY) {
        List<String> filtered = BlockVariantMenu.filteredBlockIds();
        int maxRows = BlockVariantMenu.ROWS_PER_COLUMN * 4;
        int n = Math.min(filtered.size(), maxRows);
        int colCount = Math.max(1, (n + BlockVariantMenu.ROWS_PER_COLUMN - 1) / BlockVariantMenu.ROWS_PER_COLUMN);
        double panelW = Math.max(BlockVariantMenuRenderer.MIN_PANEL_WIDTH, colCount * BlockVariantMenuRenderer.COLUMN_WIDTH);
        int displayedRows = Math.min(n, BlockVariantMenu.ROWS_PER_COLUMN);
        if (displayedRows == 0) displayedRows = 1;
        double gridH = displayedRows * BlockVariantMenuRenderer.ROW_HEIGHT;
        double panelH = BlockVariantMenuRenderer.HEADER_HEIGHT + BlockVariantMenuRenderer.TOOLBAR_HEIGHT + gridH;
        double halfW = panelW / 2.0;
        double halfH = panelH / 2.0;
        if (hitX < -halfW || hitX > halfW || hitY < -halfH || hitY > halfH) {
            return BlockVariantMenu.Hit.NONE;
        }
        double headerBottom = halfH - BlockVariantMenuRenderer.HEADER_HEIGHT;
        double searchBottom = headerBottom - BlockVariantMenuRenderer.TOOLBAR_HEIGHT;
        if (hitY > headerBottom) {
            double backCellL = -halfW;
            double backCellR = backCellL + 0.6;
            if (hitX >= backCellL && hitX <= backCellR) {
                return new BlockVariantMenu.Hit(BlockVariantMenu.CellKind.SEARCH_BACK, -1);
            }
            return BlockVariantMenu.Hit.NONE;
        }
        if (hitY > searchBottom) {
            return new BlockVariantMenu.Hit(BlockVariantMenu.CellKind.SEARCH_FIELD, -1);
        }
        double yFromGridTop = searchBottom - hitY;
        if (yFromGridTop < 0 || yFromGridTop > gridH) return BlockVariantMenu.Hit.NONE;
        double colActualW = panelW / colCount;
        int row = (int) Math.floor(yFromGridTop / BlockVariantMenuRenderer.ROW_HEIGHT);
        int col = (int) Math.floor((hitX + halfW) / colActualW);
        if (col < 0 || col >= colCount) return BlockVariantMenu.Hit.NONE;
        int idx = col * BlockVariantMenu.ROWS_PER_COLUMN + row;
        if (idx >= n) return BlockVariantMenu.Hit.NONE;
        return new BlockVariantMenu.Hit(BlockVariantMenu.CellKind.SEARCH_RESULT, idx);
    }
}
