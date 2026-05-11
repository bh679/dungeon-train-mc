package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.config.ClientDisplayConfig;
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

        // Bring the world-space hit back into the panel's logical space —
        // the renderer applies a {@link ClientDisplayConfig#getWorldspaceScale()}
        // uniform scale around every drawn primitive, so the visual half-width
        // is {@code PANEL_WIDTH / 2 * scale}. Dividing here keeps the layout
        // constants below untouched.
        double worldScale = ClientDisplayConfig.getWorldspaceScale();
        if (worldScale != 1.0) {
            hitX /= worldScale;
            hitY /= worldScale;
        }

        List<CommandMenuEntry> entries = CommandMenuState.entries();
        double mainPanelW = CommandMenuState.mainPanelWidth();
        double sidePanelW = CommandMenuState.sidePanelWidth();

        // Main panel hit-test first. If miss, fall through to the optional
        // side panel — keeps the main panel taking precedence when the
        // bounding rectangles overlap in screen space.
        if (!entries.isEmpty()
            && testPanelHit(entries, hitX, hitY, /*panelCenterX=*/0.0, mainPanelW,
                CommandMenuState::setHovered)) {
            CommandMenuState.setSideHovered(-1, 0);
            return;
        }
        CommandMenuState.setHovered(-1, 0);

        if (CommandMenuState.hasSidePanel()) {
            // Side panel sits to the right of the main panel by half of each
            // panel's width plus the inter-panel gap — same arithmetic the
            // renderer uses when translating the side-panel transform.
            double sideOffset = mainPanelW / 2.0 + sidePanelW / 2.0
                + CommandMenuRenderer.SIDE_PANEL_GAP;
            if (testPanelHit(CommandMenuState.sideEntries(), hitX, hitY, sideOffset, sidePanelW,
                    CommandMenuState::setSideHovered)) {
                return;
            }
        }
        CommandMenuState.setSideHovered(-1, 0);
    }

    /**
     * Test {@code (hitX, hitY)} against the panel whose centre sits at
     * {@code panelCenterX} on the panel-local X axis. On hit, calls
     * {@code setHover.accept(rowIdx, subIdx)} and returns true. On miss,
     * returns false and leaves the consumer untouched. Used twice from
     * {@link #updateHovered} — once for the main panel (offset 0) and once
     * for the optional side panel.
     */
    private static boolean testPanelHit(
        List<CommandMenuEntry> entries,
        double hitX, double hitY,
        double panelCenterX, double panelWidth,
        java.util.function.BiConsumer<Integer, Integer> setHover
    ) {
        if (entries.isEmpty()) return false;
        double halfW = panelWidth / 2.0;
        double localX = hitX - panelCenterX;
        if (localX < -halfW || localX > halfW) return false;

        int count = entries.size();
        double halfH = CommandMenuLayout.ROW_HEIGHT / 2.0;
        for (int i = 0; i < count; i++) {
            double centerY = CommandMenuLayout.rowCenterY(i, count);
            if (hitY < centerY - halfH || hitY > centerY + halfH) continue;

            int subIdx = 0;
            CommandMenuEntry row = entries.get(i);
            if (row instanceof CommandMenuEntry.Label) {
                return false;
            }
            if (row instanceof CommandMenuEntry.Split split) {
                double splitX = -halfW + split.leftFraction() * panelWidth;
                if (localX > splitX) subIdx = 1;
            } else if (row instanceof CommandMenuEntry.Quad quad) {
                double b1 = -halfW + quad.boundary1() * panelWidth;
                double b2 = -halfW + quad.boundary2() * panelWidth;
                double b3 = -halfW + quad.boundary3() * panelWidth;
                if (localX > b3) subIdx = 3;
                else if (localX > b2) subIdx = 2;
                else if (localX > b1) subIdx = 1;
                CommandMenuEntry cell = switch (subIdx) {
                    case 1 -> quad.e2();
                    case 2 -> quad.e3();
                    case 3 -> quad.e4();
                    default -> quad.e1();
                };
                if (cell instanceof CommandMenuEntry.Label
                    || (cell instanceof CommandMenuEntry.SaveAction sa && sa.saved())) {
                    return false;
                }
            } else if (row instanceof CommandMenuEntry.Triple triple) {
                double leftBoundary = -halfW + triple.leftFraction() * panelWidth;
                double rightBoundary = -halfW + triple.middleEnd() * panelWidth;
                if (localX > rightBoundary) subIdx = 2;
                else if (localX > leftBoundary) subIdx = 1;
                CommandMenuEntry cell = switch (subIdx) {
                    case 1 -> triple.middleEntry();
                    case 2 -> triple.rightEntry();
                    default -> triple.leftEntry();
                };
                if (cell instanceof CommandMenuEntry.Label
                    || (cell instanceof CommandMenuEntry.SaveAction sa && sa.saved())) {
                    return false;
                }
            }
            setHover.accept(i, subIdx);
            return true;
        }
        return false;
    }
}
