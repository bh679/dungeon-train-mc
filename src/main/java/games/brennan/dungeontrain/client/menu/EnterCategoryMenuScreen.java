package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.client.EditorStatusHudOverlay;

import java.util.List;
import java.util.Locale;

/**
 * Category selector shown from inside an editor plot. Lets the player
 * jump to a different editor category — Carriages, Tracks, or
 * Contents — via {@code /dt editor &lt;category&gt;}.
 *
 * <p>Two distinct drill-ins depending on the row's relation to the
 * player's current category:
 *
 * <ul>
 *   <li><b>Same category</b> — drills into {@link CategoryTemplatesScreen}
 *       listing every template in that category so the player can
 *       teleport directly to a specific variant without the destructive
 *       {@code /dt editor &lt;cat&gt;} clear-and-restamp cycle.</li>
 *   <li><b>Different category</b> — drills into {@link UnsavedCheckScreen}
 *       which queries the server for dirty plots before dispatching the
 *       destructive switch. Empty dirty list auto-dispatches and closes
 *       (preserving the original one-click flow); a non-empty list
 *       surfaces a per-row Save / View list so the player doesn't lose
 *       work.</li>
 * </ul>
 */
public final class EnterCategoryMenuScreen implements MenuScreen {

    @Override public String title() { return "Enter"; }

    @Override public List<CommandMenuEntry> entries() {
        String current = EditorStatusHudOverlay.category().toLowerCase(Locale.ROOT);
        return List.of(
            entryFor("Tracks", "tracks", current),
            entryFor("Carriages", "carriages", current),
            entryFor("Contents", "contents", current),
            new CommandMenuEntry.Back("< Back")
        );
    }

    /**
     * Build the row for one category:
     * <ul>
     *   <li>If the player is already inside this category, drill into the
     *       template-picker for in-category teleports.</li>
     *   <li>Otherwise drill into the unsaved-check confirmation so the
     *       player can save or knowingly discard before the destructive
     *       category switch.</li>
     * </ul>
     */
    static CommandMenuEntry entryFor(String label, String catId, String currentCategory) {
        if (catId.equals(currentCategory)) {
            return new CommandMenuEntry.DrillIn(label, new CategoryTemplatesScreen(catId), true);
        }
        return new CommandMenuEntry.DrillIn(label, new UnsavedCheckScreen(catId));
    }
}
