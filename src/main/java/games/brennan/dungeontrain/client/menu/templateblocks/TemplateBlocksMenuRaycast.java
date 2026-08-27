package games.brennan.dungeontrain.client.menu.templateblocks;

import games.brennan.dungeontrain.config.ClientDisplayConfig;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Camera-ray hover detection for {@link TemplateBlocksMenu}. Geometry mirrors
 * {@link TemplateBlocksMenuRenderer} exactly so hover tints line up with what
 * a click will hit. Mirrors
 * {@link games.brennan.dungeontrain.client.menu.blockvariant.BlockVariantMenuRaycast}.
 */
public final class TemplateBlocksMenuRaycast {

    private static final double MAX_REACH = 12.0;

    private TemplateBlocksMenuRaycast() {}

    public static void updateHovered() {
        if (!TemplateBlocksMenu.isActiveWorldspace()) {
            TemplateBlocksMenu.setHovered(TemplateBlocksMenu.Hit.NONE);
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 rayOrigin = camera.getPosition();
        Vector3f lookF = camera.getLookVector();
        Vec3 rayDir = new Vec3(lookF.x, lookF.y, lookF.z);

        Vec3 anchor = TemplateBlocksMenu.anchorPos();
        Vec3 right = TemplateBlocksMenu.anchorRight();
        Vec3 up = TemplateBlocksMenu.anchorUp();
        Vec3 normal = TemplateBlocksMenu.anchorNormal();

        Vec3 offset = rayOrigin.subtract(anchor);
        double oz = offset.dot(normal);
        double dz = rayDir.dot(normal);
        if (Math.abs(dz) < 1.0e-6) {
            TemplateBlocksMenu.setHovered(TemplateBlocksMenu.Hit.NONE);
            return;
        }
        double t = -oz / dz;
        if (t < 0 || t > MAX_REACH) {
            TemplateBlocksMenu.setHovered(TemplateBlocksMenu.Hit.NONE);
            return;
        }

        double ox = offset.dot(right);
        double oy = offset.dot(up);
        double dx = rayDir.dot(right);
        double dy = rayDir.dot(up);
        double hitX = ox + t * dx;
        double hitY = oy + t * dy;

        double worldScale = ClientDisplayConfig.getWorldspaceScale();
        if (worldScale != 1.0) {
            hitX /= worldScale;
            hitY /= worldScale;
        }

        TemplateBlocksMenu.setHovered(hitTest(hitX, hitY));
    }

    /**
     * Which cell sits at these panel-local coordinates. Pure — the raycast above only produces
     * the pair, and the screen-space host produces the same pair from the mouse cursor, so both
     * modes resolve hover and clicks through this one function.
     */
    static TemplateBlocksMenu.Hit hitTest(double hitX, double hitY) {
        int n = TemplateBlocksMenu.entries().size();
        TemplateBlocksMenuRenderer.PanelSize size = TemplateBlocksMenuRenderer.panelSize();
        int colCount = size.columns();
        double panelW = size.panelW();
        double gridH = size.gridH();
        double halfW = size.halfW();
        double halfH = size.halfH();

        if (hitX < -halfW || hitX > halfW || hitY < -halfH || hitY > halfH) {
            return TemplateBlocksMenu.Hit.NONE;
        }

        double gridTop = halfH - TemplateBlocksMenuRenderer.HEADER_HEIGHT;

        // Header — close button on the right, otherwise no-op.
        if (hitY > gridTop) {
            double closeL = halfW - TemplateBlocksMenuRenderer.CLOSE_CELL_WIDTH;
            if (hitX >= closeL) return new TemplateBlocksMenu.Hit(TemplateBlocksMenu.CellKind.CLOSE, -1);
            return TemplateBlocksMenu.Hit.NONE;
        }

        // Grid
        double colActualW = panelW / colCount;
        double yFromGridTop = gridTop - hitY;
        if (yFromGridTop < 0 || yFromGridTop > gridH) return TemplateBlocksMenu.Hit.NONE;
        int row = (int) Math.floor(yFromGridTop / TemplateBlocksMenuRenderer.ROW_HEIGHT);
        int col = (int) Math.floor((hitX + halfW) / colActualW);
        if (col < 0 || col >= colCount) return TemplateBlocksMenu.Hit.NONE;
        int idx = col * TemplateBlocksMenu.ROWS_PER_COLUMN + row;
        if (idx >= n) return TemplateBlocksMenu.Hit.NONE;

        double colXL = -halfW + col * colActualW;
        double swapR = colXL + colActualW - 0.02;
        double swapL = swapR - TemplateBlocksMenuRenderer.SWAP_CELL_WIDTH;
        if (hitX >= swapL && hitX <= swapR) {
            return new TemplateBlocksMenu.Hit(TemplateBlocksMenu.CellKind.ROW_SWAP, idx);
        }
        return new TemplateBlocksMenu.Hit(TemplateBlocksMenu.CellKind.ROW, idx);
    }
}
