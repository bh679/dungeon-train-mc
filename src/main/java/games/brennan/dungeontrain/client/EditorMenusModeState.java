package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.editor.EditorMenusMode;
import games.brennan.dungeontrain.net.EditorPlotLabelsPacket;

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

    private static boolean anyInPlot(List<EditorPlotLabelsPacket.Entry> snapshot) {
        for (EditorPlotLabelsPacket.Entry e : snapshot) {
            if (e.inPlot()) return true;
        }
        return false;
    }
}
