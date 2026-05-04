package games.brennan.dungeontrain.client.menu.plot;

import games.brennan.dungeontrain.client.menu.EditorPlotLabelsRenderer;
import games.brennan.dungeontrain.client.menu.plot.EditorTypeMenuRenderer.Hovered;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.net.EditorTypeMenusPacket;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.List;

/**
 * Camera-ray hover detection for the floating template-type menus. Mirrors
 * {@link EditorPlotPanelRaycast} — closest-hit-wins ray-plane intersection
 * over every cached menu, no upper-bound reach so long-distance
 * teleport / weight-bump clicks work from anywhere within render distance.
 */
public final class EditorTypeMenuRaycast {

    private EditorTypeMenuRaycast() {}

    public static void updateHovered() {
        List<EditorTypeMenusPacket.Menu> menus = EditorTypeMenuRenderer.menus();
        if (menus.isEmpty()) {
            EditorTypeMenuRenderer.setHovered(Hovered.NONE);
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 rayOrigin = camera.getPosition();
        Vector3f lookF = camera.getLookVector();
        Vec3 rayDir = new Vec3(lookF.x, lookF.y, lookF.z);

        // Match the renderer's world-space scale so panel-local cell rects
        // still cover the visible cells after the panel is scaled.
        double worldScale = ClientDisplayConfig.getWorldspaceScale();

        Hovered closest = Hovered.NONE;
        double closestT = Double.POSITIVE_INFINITY;

        for (int i = 0; i < menus.size(); i++) {
            EditorTypeMenusPacket.Menu menu = menus.get(i);
            BlockPos pos = menu.worldPos();
            Vec3 anchor = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);

            Vec3[] basis = EditorPlotLabelsRenderer.basis(anchor, rayOrigin);
            Vec3 right = basis[0], up = basis[1], normal = basis[2];

            Vec3 offset = rayOrigin.subtract(anchor);
            double oz = offset.dot(normal);
            double dz = rayDir.dot(normal);
            if (Math.abs(dz) < 1.0e-6) continue;
            double t = -oz / dz;
            if (t < 0) continue;
            if (t >= closestT) continue;

            double ox = offset.dot(right);
            double oy = offset.dot(up);
            double dx = rayDir.dot(right);
            double dy = rayDir.dot(up);
            double hitX = ox + t * dx;
            double hitY = oy + t * dy;
            // Convert the camera-space hit back to pre-scale panel-local
            // coords so hitFor's rectangles still apply.
            if (worldScale != 1.0) {
                hitX /= worldScale;
                hitY /= worldScale;
            }
            // Companion menus are translated +X in panel-local space after
            // the basis + scale (see EditorTypeMenuRenderer.drawMenu) — undo
            // that shift here so the cell rects line up with the visible
            // panel.
            double shiftX = EditorTypeMenuRenderer.companionShiftX(menu, font);
            if (shiftX != 0) {
                hitX -= shiftX;
            }

            Hovered hit = EditorTypeMenuRenderer.hitFor(i, menu, font, hitX, hitY);
            if (hit.cell() == EditorTypeMenuRenderer.CellKind.NONE) continue;

            closestT = t;
            closest = hit;
        }

        EditorTypeMenuRenderer.setHovered(closest);
    }
}
