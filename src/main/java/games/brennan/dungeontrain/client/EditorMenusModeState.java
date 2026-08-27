package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.editor.EditorMenusMode;
import games.brennan.dungeontrain.net.EditorPlotLabelsPacket;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Client-side mirror of the player's editor-menus mode, pushed by
 * {@link games.brennan.dungeontrain.net.EditorMenusModePacket}.
 *
 * <p>Held here rather than on {@link EditorStatusHudOverlay} because the status overlay is
 * cleared whenever the player steps out of a plot, and this setting has to survive that — see
 * the packet's own note.</p>
 */
public final class EditorMenusModeState {

    /** Mutated on the main client thread from the packet handler; read from the render thread. */
    private static volatile EditorMenusMode mode = EditorMenusMode.DEFAULT;

    private EditorMenusModeState() {}

    public static EditorMenusMode mode() {
        return mode;
    }

    public static void set(EditorMenusMode next) {
        mode = next == null ? EditorMenusMode.DEFAULT : next;
    }

    /** Back to the default — called when the client disconnects so a fresh session starts clean. */
    public static void reset() {
        mode = EditorMenusMode.DEFAULT;
    }

    /** True while any world-space editor panel may draw — every mode but {@link EditorMenusMode#OFF}. */
    public static boolean menusVisible() {
        return mode != EditorMenusMode.OFF;
    }

    /**
     * Whether the plot panel at {@code index} of {@code snapshot} should draw under {@code mode}.
     *
     * <p>{@link EditorMenusMode#AUTO} shows one panel while the player is inside a plot — the one
     * for that plot — and every panel while they are between plots. {@code ON} shows all of them;
     * {@code OFF} shows none. The "which plot am I in" answer is the server-set
     * {@link EditorPlotLabelsPacket.Entry#inPlot()} flag already on every entry, so this stays a
     * pure function of the snapshot and is unit-testable headless.</p>
     */
    public static boolean isPanelVisible(List<EditorPlotLabelsPacket.Entry> snapshot, int index,
                                         EditorMenusMode mode) {
        if (mode == EditorMenusMode.OFF) return false;
        if (snapshot == null || index < 0 || index >= snapshot.size()) return false;
        if (mode != EditorMenusMode.AUTO) return true;
        EditorPlotLabelsPacket.Entry entry = snapshot.get(index);
        if (entry.inPlot()) return true;
        // Between plots, Auto behaves like On — nothing is being edited, so nothing is competing.
        return !anyInPlot(snapshot);
    }

    /** {@link #isPanelVisible(List, int, EditorMenusMode)} against the live client mode. */
    public static boolean isPanelVisible(List<EditorPlotLabelsPacket.Entry> snapshot, int index) {
        return isPanelVisible(snapshot, index, mode);
    }

    /**
     * How far a world-space editor panel may sit from the camera before {@link EditorMenusMode#AUTO}
     * stops drawing it, in blocks.
     *
     * <p>The editor's panels are spread over the whole build area, so without this the far end of a
     * row keeps painting text across the view of whatever you are actually working on. Fifteen is
     * about a plot and its neighbour — near enough to reach, far enough to read.</p>
     */
    public static final double MAX_PANEL_DISTANCE = 15.0;

    private static final double MAX_PANEL_DISTANCE_SQ = MAX_PANEL_DISTANCE * MAX_PANEL_DISTANCE;

    /**
     * Whether a panel anchored at {@code anchor} is near enough to draw for a camera at {@code cam}.
     *
     * <p>Three conditions have to line up before anything is culled. The mode must be
     * {@link EditorMenusMode#AUTO} — {@code ON} deliberately keeps drawing everything at any range
     * (it is the escape hatch when you want the whole board) and {@code OFF} has already drawn
     * nothing by the time anything asks. The player must be {@code insideTemplate}: between plots
     * the whole board is how you find your way to the next one, so nothing culls there. Only then
     * does distance decide.</p>
     */
    public static boolean withinRange(Vec3 anchor, Vec3 cam, EditorMenusMode mode,
                                      boolean insideTemplate) {
        if (mode != EditorMenusMode.AUTO) return true;
        if (!insideTemplate) return true;
        if (anchor == null || cam == null) return true;
        return anchor.distanceToSqr(cam) <= MAX_PANEL_DISTANCE_SQ;
    }

    /**
     * {@link #withinRange(Vec3, Vec3, EditorMenusMode, boolean)} against the live client mode and
     * the live in-a-template answer.
     */
    public static boolean withinRange(Vec3 anchor, Vec3 cam) {
        return withinRange(anchor, cam, mode, insideTemplate());
    }

    /**
     * Whether the player is standing in an editor plot right now.
     *
     * <p>{@link EditorStatusHudOverlay#isActive()} is already the client's answer to that — the
     * server pushes a status for the plot you are in (part plots included) and clears it the
     * moment you step out — so this reuses it rather than tracking the same thing twice.</p>
     */
    public static boolean insideTemplate() {
        return EditorStatusHudOverlay.isActive();
    }

    private static boolean anyInPlot(List<EditorPlotLabelsPacket.Entry> snapshot) {
        for (EditorPlotLabelsPacket.Entry e : snapshot) {
            if (e.inPlot()) return true;
        }
        return false;
    }
}
