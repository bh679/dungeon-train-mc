package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.editor.PlotCategory;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which way each of the editor's world-space panels faces. A panel turns to face the player the
 * first frame it exists — on load, and again after every teleport ({@link #clearAll}) — then holds
 * still as they walk around it. Its top-right {@code ↻} button turns it to face them again
 * ({@link #faceNow}); shift-click snaps it to the grid-aligned default ({@link #reset}).
 *
 * <p>Every basis is {@code {right, up, normal}}, with {@code normal} pointing from the panel toward
 * its reader — the shape {@link EditorPlotLabelsRenderer#basis} returns. Renderer and raycast of a
 * panel must ask with the same key, or clicks drift off the drawn cells.</p>
 *
 * <p>Keys are the panel's anchor block, and panels that must move as one board share a key: the
 * companion menus ride at their per-plot panel's anchor, and the Welcome panel asks with its nav
 * menu's key.</p>
 *
 * <p>Grid defaults (what shift-click resets to):</p>
 * <ul>
 *   <li>{@link #plotPanel()} — per-plot panels sit at each plot's {@code +X} back edge and are read
 *       from on the plot looking {@code +X}, so they face {@code -X}.</li>
 *   <li>{@link #doorPanel(boolean)} — the nav, Welcome and Stages panels sit before the row start:
 *       {@code -X} of an X-row (facing {@code +X}), {@code -Z} of a Z-row (facing {@code +Z}).</li>
 * </ul>
 */
public final class EditorPanelFacing {

    /** Width (panel-local units) of the square {@code ↻} button — one row tall, one row wide. */
    public static final double BUTTON_W = 0.30;
    /** The button glyph; Minecraft's unifont fallback carries it, so it needs no lang key. */
    public static final String BUTTON_GLYPH = "↻";
    /** Cool blue band behind the button, apart from every action-cell tint. */
    public static final int BUTTON_BG = 0x505599FF;
    public static final int BUTTON_COLOR = 0xFFDDEEFF;

    private static final Vec3 UP = new Vec3(0, 1, 0);
    private static final Vec3[] PLOT = basisFacing(new Vec3(-1, 0, 0));
    private static final Vec3[] DOOR_X_ROW = basisFacing(new Vec3(1, 0, 0));
    private static final Vec3[] DOOR_Z_ROW = basisFacing(new Vec3(0, 0, 1));

    /** Anchor block → the basis that panel currently holds. Absent ⇒ face the camera on next ask. */
    private static final Map<BlockPos, Vec3[]> HELD = new ConcurrentHashMap<>();

    private EditorPanelFacing() {}

    /**
     * The basis the panel at {@code key} holds, capturing one that faces {@code cam} if it has none
     * yet. Safe to call from both the render and the tick (raycast) paths.
     */
    public static Vec3[] basis(BlockPos key, Vec3 anchor, Vec3 cam) {
        return HELD.computeIfAbsent(key.immutable(), k -> facing(anchor, cam)).clone();
    }

    /** Turn the panel at {@code key} to face {@code cam} now (the {@code ↻} button). */
    public static void faceNow(BlockPos key, Vec3 anchor, Vec3 cam) {
        HELD.put(key.immutable(), facing(anchor, cam));
    }

    /** Snap the panel at {@code key} to {@code gridDefault} (shift + {@code ↻}). */
    public static void reset(BlockPos key, Vec3[] gridDefault) {
        HELD.put(key.immutable(), gridDefault.clone());
    }

    /** Forget every held facing, so each panel turns to face the player on its next frame. */
    public static void clearAll() {
        HELD.clear();
    }

    /** Grid default for a per-plot panel and its companions. */
    public static Vec3[] plotPanel() {
        return PLOT.clone();
    }

    /** Grid default for a panel at the row start — {@code zRow} for categories whose rows run along Z. */
    public static Vec3[] doorPanel(boolean zRow) {
        return (zRow ? DOOR_Z_ROW : DOOR_X_ROW).clone();
    }

    /** True for the categories whose plot rows extend along {@code +Z} (tracks, dimensional carriages). */
    public static boolean isZRow(String categoryId) {
        PlotCategory cat = PlotCategory.fromId(categoryId).orElse(null);
        return cat == PlotCategory.TRACKS || cat == PlotCategory.PORTALS;
    }

    /** Upright basis whose normal points horizontally from {@code anchor} toward {@code cam}. */
    static Vec3[] facing(Vec3 anchor, Vec3 cam) {
        Vec3 toCam = cam.subtract(anchor);
        Vec3 horiz = new Vec3(toCam.x, 0, toCam.z);
        return basisFacing(horiz.lengthSqr() < 1.0e-6 ? new Vec3(0, 0, 1) : horiz.normalize());
    }

    /** {@code {right, up, normal}} for a horizontal {@code normal}; right = up × normal. */
    static Vec3[] basisFacing(Vec3 normal) {
        Vec3 right = UP.cross(normal).normalize();
        return new Vec3[]{right, UP, normal};
    }
}
