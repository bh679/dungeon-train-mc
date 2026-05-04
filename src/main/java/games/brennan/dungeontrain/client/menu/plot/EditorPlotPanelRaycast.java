package games.brennan.dungeontrain.client.menu.plot;

import games.brennan.dungeontrain.client.menu.EditorPlotLabelsRenderer;
import games.brennan.dungeontrain.client.menu.EditorPlotLabelsRenderer.CellKind;
import games.brennan.dungeontrain.client.menu.EditorPlotLabelsRenderer.Hovered;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.net.EditorPlotLabelsPacket;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.List;

/**
 * Camera-ray hover detection for the floating editor-plot control panels.
 *
 * <p>Loops over every cached entry, intersects the camera ray with each
 * panel's plane (cylindrical billboard around world up — same basis the
 * renderer uses), and picks the closest hit. Reports the matched
 * {@code (entryIndex, cellKind)} via
 * {@link EditorPlotLabelsRenderer#setHovered(Hovered)}.</p>
 *
 * <p>No upper-bound on ray distance — the floating panels are visible at any
 * distance within render distance, so the click should follow the eye. The
 * Name → teleport target especially needs long reach so authors can teleport
 * to a template from anywhere in the world. Closest-hit-wins still resolves
 * overlap if two panels line up along the camera ray; the lower bound
 * ({@code t >= 0}) keeps panels behind the camera from registering.</p>
 */
public final class EditorPlotPanelRaycast {

    private EditorPlotPanelRaycast() {}

    /** Recompute the hovered cell from the current camera frame. Idempotent — safe to call from both render and tick paths. */
    public static void updateHovered() {
        List<EditorPlotLabelsPacket.Entry> entries = EditorPlotLabelsRenderer.entries();
        if (entries.isEmpty()) {
            EditorPlotLabelsRenderer.setHovered(Hovered.NONE);
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 rayOrigin = camera.getPosition();
        Vector3f lookF = camera.getLookVector();
        Vec3 rayDir = new Vec3(lookF.x, lookF.y, lookF.z);

        // Match the renderer's world-space scale so cell rectangles in
        // panel-local units still cover the visible cells after the panel
        // has been scaled in PoseStack.
        double worldScale = ClientDisplayConfig.getWorldspaceScale();

        Hovered closest = Hovered.NONE;
        double closestT = Double.POSITIVE_INFINITY;

        for (int i = 0; i < entries.size(); i++) {
            EditorPlotLabelsPacket.Entry entry = entries.get(i);
            BlockPos pos = entry.worldPos();
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
            // coords so cellAt's rectangles still apply.
            if (worldScale != 1.0) {
                hitX /= worldScale;
                hitY /= worldScale;
            }

            CellKind cell = EditorPlotLabelsRenderer.cellAt(entry, font, hitX, hitY);
            if (cell == CellKind.NONE) continue;

            closestT = t;
            closest = new Hovered(i, cell);
        }

        EditorPlotLabelsRenderer.setHovered(closest);
    }
}
