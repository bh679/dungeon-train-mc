package games.brennan.dungeontrain.client.menu.containercontents;

import games.brennan.dungeontrain.net.ContainerContentsSyncPacket;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.List;

/**
 * Camera-ray hover detection for {@link ContainerContentsMenu}. Mirrors
 * {@link games.brennan.dungeontrain.client.menu.blockvariant.BlockVariantMenuRaycast}
 * with a simplified per-row layout (no rotation columns, no lock).
 */
public final class ContainerContentsMenuRaycast {

    private static final double MAX_REACH = 12.0;

    private ContainerContentsMenuRaycast() {}

    public static void updateHovered() {
        if (!ContainerContentsMenu.isActive() || ContainerContentsMenu.localPos() == null) {
            ContainerContentsMenu.setHovered(ContainerContentsMenu.Hit.NONE);
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 rayOrigin = camera.getPosition();
        Vector3f lookF = camera.getLookVector();
        Vec3 rayDir = new Vec3(lookF.x, lookF.y, lookF.z);

        Vec3 anchor = ContainerContentsMenu.anchorPos();
        Vec3 right = ContainerContentsMenu.anchorRight();
        Vec3 up = ContainerContentsMenu.anchorUp();
        Vec3 normal = ContainerContentsMenu.anchorNormal();

        Vec3 offset = rayOrigin.subtract(anchor);
        double oz = offset.dot(normal);
        double dz = rayDir.dot(normal);
        if (Math.abs(dz) < 1.0e-6) {
            ContainerContentsMenu.setHovered(ContainerContentsMenu.Hit.NONE);
            return;
        }
        double t = -oz / dz;
        if (t < 0 || t > MAX_REACH) {
            ContainerContentsMenu.setHovered(ContainerContentsMenu.Hit.NONE);
            return;
        }

        double ox = offset.dot(right);
        double oy = offset.dot(up);
        double dx = rayDir.dot(right);
        double dy = rayDir.dot(up);
        double hitX = ox + t * dx;
        double hitY = oy + t * dy;

        if (ContainerContentsMenu.screen() == ContainerContentsMenu.Screen.ROOT) {
            ContainerContentsMenu.setHovered(rootHit(hitX, hitY));
        } else {
            ContainerContentsMenu.setHovered(searchHit(hitX, hitY));
        }
    }

    private static ContainerContentsMenu.Hit rootHit(double hitX, double hitY) {
        List<ContainerContentsSyncPacket.Entry> entries = ContainerContentsMenu.entries();
        int n = entries.size();
        int colCount = Math.max(1, (n + ContainerContentsMenu.ROWS_PER_COLUMN - 1) / ContainerContentsMenu.ROWS_PER_COLUMN);
        double panelW = Math.max(ContainerContentsMenuRenderer.MIN_PANEL_WIDTH, colCount * ContainerContentsMenuRenderer.COLUMN_WIDTH);
        int displayedRows = Math.min(n, ContainerContentsMenu.ROWS_PER_COLUMN);
        if (displayedRows == 0) displayedRows = 1;
        double gridH = displayedRows * ContainerContentsMenuRenderer.ROW_HEIGHT;
        double panelH = ContainerContentsMenuRenderer.HEADER_HEIGHT + ContainerContentsMenuRenderer.TOOLBAR_HEIGHT + gridH;
        double halfW = panelW / 2.0;
        double halfH = panelH / 2.0;
        double colActualW = panelW / colCount;

        if (hitX < -halfW || hitX > halfW || hitY < -halfH || hitY > halfH) {
            return ContainerContentsMenu.Hit.NONE;
        }

        double headerBottom = halfH - ContainerContentsMenuRenderer.HEADER_HEIGHT;
        double toolbarBottom = headerBottom - ContainerContentsMenuRenderer.TOOLBAR_HEIGHT;

        if (hitY > headerBottom) return ContainerContentsMenu.Hit.NONE;

        // Toolbar — 6 cells: Add | Save | FillMin | FillMax | Clear | X
        if (hitY > toolbarBottom) {
            double cellW = panelW / 6.0;
            int idx = (int) Math.floor((hitX + halfW) / cellW);
            if (idx < 0) idx = 0;
            if (idx > 5) idx = 5;
            ContainerContentsMenu.CellKind kind = switch (idx) {
                case 0 -> ContainerContentsMenu.CellKind.ADD;
                case 1 -> ContainerContentsMenu.CellKind.SAVE;
                case 2 -> ContainerContentsMenu.CellKind.FILL_MIN;
                case 3 -> ContainerContentsMenu.CellKind.FILL_MAX;
                case 4 -> ContainerContentsMenu.CellKind.CLEAR;
                default -> ContainerContentsMenu.CellKind.CLOSE;
            };
            return new ContainerContentsMenu.Hit(kind, -1);
        }

        // Grid
        double gridTop = toolbarBottom;
        double yFromGridTop = gridTop - hitY;
        if (yFromGridTop < 0 || yFromGridTop > gridH) return ContainerContentsMenu.Hit.NONE;
        int row = (int) Math.floor(yFromGridTop / ContainerContentsMenuRenderer.ROW_HEIGHT);
        int col = (int) Math.floor((hitX + halfW) / colActualW);
        if (col < 0 || col >= colCount) return ContainerContentsMenu.Hit.NONE;
        int idx = col * ContainerContentsMenu.ROWS_PER_COLUMN + row;
        if (idx >= n) return ContainerContentsMenu.Hit.NONE;

        // Cell layout (right-to-left): [×] [weight] [count] [name]
        double colXL = -halfW + col * colActualW;
        double colXR = colXL + colActualW;
        double xR = colXR;
        double xCellL = xR - ContainerContentsMenuRenderer.X_CELL_WIDTH;
        double weightR = xCellL;
        double weightL = weightR - ContainerContentsMenuRenderer.WEIGHT_CELL_WIDTH;
        double countR = weightL;
        double countL = countR - ContainerContentsMenuRenderer.COUNT_CELL_WIDTH;

        if (hitX >= xCellL) return new ContainerContentsMenu.Hit(ContainerContentsMenu.CellKind.ENTRY_REMOVE_X, idx);
        if (hitX >= weightL && hitX <= weightR)
            return new ContainerContentsMenu.Hit(ContainerContentsMenu.CellKind.ENTRY_WEIGHT_PLUS, idx);
        if (hitX >= countL && hitX <= countR)
            return new ContainerContentsMenu.Hit(ContainerContentsMenu.CellKind.ENTRY_COUNT_PLUS, idx);
        return new ContainerContentsMenu.Hit(ContainerContentsMenu.CellKind.ENTRY_NAME, idx);
    }

    private static ContainerContentsMenu.Hit searchHit(double hitX, double hitY) {
        List<String> filtered = ContainerContentsMenu.filteredItemIds();
        int maxRows = ContainerContentsMenu.ROWS_PER_COLUMN * 4;
        int n = Math.min(filtered.size(), maxRows);
        int colCount = Math.max(1, (n + ContainerContentsMenu.ROWS_PER_COLUMN - 1) / ContainerContentsMenu.ROWS_PER_COLUMN);
        double panelW = Math.max(ContainerContentsMenuRenderer.MIN_PANEL_WIDTH, colCount * ContainerContentsMenuRenderer.COLUMN_WIDTH);
        int displayedRows = Math.min(n, ContainerContentsMenu.ROWS_PER_COLUMN);
        if (displayedRows == 0) displayedRows = 1;
        double gridH = displayedRows * ContainerContentsMenuRenderer.ROW_HEIGHT;
        double panelH = ContainerContentsMenuRenderer.HEADER_HEIGHT + ContainerContentsMenuRenderer.TOOLBAR_HEIGHT + gridH;
        double halfW = panelW / 2.0;
        double halfH = panelH / 2.0;
        if (hitX < -halfW || hitX > halfW || hitY < -halfH || hitY > halfH) return ContainerContentsMenu.Hit.NONE;
        double headerBottom = halfH - ContainerContentsMenuRenderer.HEADER_HEIGHT;
        double searchBottom = headerBottom - ContainerContentsMenuRenderer.TOOLBAR_HEIGHT;
        if (hitY > headerBottom) {
            double backCellL = -halfW;
            double backCellR = backCellL + 0.6;
            if (hitX >= backCellL && hitX <= backCellR)
                return new ContainerContentsMenu.Hit(ContainerContentsMenu.CellKind.SEARCH_BACK, -1);
            return ContainerContentsMenu.Hit.NONE;
        }
        if (hitY > searchBottom) return new ContainerContentsMenu.Hit(ContainerContentsMenu.CellKind.SEARCH_FIELD, -1);
        double yFromGridTop = searchBottom - hitY;
        if (yFromGridTop < 0 || yFromGridTop > gridH) return ContainerContentsMenu.Hit.NONE;
        double colActualW = panelW / colCount;
        int row = (int) Math.floor(yFromGridTop / ContainerContentsMenuRenderer.ROW_HEIGHT);
        int col = (int) Math.floor((hitX + halfW) / colActualW);
        if (col < 0 || col >= colCount) return ContainerContentsMenu.Hit.NONE;
        int idx = col * ContainerContentsMenu.ROWS_PER_COLUMN + row;
        if (idx >= n) return ContainerContentsMenu.Hit.NONE;
        return new ContainerContentsMenu.Hit(ContainerContentsMenu.CellKind.SEARCH_RESULT, idx);
    }
}
