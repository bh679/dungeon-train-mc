package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.config.ClientDisplayConfig;
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
     * The tighter distance {@link EditorMenusMode#AUTO} applies while the player stands in a
     * template, in blocks.
     *
     * <p>Inside a plot the surrounding board is noise — fifteen is about a plot and its neighbour,
     * near enough to reach, far enough to read. Between plots this does not apply at all: there the
     * board is how you find your way to the next one.</p>
     */
    public static final double AUTO_TEMPLATE_DISTANCE =
        ClientDisplayConfig.AUTO_TEMPLATE_DISTANCE_BLOCKS;

    /**
     * Whether a panel anchored at {@code anchor} is near enough to draw for a camera at {@code cam}.
     *
     * <p>Two distances are in play and the smaller wins. {@code maxRenderDistance} is the player's
     * own Menu Distance setting and applies in every mode, in a template or not. On top of that,
     * {@link EditorMenusMode#AUTO} tightens to {@link #AUTO_TEMPLATE_DISTANCE} while
     * {@code insideTemplate} — and only then, because between plots you want the whole board.</p>
     *
     * <p>{@code OFF} short-circuits true: nothing has drawn by the time anything asks this.</p>
     */
    public static boolean withinRange(Vec3 anchor, Vec3 cam, EditorMenusMode mode,
                                      boolean insideTemplate, int maxRenderDistance) {
        if (mode == EditorMenusMode.OFF) return true;
        if (anchor == null || cam == null) return true;
        double limit = maxRenderDistance;
        if (mode == EditorMenusMode.AUTO && insideTemplate) {
            limit = Math.min(limit, AUTO_TEMPLATE_DISTANCE);
        }
        return anchor.distanceToSqr(cam) <= limit * limit;
    }

    /**
     * {@link #withinRange(Vec3, Vec3, EditorMenusMode, boolean, int)} against the live client mode,
     * the live in-a-template answer, and the player's configured Menu Distance.
     */
    public static boolean withinRange(Vec3 anchor, Vec3 cam) {
        return withinRange(anchor, cam, mode, insideTemplate(),
            ClientDisplayConfig.getMenuRenderDistance());
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
