package games.brennan.dungeontrain.client.menu.creakingheart;

import games.brennan.dungeontrain.config.ClientDisplayConfig;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Camera-ray hover for the creaking heart panel: ray-plane intersection against the panel's
 * pinned basis, divided back by the world-space scale, then looked up in the same layout the
 * renderer draws ({@link CreakingHeartPanelLayout#hitFor}). Same reach cap as the Z menu.
 */
public final class CreakingHeartPanelRaycast {

    private static final double MAX_REACH = 12.0;

    private CreakingHeartPanelRaycast() {}

    public static void updateHovered() {
        if (!CreakingHeartPanel.isActive() || Minecraft.getInstance().screen != null) {
            CreakingHeartPanel.setHovered(CreakingHeartPanelLayout.Hit.NONE);
            return;
        }
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vec3 origin = camera.getPosition();
        Vector3f lookF = camera.getLookVector();
        Vec3 dir = new Vec3(lookF.x, lookF.y, lookF.z);

        Vec3 offset = origin.subtract(CreakingHeartPanel.anchor());
        Vec3 normal = CreakingHeartPanel.normal();
        double dz = dir.dot(normal);
        if (Math.abs(dz) < 1.0e-6) {
            CreakingHeartPanel.setHovered(CreakingHeartPanelLayout.Hit.NONE);
            return;
        }
        double t = -offset.dot(normal) / dz;
        if (t < 0 || t > MAX_REACH) {
            CreakingHeartPanel.setHovered(CreakingHeartPanelLayout.Hit.NONE);
            return;
        }
        Vec3 right = CreakingHeartPanel.right();
        Vec3 up = CreakingHeartPanel.up();
        double hitX = offset.dot(right) + t * dir.dot(right);
        double hitY = offset.dot(up) + t * dir.dot(up);
        double worldScale = ClientDisplayConfig.getWorldspaceScale();
        if (worldScale != 1.0) {
            hitX /= worldScale;
            hitY /= worldScale;
        }
        CreakingHeartPanel.setHovered(CreakingHeartPanelLayout.hitFor(CreakingHeartPanelRenderer.layout(), hitX, hitY));
    }
}
