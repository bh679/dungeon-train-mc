package games.brennan.dungeontrain.client.menu;

import java.util.List;

/**
 * A curated node in the worldspace command menu. Each concrete screen
 * produces a short, hand-chosen list of {@link CommandMenuEntry} rows —
 * deliberately NOT a mirror of the full Brigadier command tree. The goal
 * is a small, discoverable set of actions at each level, matching the
 * spec:
 *
 * <pre>
 * Dungeon Train Menu
 *   Editor (enter)
 *   Train ▸ Spawn, Speed ▸ presets, &lt; Back
 *   Debug Scan
 *
 * Editor Menu (only reachable while in an editor plot)
 *   DevMode [ON/OFF]
 *   Enter ▸ Tracks, Carriages, Architecture
 *   Save | All
 *   Reset | All
 *   &lt; Back  (returns to main menu)
 * </pre>
 *
 * <p>Screens are stateless — {@link #entries()} may be called repeatedly
 * and is expected to read current external state (e.g. devmode flag from
 * {@link games.brennan.dungeontrain.client.EditorStatusHudOverlay}) each
 * call, so toggles reflect the latest server-reported state.
 */
public interface MenuScreen {

    /** Short title shown in the breadcrumb band at the top of the panel. */
    String title();

    /** Current list of entries, in top-to-bottom render order. */
    List<CommandMenuEntry> entries();

    /**
     * Optional companion panel that renders to the right of the main panel
     * for the lifetime of this screen. Use for read-mostly summaries that
     * shouldn't crowd the main action list — e.g. a list of saved user
     * templates next to the Package menu. Returning {@code null} (the
     * default) means no side panel is drawn.
     *
     * <p>The side panel is rendered with the same row primitives as the
     * main panel and is fully click-capable through the same raycast +
     * dispatch path. Side-panel screens themselves should return
     * {@code null} from this method — nested side panels aren't supported.</p>
     */
    default MenuScreen sidePanel() {
        return null;
    }

    /**
     * Horizontal extent of this panel in world units. Defaults to the
     * shared {@link CommandMenuLayout#PANEL_WIDTH}. Override when a
     * screen has unusually wide rows — e.g. the package list, which
     * carries Name | Save | Open | Enable in a single Quad and
     * benefits from a wider main panel so the name cell isn't cramped.
     *
     * <p>Each panel (main + side) is sized independently, so a wider
     * main screen does not force the side panel to widen.</p>
     */
    default double panelWidth() {
        return CommandMenuLayout.PANEL_WIDTH;
    }

    /**
     * How many leading rows stay pinned to the top of the panel when the list scrolls.
     *
     * <p>Defaults to none, which is right for a plain list. {@link EditorMenuScreen} returns 1 so
     * its tab strip stays reachable: the strip is row 0, and a tall Current tab would otherwise
     * scroll away the very control you need in order to leave it.</p>
     */
    default int stickyRows() {
        return 0;
    }

    /**
     * An optional icon button at the right end of the breadcrumb band, or {@code null} (the
     * default) for none.
     *
     * <p>Only the main panel draws it; a side panel's header stays plain text. It exists for the
     * one action worth reaching from every tab — the editor's Save — without spending a row on
     * it, and the renderer hit-tests it separately from the rows so it never shadows row 0.</p>
     */
    default MenuHeaderAction headerAction() {
        return null;
    }
}
