package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.editor.PlotCategory;
import net.minecraft.world.phys.Vec3;

/**
 * Fixed orientations for the editor's world-space panels. They used to billboard toward the camera;
 * now each panel faces one way, like a signpost, so it holds still as you walk past it.
 *
 * <p>Every basis is {@code {right, up, normal}}, with {@code normal} pointing from the panel toward
 * its reader — the same shape {@link EditorPlotLabelsRenderer#basis} returns, so renderers and
 * raycasts use it unchanged. Both sides of a pair must ask for the same basis or clicks drift off
 * the drawn cells.</p>
 *
 * <ul>
 *   <li>{@link #plotPanel()} — per-plot panels (and the companions riding beside them) sit at each
 *       plot's {@code +X} back edge and are read by a player standing on the plot looking {@code +X}
 *       (the {@code EditorPlotArrival} landing yaw), so they face {@code -X}.</li>
 *   <li>{@link #doorPanel(boolean)} — the nav menu, Welcome and Stages panels sit before the row
 *       start: {@code -X} of an X-row, so they face {@code +X}; {@code -Z} of a Z-row (tracks,
 *       dimensional carriages), so they face {@code +Z}.</li>
 * </ul>
 */
public final class EditorPanelFacing {

    private static final Vec3[] PLOT = basisFacing(new Vec3(-1, 0, 0));
    private static final Vec3[] DOOR_X_ROW = basisFacing(new Vec3(1, 0, 0));
    private static final Vec3[] DOOR_Z_ROW = basisFacing(new Vec3(0, 0, 1));

    private EditorPanelFacing() {}

    /** Basis for a per-plot panel and its companions. */
    public static Vec3[] plotPanel() {
        return PLOT.clone();
    }

    /** Basis for a panel at the row start — {@code zRow} for categories whose rows run along Z. */
    public static Vec3[] doorPanel(boolean zRow) {
        return (zRow ? DOOR_Z_ROW : DOOR_X_ROW).clone();
    }

    /** True for the categories whose plot rows extend along {@code +Z} (tracks, dimensional carriages). */
    public static boolean isZRow(String categoryId) {
        PlotCategory cat = PlotCategory.fromId(categoryId).orElse(null);
        return cat == PlotCategory.TRACKS || cat == PlotCategory.PORTALS;
    }

    /** {@code {right, up, normal}} for a horizontal {@code normal}; right = up × normal, as in {@code basis}. */
    static Vec3[] basisFacing(Vec3 normal) {
        Vec3 up = new Vec3(0, 1, 0);
        Vec3 right = up.cross(normal).normalize();
        return new Vec3[]{right, up, normal};
    }
}
