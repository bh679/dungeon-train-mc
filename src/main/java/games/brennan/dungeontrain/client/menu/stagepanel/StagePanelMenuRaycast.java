package games.brennan.dungeontrain.client.menu.stagepanel;

import games.brennan.dungeontrain.client.menu.EditorPlotLabelsRenderer;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Camera-ray hover detection for the Stage Blocks panel — the single-panel copy of
 * {@link games.brennan.dungeontrain.client.menu.plot.EditorTypeMenuRaycast}: ray-plane
 * intersection against the panel's live billboard basis, hit coords divided back by the
 * world-space scale so {@link StagePanelMenuRenderer#hitFor}'s panel-local rectangles apply.
 */
public final class StagePanelMenuRaycast {

    private StagePanelMenuRaycast() {}

    public static void updateHovered() {
        if (!StagePanelMenu.isActive()) {
            StagePanelMenu.setHovered(StagePanelMenu.Hit.NONE);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 rayOrigin = camera.getPosition();
        Vector3f lookF = camera.getLookVector();
        Vec3 rayDir = new Vec3(lookF.x, lookF.y, lookF.z);

        BlockPos pos = StagePanelMenu.anchor();
        Vec3 anchor = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        Vec3[] basis = EditorPlotLabelsRenderer.basis(anchor, rayOrigin);
        Vec3 right = basis[0], up = basis[1], normal = basis[2];

        Vec3 offset = rayOrigin.subtract(anchor);
        double oz = offset.dot(normal);
        double dz = rayDir.dot(normal);
        if (Math.abs(dz) < 1.0e-6) {
            StagePanelMenu.setHovered(StagePanelMenu.Hit.NONE);
            return;
        }
        double t = -oz / dz;
        if (t < 0) {
            StagePanelMenu.setHovered(StagePanelMenu.Hit.NONE);
            return;
        }
        double hitX = offset.dot(right) + t * rayDir.dot(right);
        double hitY = offset.dot(up) + t * rayDir.dot(up);
        double worldScale = ClientDisplayConfig.getWorldspaceScale();
        if (worldScale != 1.0) {
            hitX /= worldScale;
            hitY /= worldScale;
        }
        StagePanelMenu.setHovered(StagePanelMenuRenderer.hitFor(hitX, hitY));
    }
}
