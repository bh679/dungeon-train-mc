package games.brennan.dungeontrain.client.menu.plot;

import games.brennan.dungeontrain.client.menu.EditorPlotLabelsRenderer;
import games.brennan.dungeontrain.client.menu.plot.EditorHelpPanelRenderer.Hovered;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.net.EditorTypeMenusPacket;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Camera-ray hover detection for the worldspace editor help panel.
 * Mirrors {@link EditorTypeMenuRaycast} — ray-plane intersection in
 * panel-local coordinates after undoing world scale. The help panel uses
 * its own world-space anchor (see {@link EditorHelpPanelRenderer#helpAnchor})
 * so no panel-local shift is involved.
 */
public final class EditorHelpPanelRaycast {

    private EditorHelpPanelRaycast() {}

    public static void updateHovered() {
        EditorTypeMenusPacket.Menu navMenu = EditorHelpPanelRenderer.firstNavMenu();
        if (navMenu == null) {
            EditorHelpPanelRenderer.setHovered(Hovered.NONE);
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 rayOrigin = camera.getPosition();
        Vector3f lookF = camera.getLookVector();
        Vec3 rayDir = new Vec3(lookF.x, lookF.y, lookF.z);

        double worldScale = ClientDisplayConfig.getWorldspaceScale();

        Vec3 anchor = EditorHelpPanelRenderer.helpAnchor(navMenu);

        Vec3[] basis = EditorPlotLabelsRenderer.basis(anchor, rayOrigin);
        Vec3 right = basis[0], up = basis[1], normal = basis[2];

        Vec3 offset = rayOrigin.subtract(anchor);
        double oz = offset.dot(normal);
        double dz = rayDir.dot(normal);
        if (Math.abs(dz) < 1.0e-6) {
            EditorHelpPanelRenderer.setHovered(Hovered.NONE);
            return;
        }
        double t = -oz / dz;
        if (t < 0) {
            EditorHelpPanelRenderer.setHovered(Hovered.NONE);
            return;
        }

        double ox = offset.dot(right);
        double oy = offset.dot(up);
        double dx = rayDir.dot(right);
        double dy = rayDir.dot(up);
        double hitX = ox + t * dx;
        double hitY = oy + t * dy;

        if (worldScale != 1.0) {
            hitX /= worldScale;
            hitY /= worldScale;
        }

        EditorHelpPanelRenderer.setHovered(EditorHelpPanelRenderer.hitFor(hitX, hitY));
    }
}
