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
}
