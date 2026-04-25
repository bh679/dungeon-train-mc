package games.brennan.dungeontrain.client.menu.parts;

import games.brennan.dungeontrain.train.CarriagePartAssignment.WeightedName;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.List;

/**
 * Camera-ray hover detection for {@link PartPositionMenu}.
 *
 * <p>Mirrors {@link games.brennan.dungeontrain.client.menu.CommandMenuRaycast}
 * — finds the ray's intersection with the panel plane (defined by
 * {@link PartPositionMenu#anchorPos} and {@link PartPositionMenu#anchorNormal})
 * and checks the hit against each cell's panel-local rectangle. Reports
 * the matched {@link PartPositionMenu.Hit} so the renderer can highlight
 * and the input handler can dispatch.</p>
 */
public final class PartPositionMenuRaycast {

    /** Same reach as the existing CommandMenuRaycast — generous enough that the menu is clickable from the carriage interior. */
    private static final double MAX_REACH = 12.0;

    private PartPositionMenuRaycast() {}

    public static void updateHovered() {
        if (!PartPositionMenu.isActive() || PartPositionMenu.kind() == null) {
            PartPositionMenu.setHovered(PartPositionMenu.Hit.NONE);
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 rayOrigin = camera.getPosition();
        Vector3f lookF = camera.getLookVector();
        Vec3 rayDir = new Vec3(lookF.x, lookF.y, lookF.z);

        Vec3 anchor = PartPositionMenu.anchorPos();
        Vec3 right = PartPositionMenu.anchorRight();
        Vec3 up = PartPositionMenu.anchorUp();
        Vec3 normal = PartPositionMenu.anchorNormal();

        Vec3 offset = rayOrigin.subtract(anchor);
        double oz = offset.dot(normal);
        double dz = rayDir.dot(normal);

        if (Math.abs(dz) < 1.0e-6) {
            PartPositionMenu.setHovered(PartPositionMenu.Hit.NONE);
            return;
        }
        double t = -oz / dz;
        if (t < 0 || t > MAX_REACH) {
            PartPositionMenu.setHovered(PartPositionMenu.Hit.NONE);
            return;
        }

        double ox = offset.dot(right);
        double oy = offset.dot(up);
        double dx = rayDir.dot(right);
        double dy = rayDir.dot(up);
        double hitX = ox + t * dx;
        double hitY = oy + t * dy;

        if (PartPositionMenu.screen() == PartPositionMenu.Screen.ROOT) {
            PartPositionMenu.setHovered(rootHit(hitX, hitY));
        } else {
            PartPositionMenu.setHovered(searchHit(hitX, hitY));
        }
    }

    private static PartPositionMenu.Hit rootHit(double hitX, double hitY) {
        List<WeightedName> entries = PartPositionMenu.entries();
        int n = entries.size();
        int colCount = Math.max(1, (n + PartPositionMenu.ROWS_PER_COLUMN - 1) / PartPositionMenu.ROWS_PER_COLUMN);
        double panelW = Math.max(PartPositionMenuRenderer.MIN_PANEL_WIDTH, colCount * PartPositionMenuRenderer.COLUMN_WIDTH);
        int displayedRows = Math.min(n, PartPositionMenu.ROWS_PER_COLUMN);
        if (displayedRows == 0) displayedRows = 1;
        double gridH = displayedRows * PartPositionMenuRenderer.ROW_HEIGHT;
        double panelH = PartPositionMenuRenderer.HEADER_HEIGHT + PartPositionMenuRenderer.TOOLBAR_HEIGHT + gridH;
        double halfW = panelW / 2.0;
        double halfH = panelH / 2.0;
        double colActualW = panelW / colCount;
        if (hitX < -halfW || hitX > halfW || hitY < -halfH || hitY > halfH) {
            return PartPositionMenu.Hit.NONE;
        }

        double headerBottom = halfH - PartPositionMenuRenderer.HEADER_HEIGHT;
        double toolbarBottom = headerBottom - PartPositionMenuRenderer.TOOLBAR_HEIGHT;

        // Header — non-interactive.
        if (hitY > headerBottom) {
            return PartPositionMenu.Hit.NONE;
        }

        // Toolbar
        if (hitY > toolbarBottom) {
            double cellW = panelW / 4.0;
            int idx = (int) Math.floor((hitX + halfW) / cellW);
            if (idx < 0) idx = 0;
            if (idx > 3) idx = 3;
            PartPositionMenu.CellKind kind = switch (idx) {
                case 0 -> PartPositionMenu.CellKind.ADD;
                case 1 -> PartPositionMenu.CellKind.REMOVE;
                case 2 -> PartPositionMenu.CellKind.CLEAR;
                default -> PartPositionMenu.CellKind.CLOSE;
            };
            return new PartPositionMenu.Hit(kind, -1);
        }

        // Grid
        double gridTop = toolbarBottom;
        double yFromGridTop = gridTop - hitY;
        if (yFromGridTop < 0 || yFromGridTop > gridH) return PartPositionMenu.Hit.NONE;
        int row = (int) Math.floor(yFromGridTop / PartPositionMenuRenderer.ROW_HEIGHT);
        int col = (int) Math.floor((hitX + halfW) / colActualW);
        if (col < 0 || col >= colCount) return PartPositionMenu.Hit.NONE;
        int idx = col * PartPositionMenu.ROWS_PER_COLUMN + row;
        if (idx >= n) return PartPositionMenu.Hit.NONE;

        boolean removeMode = PartPositionMenu.removeMode();
        boolean showSideMode = PartPositionMenu.kindHasSideMode(PartPositionMenu.kind());
        double colXL = -halfW + col * colActualW;
        double colXR = colXL + colActualW;
        double xCellW = removeMode ? PartPositionMenuRenderer.X_CELL_WIDTH : 0.0;
        double sideCellW = showSideMode ? PartPositionMenuRenderer.SIDE_MODE_CELL_WIDTH : 0.0;
        double sideCellR = colXR - xCellW;
        double sideCellL = sideCellR - sideCellW;
        double weightCellR = sideCellL;
        double weightCellL = weightCellR - PartPositionMenuRenderer.WEIGHT_CELL_WIDTH;

        if (removeMode && hitX >= colXR - PartPositionMenuRenderer.X_CELL_WIDTH) {
            return new PartPositionMenu.Hit(PartPositionMenu.CellKind.ENTRY_REMOVE_X, idx);
        }
        if (showSideMode && hitX >= sideCellL && hitX <= sideCellR) {
            return new PartPositionMenu.Hit(PartPositionMenu.CellKind.ENTRY_SIDE_MODE, idx);
        }
        if (hitX >= weightCellL && hitX <= weightCellR) {
            return new PartPositionMenu.Hit(PartPositionMenu.CellKind.ENTRY_WEIGHT, idx);
        }
        return new PartPositionMenu.Hit(PartPositionMenu.CellKind.ENTRY_NAME, idx);
    }

    private static PartPositionMenu.Hit searchHit(double hitX, double hitY) {
        List<String> filtered = PartPositionMenu.filteredRegisteredNames();
        int n = filtered.size();
        int colCount = Math.max(1, (n + PartPositionMenu.ROWS_PER_COLUMN - 1) / PartPositionMenu.ROWS_PER_COLUMN);
        double panelW = Math.max(PartPositionMenuRenderer.MIN_PANEL_WIDTH, colCount * PartPositionMenuRenderer.COLUMN_WIDTH);
        int displayedRows = Math.min(n, PartPositionMenu.ROWS_PER_COLUMN);
        if (displayedRows == 0) displayedRows = 1;
        double gridH = displayedRows * PartPositionMenuRenderer.ROW_HEIGHT;
        double panelH = PartPositionMenuRenderer.HEADER_HEIGHT + PartPositionMenuRenderer.TOOLBAR_HEIGHT + gridH;
        double halfW = panelW / 2.0;
        double halfH = panelH / 2.0;
        if (hitX < -halfW || hitX > halfW || hitY < -halfH || hitY > halfH) {
            return PartPositionMenu.Hit.NONE;
        }
        double headerBottom = halfH - PartPositionMenuRenderer.HEADER_HEIGHT;
        double searchBottom = headerBottom - PartPositionMenuRenderer.TOOLBAR_HEIGHT;
        // Header row: left "< Back" chip is interactive; the rest is just a label.
        if (hitY > headerBottom) {
            double backCellL = -halfW;
            double backCellR = backCellL + 0.6;
            if (hitX >= backCellL && hitX <= backCellR) {
                return new PartPositionMenu.Hit(PartPositionMenu.CellKind.SEARCH_BACK, -1);
            }
            return PartPositionMenu.Hit.NONE;
        }
        if (hitY > searchBottom) {
            return new PartPositionMenu.Hit(PartPositionMenu.CellKind.SEARCH_FIELD, -1);
        }
        double yFromGridTop = searchBottom - hitY;
        if (yFromGridTop < 0 || yFromGridTop > gridH) return PartPositionMenu.Hit.NONE;
        double colActualW = panelW / colCount;
        int row = (int) Math.floor(yFromGridTop / PartPositionMenuRenderer.ROW_HEIGHT);
        int col = (int) Math.floor((hitX + halfW) / colActualW);
        if (col < 0 || col >= colCount) return PartPositionMenu.Hit.NONE;
        int idx = col * PartPositionMenu.ROWS_PER_COLUMN + row;
        if (idx >= n) return PartPositionMenu.Hit.NONE;
        return new PartPositionMenu.Hit(PartPositionMenu.CellKind.SEARCH_RESULT, idx);
    }
}
