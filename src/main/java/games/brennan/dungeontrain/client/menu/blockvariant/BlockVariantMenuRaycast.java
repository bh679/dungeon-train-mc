package games.brennan.dungeontrain.client.menu.blockvariant;

import games.brennan.dungeontrain.net.BlockVariantSyncPacket;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
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
        if (hitX < -halfW || hitX > halfW || hitY < -halfH || hitY > halfH) {
            return BlockVariantMenu.Hit.NONE;
        }

        double headerBottom = halfH - BlockVariantMenuRenderer.HEADER_HEIGHT;
        double toolbarBottom = headerBottom - BlockVariantMenuRenderer.TOOLBAR_HEIGHT;

        if (hitY > headerBottom) return BlockVariantMenu.Hit.NONE;

        // Toolbar — 6 cells: Copy | Add | Lock | Remove | Clear | X.
        if (hitY > toolbarBottom) {
            double cellW = panelW / 6.0;
            int idx = (int) Math.floor((hitX + halfW) / cellW);
            if (idx < 0) idx = 0;
            if (idx > 5) idx = 5;
            BlockVariantMenu.CellKind kind = switch (idx) {
                case 0 -> BlockVariantMenu.CellKind.COPY;
                case 1 -> BlockVariantMenu.CellKind.ADD;
                case 2 -> BlockVariantMenu.CellKind.LOCK;
                case 3 -> BlockVariantMenu.CellKind.REMOVE;
                case 4 -> BlockVariantMenu.CellKind.CLEAR;
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

        if (removeMode && hitX >= colXR - BlockVariantMenuRenderer.X_CELL_WIDTH) {
            return new BlockVariantMenu.Hit(BlockVariantMenu.CellKind.ENTRY_REMOVE_X, idx);
        }
        if (hitX >= weightCellL && hitX <= weightCellR) {
            return new BlockVariantMenu.Hit(BlockVariantMenu.CellKind.ENTRY_WEIGHT, idx);
        }
        return new BlockVariantMenu.Hit(BlockVariantMenu.CellKind.ENTRY_NAME, idx);
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
