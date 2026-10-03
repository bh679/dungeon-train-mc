package games.brennan.dungeontrain.client.menu.blockvariant;

import games.brennan.dungeontrain.editor.GrowthShapes;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.editor.RedstoneToggle;
import games.brennan.dungeontrain.editor.VariantConnect;
import games.brennan.dungeontrain.editor.RotationApplier;
import games.brennan.dungeontrain.editor.VariantRotation;
import games.brennan.dungeontrain.net.BlockVariantSyncPacket;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
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
        if (!BlockVariantMenu.isActiveWorldspace() || BlockVariantMenu.localPos() == null) {
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

    /**
     * Which cell sits at these panel-local coordinates on the root panel. Pure — the raycast
     * above only produces the pair, and the screen-space host produces the same pair from the
     * mouse cursor, so both modes resolve hover and clicks through this one function. The
     * rotation-options popup is picked from inside here, so it needs no separate seam.
     */
    static BlockVariantMenu.Hit rootHit(double hitX, double hitY) {
        List<BlockVariantSyncPacket.Entry> entries = BlockVariantMenu.entries();
        int n = entries.size();
        int colCount = Math.max(1, (n + BlockVariantMenu.ROWS_PER_COLUMN - 1) / BlockVariantMenu.ROWS_PER_COLUMN);
        double panelW = Math.max(BlockVariantMenuRenderer.minPanelWidth(), colCount * BlockVariantMenuRenderer.COLUMN_WIDTH);
        int displayedRows = Math.min(n, BlockVariantMenu.ROWS_PER_COLUMN);
        if (displayedRows == 0) displayedRows = 1;
        double gridH = displayedRows * BlockVariantMenuRenderer.ROW_HEIGHT;
        double panelH = BlockVariantMenuRenderer.HEADER_HEIGHT + BlockVariantMenuRenderer.TOOLBAR_HEIGHT + gridH;
        double halfW = panelW / 2.0;
        double halfH = panelH / 2.0;
        double colActualW = panelW / colCount;

        double gridTopAbs = halfH - BlockVariantMenuRenderer.HEADER_HEIGHT - BlockVariantMenuRenderer.TOOLBAR_HEIGHT;

        // Span option strip (above the panel) — modal like the OPTIONS popup: an option is a pick,
        // anywhere else in the panel closes it (secondary -2), outside both does nothing.
        // Grow popup — the same modal rules as the Span strip below.
        int growthRow = BlockVariantMenu.growthPopupRow();
        BlockState growthState = growthRow >= 0
            ? BlockVariantMenu.parseState(entries.get(growthRow).stateString()) : null;
        if (growthState != null) {
            java.util.List<GrowthPopupLayout.Row> rows = GrowthPopupLayout.rows(
                games.brennan.dungeontrain.editor.VariantGrowth.fromInt(entries.get(growthRow).growth()),
                growthState, panelW, halfH);
            for (GrowthPopupLayout.Row row : rows) {
                if (hitY < row.bottom() || hitY > row.top()) continue;
                double rel = hitX - row.buttonsLeft();
                if (rel < 0 || rel > GrowthPopupLayout.BUTTONS_WIDTH) continue;
                int opt = Math.min(row.buttons().length - 1, (int) Math.floor(rel / row.buttonWidth()));
                return new BlockVariantMenu.Hit(BlockVariantMenu.CellKind.GROWTH_OPTION, growthRow,
                    GrowthPopupLayout.encode(row.section(), opt));
            }
            double[] r = GrowthPopupLayout.rect(rows);
            if (hitX >= r[0] && hitX <= r[1] && hitY >= r[2] && hitY <= r[3]) {
                return new BlockVariantMenu.Hit(BlockVariantMenu.CellKind.GROWTH_OPTION, growthRow, -1);
            }
            if (hitX >= -halfW && hitX <= halfW && hitY >= -halfH && hitY <= halfH) {
                return new BlockVariantMenu.Hit(BlockVariantMenu.CellKind.GROWTH_OPTION, growthRow, -2);
            }
            return BlockVariantMenu.Hit.NONE;
        }
        if (BlockVariantMenu.spanPopupOpen()) {
            java.util.List<SpanPopupLayout.Row> rows = SpanPopupLayout.rows(panelW, halfH);
            for (SpanPopupLayout.Row row : rows) {
                if (hitY < row.bottom() || hitY > row.top()) continue;
                double rel = hitX - row.buttonsLeft();
                if (rel < 0 || rel > SpanPopupLayout.BUTTONS_WIDTH) continue;
                int opt = Math.min(row.buttons().length - 1, (int) Math.floor(rel / row.buttonWidth()));
                return new BlockVariantMenu.Hit(BlockVariantMenu.CellKind.SPAN_OPTION, -1,
                    SpanPopupLayout.encode(row.section(), opt));
            }
            double[] r = SpanPopupLayout.rect(rows);
            if (hitX >= r[0] && hitX <= r[1] && hitY >= r[2] && hitY <= r[3]) {
                return new BlockVariantMenu.Hit(BlockVariantMenu.CellKind.SPAN_OPTION, -1, -1);
            }
            if (hitX >= -halfW && hitX <= halfW && hitY >= -halfH && hitY <= halfH) {
                return new BlockVariantMenu.Hit(BlockVariantMenu.CellKind.SPAN_OPTION, -1, -2);
            }
            return BlockVariantMenu.Hit.NONE;
        }

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

        // Toolbar — the same cells the renderer drew, in the same order, from the same list.
        if (hitY > toolbarBottom) {
            java.util.List<BlockVariantMenu.CellKind> toolbar = BlockVariantMenu.toolbarCells();
            double cellW = panelW / toolbar.size();
            int idx = (int) Math.floor((hitX + halfW) / cellW);
            if (idx < 0) idx = 0;
            if (idx >= toolbar.size()) idx = toolbar.size() - 1;
            return new BlockVariantMenu.Hit(toolbar.get(idx), -1);
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
        // A group-reference row owns none of these: rotation, half and
        // difficulty all come from whatever entry the referenced group
        // resolves to, so the cells collapse (matching the renderer).
        boolean concrete = !entry.isMob() && !entry.isGroupRef();
        boolean rotatable = parsed != null && concrete && RotationApplier.canRotate(parsed);
        boolean halfable = parsed != null && concrete && RotationApplier.canFlip(parsed);
        boolean toggleable = parsed != null && concrete && RedstoneToggle.canToggle(parsed);
        boolean connectable = parsed != null && concrete && BlockVariantMenu.connectSupported()
            && VariantConnect.canConnect(parsed);
        VariantRotation.Mode rowMode = BlockVariantMenuRenderer.decodeMode(entry.rotMode());
        boolean showDirs = rotatable && rowMode != VariantRotation.Mode.RANDOM;
        double rotDirsCellR = weightCellL;
        double rotDirsCellL = showDirs ? rotDirsCellR - BlockVariantMenuRenderer.ROT_DIRS_CELL_WIDTH : rotDirsCellR;
        double rotModeCellR = rotDirsCellL;
        double rotModeCellL = rotatable ? rotModeCellR - BlockVariantMenuRenderer.ROT_MODE_CELL_WIDTH : rotModeCellR;
        double halfModeCellR = rotModeCellL;
        double halfModeCellL = halfable ? halfModeCellR - BlockVariantMenuRenderer.HALF_MODE_CELL_WIDTH : halfModeCellR;
        double activeModeCellR = halfModeCellL;
        double activeModeCellL = toggleable ? activeModeCellR - BlockVariantMenuRenderer.ACTIVE_MODE_CELL_WIDTH : activeModeCellR;
        double connectCellR = activeModeCellL;
        double connectCellL = connectable ? connectCellR - BlockVariantMenuRenderer.CONNECT_CELL_WIDTH : connectCellR;
        boolean showArms = connectable
            && VariantConnect.Mode.fromOrdinal(entry.connectMode() & 0xFF) == VariantConnect.Mode.LOCK;
        double armsCellR = connectCellL;
        double armsCellL = showArms ? armsCellR - BlockVariantMenuRenderer.ARMS_CELL_WIDTH : armsCellR;
        boolean growable = parsed != null && concrete && BlockVariantMenu.growthSupported()
            && GrowthShapes.canGrow(parsed);
        double growthCellR = armsCellL;
        double growthCellL = growable ? growthCellR - BlockVariantMenuRenderer.GROWTH_CELL_WIDTH : growthCellR;
        // Difficulty cells (mob rows only) — mirror the renderer geometry: they
        // occupy the space the rotation/half cells leave free on a mob row.
        boolean showDiff = entry.isMob();
        double diffMaxCellR = growthCellL;
        double diffMaxCellL = showDiff ? diffMaxCellR - BlockVariantMenuRenderer.DIFF_CELL_WIDTH : diffMaxCellR;
        double diffMinCellR = diffMaxCellL;
        double diffMinCellL = showDiff ? diffMinCellR - BlockVariantMenuRenderer.DIFF_CELL_WIDTH : diffMinCellR;

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
        if (halfable && hitX >= halfModeCellL && hitX <= halfModeCellR) {
            return new BlockVariantMenu.Hit(BlockVariantMenu.CellKind.ENTRY_HALF_MODE, idx);
        }
        if (toggleable && hitX >= activeModeCellL && hitX <= activeModeCellR) {
            return new BlockVariantMenu.Hit(BlockVariantMenu.CellKind.ENTRY_ACTIVE_MODE, idx);
        }
        if (connectable && hitX >= connectCellL && hitX <= connectCellR) {
            int seg = BlockVariantMenuRenderer.segmentAt(hitX, connectCellL, connectCellR,
                BlockVariantMenu.CONNECT_SEGMENTS.length);
            return new BlockVariantMenu.Hit(BlockVariantMenu.CONNECT_SEGMENTS[seg], idx);
        }
        if (showArms && hitX >= armsCellL && hitX <= armsCellR) {
            int seg = BlockVariantMenuRenderer.segmentAt(hitX, armsCellL, armsCellR,
                BlockVariantMenu.ARM_SEGMENTS.length);
            return new BlockVariantMenu.Hit(BlockVariantMenu.ARM_SEGMENTS[seg], idx);
        }
        if (growable && hitX >= growthCellL && hitX <= growthCellR) {
            return new BlockVariantMenu.Hit(BlockVariantMenu.CellKind.ENTRY_GROWTH, idx);
        }
        if (showDiff && hitX >= diffMinCellL && hitX <= diffMinCellR) {
            return new BlockVariantMenu.Hit(BlockVariantMenu.CellKind.ENTRY_DIFF_MIN, idx);
        }
        if (showDiff && hitX >= diffMaxCellL && hitX <= diffMaxCellR) {
            return new BlockVariantMenu.Hit(BlockVariantMenu.CellKind.ENTRY_DIFF_MAX, idx);
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
        int[][] grid = RotationPopupLayout.grid(parsed);
        double[] popup = RotationPopupLayout.bounds(grid, rowIndex, colActualW, gridTop, halfW);
        if (hitX < popup[0] || hitX > popup[2] || hitY < popup[1] || hitY > popup[3]) {
            return BlockVariantMenu.Hit.NONE;
        }

        for (int gy = 0; gy < grid.length; gy++) {
            for (int gx = 0; gx < grid[gy].length; gx++) {
                if (grid[gy][gx] < 0) continue;
                double[] b = RotationPopupLayout.button(popup, gx, gy);
                if (hitX >= b[0] && hitX <= b[2] && hitY >= b[1] && hitY <= b[3]) {
                    return new BlockVariantMenu.Hit(
                        BlockVariantMenu.CellKind.ROT_DIR_OPTION, rowIndex, grid[gy][gx]);
                }
            }
        }
        // Hit the popup background but not a button — still consume so the
        // click closes the popup (handled by the input dispatcher).
        return new BlockVariantMenu.Hit(BlockVariantMenu.CellKind.ROT_DIR_OPTION, rowIndex, -1);
    }

    /** {@link #rootHit} for the Add-search panel. */
    static BlockVariantMenu.Hit searchHit(double hitX, double hitY) {
        List<String> filtered = BlockVariantMenu.filteredBlockIds();
        int maxRows = BlockVariantMenu.ROWS_PER_COLUMN * 4;
        int n = Math.min(filtered.size(), maxRows);
        int colCount = Math.max(1, (n + BlockVariantMenu.ROWS_PER_COLUMN - 1) / BlockVariantMenu.ROWS_PER_COLUMN);
        double panelW = Math.max(BlockVariantMenuRenderer.minPanelWidth(), colCount * BlockVariantMenuRenderer.COLUMN_WIDTH);
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
