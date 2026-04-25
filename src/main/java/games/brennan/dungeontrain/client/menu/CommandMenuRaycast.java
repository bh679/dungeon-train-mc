package games.brennan.dungeontrain.client.menu;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.List;

/**
 * Camera-ray hover detection for the worldspace command menu.
 *
 * <p>Treats the panel as an infinite plane defined by {@code anchorPos}
 * and {@code anchorNormal}. Finds the ray's intersection with that plane
 * once, then tests the hit against each row's 2D rectangle (in the
 * panel's {@code anchorRight} / {@code anchorUp} basis).
 *
 * <p>For {@link CommandMenuEntry.Split} rows, also resolves which of the
 * two buttons was hit by comparing the hit X to the split boundary and
 * writes the {@code subIdx} back to {@link CommandMenuState} so the
 * activate handler can dispatch to the correct command.
 *
 * <p>Reads position and look direction from the active {@link Camera}
 * rather than {@code player.getViewVector} — the camera's transform
 * includes head bob and partial-tick interpolation that the crosshair
 * renders against, so using it keeps the hit point glued to what the
 * player sees.
 */
public final class CommandMenuRaycast {

    /** Maximum raycast distance in blocks. */
    private static final double MAX_REACH = 12.0;

    private CommandMenuRaycast() {}

    public static void updateHovered() {
        if (!CommandMenuState.isOpen()) return;

        Minecraft mc = Minecraft.getInstance();
        Camera camera = mc.gameRenderer.getMainCamera();

        Vec3 rayOrigin = camera.getPosition();
        Vector3f lookF = camera.getLookVector();
        Vec3 rayDir = new Vec3(lookF.x, lookF.y, lookF.z);

        Vec3 anchor = CommandMenuState.anchorPos();
        Vec3 right = CommandMenuState.anchorRight();
        Vec3 up = CommandMenuState.anchorUp();
        Vec3 normal = CommandMenuState.anchorNormal();

        Vec3 offset = rayOrigin.subtract(anchor);
        double ox = offset.dot(right);
        double oy = offset.dot(up);
        double oz = offset.dot(normal);
        double dx = rayDir.dot(right);
        double dy = rayDir.dot(up);
        double dz = rayDir.dot(normal);

        if (Math.abs(dz) < 1.0e-6) {
            CommandMenuState.setHovered(-1, 0);
            return;
        }
        double t = -oz / dz;
        if (t < 0 || t > MAX_REACH) {
            CommandMenuState.setHovered(-1, 0);
            return;
        }

        double hitX = ox + t * dx;
        double hitY = oy + t * dy;

        List<CommandMenuEntry> entries = CommandMenuState.entries();
        if (entries.isEmpty()) {
            CommandMenuState.setHovered(-1, 0);
            return;
        }

        double halfW = CommandMenuLayout.PANEL_WIDTH / 2.0;
        if (hitX < -halfW || hitX > halfW) {
            CommandMenuState.setHovered(-1, 0);
            return;
        }

        int count = entries.size();
        double halfH = CommandMenuLayout.ROW_HEIGHT / 2.0;
        for (int i = 0; i < count; i++) {
            double centerY = CommandMenuLayout.rowCenterY(i, count);
            if (hitY < centerY - halfH || hitY > centerY + halfH) continue;

            int subIdx = 0;
            CommandMenuEntry row = entries.get(i);
            if (row instanceof CommandMenuEntry.Split split) {
                double splitX = -halfW + split.leftFraction() * CommandMenuLayout.PANEL_WIDTH;
                if (hitX > splitX) subIdx = 1;
            } else if (row instanceof CommandMenuEntry.Triple triple) {
                double leftBoundary = -halfW + triple.leftFraction() * CommandMenuLayout.PANEL_WIDTH;
                double rightBoundary = -halfW + triple.middleEnd() * CommandMenuLayout.PANEL_WIDTH;
                if (hitX > rightBoundary) subIdx = 2;
                else if (hitX > leftBoundary) subIdx = 1;
            }
            CommandMenuState.setHovered(i, subIdx);
            return;
        }
        CommandMenuState.setHovered(-1, 0);
    }
}
